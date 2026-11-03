package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.fakeModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ツールは、常に存在する共通ツールと各グループの入り口 (簡単な説明) の 2 つのレイヤーに分かれています。グループを初めて使用するときは、グループ全体 (フルテキスト) が展開され、タスクが終了するまでそのままになります。
 * 順序は固定されており、展開ではそのグループから始まる部分のみが変更されます。前回のラウンドで使用したグループと持ち込まれたワークスペース ファイルは、開始時に事前に展開されます。
 */
class ToolGroupsTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("g${++seq}", name, args)
    private fun all() = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" })
    private fun names(specs: List<ToolSpec>) = specs.map { it.name }

    /** はチャット中にモデルにのみ送信されます: 一般的なツール (引き継ぎ、障害の報告、画面の確認を除く) と 5 つの入り口。 */
    private val chatOnly = listOf(
        "wait", "ask_user", "request_confirmation", "end_not_done",
        "phone_list_apps", "phone_launch_app", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event",
        "web_navigate", "web_read_page", "run_command",
    )

    @Test fun chatOnlyGetsBaseAndEntries() {
        val d = all()
        assertEquals(chatOnly, names(d.offeredNow()))
        assertEquals(emptySet<String>(), d.expanded)
        // 入り口を閉じるときに短い説明を使用します。パラメータは全文と同じです
        val now = d.offeredNow().associateBy { it.name }
        val full = d.definitions().associateBy { it.name }
        for (e in listOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command")) {
            assertEquals(Prompts.entry(e), now.getValue(e).description)
            assertEquals(full.getValue(e).parametersJsonSchema, now.getValue(e).parametersJsonSchema)
            assertTrue(e, now.getValue(e).description.length < full.getValue(e).description.length)
        }
        // すべてのツール (各グループを展開) を一度に 1 つずつ
        assertTrue(names(d.definitions()).containsAll(listOf("phone_tap", "web_tap", "read_file", "hand_to_user", "handle_obstacle", "view_past_screens")))
    }

    @Test fun entryCallExpandsItsGroup() = runTest {
        val d = all()
        val r = d.execute(call("phone_launch_app", """{"package":"com.x"}""")).content
        assertTrue("phone" in r["moreTools"]!!.jsonPrimitive.content)
        assertEquals(setOf("phone"), d.expanded)
        val now = d.offeredNow()
        // 画面でのみ使用できる一般的なツールのセット全体がここにあります。入り口が全文に変更されます。
        assertTrue(names(now).containsAll(listOf("phone_observe", "phone_tap", "hand_to_user", "handle_obstacle", "view_past_screens")))
        assertEquals(d.definitions().single { it.name == "phone_launch_app" }, now.single { it.name == "phone_launch_app" })
        // Web ページとサンドボックスはまだ保存されています
        assertFalse("web_tap" in names(now))
        assertEquals(Prompts.entry("web_navigate"), now.single { it.name == "web_navigate" }.description)
        // 二度目の電話にはもう言葉はありません
        assertNull(d.execute(call("phone_launch_app", """{"package":"com.x"}""")).content["moreTools"])
        assertEquals(setOf("phone"), d.used)
    }

    @Test fun readersAloneDoNotExpand() = runTest {
        val d = all()
        d.execute(call("phone_list_apps"))
        // 目覚まし時計とタイマーだけを設定するだけで十分です。携帯電話をセットで広げないでください。
        d.execute(call("phone_set_alarm", """{"hour":9,"minute":40}"""))
        d.execute(call("phone_set_timer", """{"seconds":600}"""))
        d.execute(call("web_read_page", """{"url":"https://example.com"}"""))
        assertEquals(emptySet<String>(), d.expanded)
        assertEquals(emptySet<String>(), d.used)
        assertEquals(chatOnly, names(d.offeredNow()))
    }

    @Test fun collapsedToolCalledFromMemoryRunsAndExpands() = runTest {
        val d = all()
        // モデルには、phone_observe (携帯電話のグループが保存されている) が表示されなかったので、メモリから調整しました。通常どおりに実行し、携帯電話のグループを展開します。
        val r = d.execute(call("phone_observe")).content
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertTrue(r["moreTools"] != null)
        assertEquals(setOf("phone"), d.expanded)
        // パラメーターが間違っていて展開されています。全文を表示して次回変更できます。
        val w = d.execute(call("web_tap", """{"bogus":1}""")).content
        assertEquals("false", w["ok"]!!.jsonPrimitive.content)
        assertTrue(w["moreTools"] != null)
        assertEquals(setOf("phone", "web"), d.expanded)
    }

    @Test fun orderIsFixedAndExpandingOnlyChangesFromItsGroup() = runTest {
        val d = all()
        val collapsed = d.offeredNow()
        d.execute(call("web_navigate", """{"url":"https://example.com"}"""))
        val web = d.offeredNow()
        d.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val both = d.offeredNow()
        d.execute(call("run_command", """{"command":"ls"}"""))
        val everything = d.offeredNow()
        // いつもそこにあったものは、一字一句変わることなく、常に最前線にあります。
        val base = collapsed.takeWhile { it.name !in setOf("phone_list_apps", "phone_launch_app") }
        for (l in listOf(web, both, everything)) assertEquals(base, l.take(base.size))
        // 最初に Web ページを開き、次に携帯電話を開きます。これは逆と同じです。この順序では、グループのみが参照され、展開の順序は参照されません。
        assertEquals(both, toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" }).also {
            it.expand("phone"); it.expand("web")
        }.offeredNow())
        // はサンドボックス (最後にランク付け) を拡張し、最後だけ変更します。携帯電話と Web ページの最初の 2 つのグループは、一語一語変更されません。
        val runCommandAt = both.indexOfFirst { it.name == "run_command" }
        assertEquals(both.take(runCommandAt), everything.take(runCommandAt))
        // 各グループが展開されます: すべてのツール (グループ順)
        assertEquals(d.definitions().map { it.name }.toSet(), names(everything).toSet())
        assertEquals(everything, d.offeredNow(all = true))
        // 画面を備えた一般的なツールのグループが、常時オンの後ろと携帯電話のグループの前に配置されています
        val i = names(everything)
        assertTrue(i.indexOf("end_not_done") < i.indexOf("hand_to_user") && i.indexOf("view_past_screens") < i.indexOf("phone_observe"))
    }

    @Test fun batchSpecGoesWithScreenGroups() {
        val d = all()
        val batch = ToolSpec("do_steps", "d", "{}")
        assertFalse("do_steps" in names(d.offeredNow(listOf(batch))))
        d.expand("sandbox")
        assertFalse("do_steps" in names(d.offeredNow(listOf(batch))))
        d.expand("web")
        val now = names(d.offeredNow(listOf(batch)))
        // は画面付きの一般ツールのグループの最後に位置し、携帯電話のグループがそれに続きます (クローズド、入り口のみ)
        assertEquals(now.indexOf("handle_obstacle") + 1, now.indexOf("do_steps"))
        assertEquals("phone_list_apps", now[now.indexOf("do_steps") + 1])
    }

    @Test fun preloadRules() {
        // 前回ラウンドで使用したグループ
        assertEquals(listOf("phone", "web"), all().preload("再看一下", listOf("web", "phone")))
        // はファイルをワークスペースに取り込みます (添付ファイルの行には /workspace/ の下のパスが示されています)
        assertEquals(listOf("sandbox"), all().preload("总结一下\n附件:/workspace/inbox/a.pdf(12 KB)", emptyList()))
        // ブラウザ ページからページへの質問: どのページがすでに現在のタブですか?
        val page = com.wangzan101.mika.core.cap.web.AttachedPage.compose(com.wangzan101.mika.core.cap.web.AttachedPage.Page("https://shop.example.com/", "店"), "挑一个")
        assertEquals(listOf("web"), all().preload(page, emptyList()))
        // デバイスはサンドボックスをサポートしていません: 展開しないでください
        val none = toolDispatcher(FakeScreen(), sandbox = FakeSandbox(SandboxStatus.Unsupported("x86")), workspace = { "w" })
        assertEquals(emptyList<String>(), none.preload("附件:/workspace/inbox/a.pdf", listOf("sandbox")))
        // このタスクに含まれていないグループは展開されません。何もない場合は保存されます。
        assertEquals(emptyList<String>(), toolDispatcher(FakeScreen()).preload("看看", listOf("web")))
        assertEquals(emptyList<String>(), all().preload("1+1 等于几", emptyList()))
    }

    @Test fun loopPreloadsFromPreviousTurnAndRecordsUsedGroups() = runTest {
        val m = fakeModel("main") { call("phone_launch_app", "package" to "com.x"); answer("完了しました") }
        val loop = AgentLoop(m, all(), SystemPrompt.identity())
        loop.run("打开看看")
        assertEquals(setOf("phone"), loop.usedGroups)
        // 次のラウンドでは、「前のラウンドで使用された携帯電話」が提供されます。最初の最初のリクエストには、携帯電話ツールの完全なセットと携帯電話セクションが含まれます。
        val m2 = fakeModel("main") { answer("好") }
        AgentLoop(m2, all(), SystemPrompt.identity()).run("再看一下", listOf(Turn("打开看看", "好了", "succeeded", groups = loop.usedGroups.toList())))
        val first = m2.requests.first()
        assertTrue("phone_tap" in first.tools.map { it.name })
        assertFalse("web_tap" in first.tools.map { it.name })
        assertTrue(SystemPrompt.read("phone").lineSequence().first() in first.system)
    }

    @Test fun childAndScheduledRunsUseTheSameLayers() = runTest {
        // サブタスクには Web ページと (読み取り専用) サンドボックスのみがあります。Web ページは入り口のみを提供し、使用時に展開されます。読み取り専用サンドボックスには入り口がなく (コマンドの実行時に提供されません)、そのグループは最初に展開されます。
        val child = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" },
            runSpec = RunSpec(RunId("c"), "查", "t", provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended)))
        val start = names(child.offeredNow())
        assertEquals(listOf("web_navigate", "web_read_page"), start.filter { it.startsWith("web_") || it.startsWith("phone_") })
        assertTrue("read_file" in start && "search_files" in start && "run_command" !in start)
        assertEquals(setOf("sandbox"), child.expanded)
        child.execute(call("web_navigate", """{"url":"https://example.com"}"""))
        assertEquals(setOf("sandbox", "web"), child.expanded)
        assertTrue("web_observe" in names(child.offeredNow()))
        // 時間トリガータスク (誰も存在しない): 引き継ぎなし、残りも階層化されます
        val scheduled = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = RunSpec(RunId("s"), "查", "t",
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended)))
        assertFalse("phone_observe" in names(scheduled.offeredNow()))
        scheduled.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val now = names(scheduled.offeredNow())
        assertTrue("phone_observe" in now && "handle_obstacle" in now && "hand_to_user" !in now)
    }
}
