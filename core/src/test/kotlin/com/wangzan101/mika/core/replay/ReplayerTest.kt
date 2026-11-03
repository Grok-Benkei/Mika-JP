package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.knowledge.Link
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

internal fun ui(ref: String, role: String, name: String, b: List<Int>, rid: String? = null, clickable: Boolean = true, editable: Boolean = false) =
    UiElement(ref, role, name, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2, b, rid, clickable, editable, false)

/** ページごとに移動する偽の携帯電話: 各ページには一連の要素があり、アクションは「現在のページ + クリックした人」でページを変更します。入力されたすべての単語を記録します。 */
internal class PagedScreen(pages: Map<String, List<UiElement>>, start: String = "launcher") : ScreenPort by FakeScreen() {
    val pages = pages.toMutableMap()
    var page = start
    var pkg: String? = null
    val typed = mutableListOf<String>()
    val taps = mutableListOf<String>()
    /** (ページ、クリックした要素名) → 新しいページ */
    val links = mutableMapOf<Pair<String, String>, String>()
    val log = mutableListOf<String>()
    var failReads = false
    var protectedPage: String? = null
    private var n = 0
    private fun snap() = ScreenSnapshot("s${++n}", pkg, pages[page].orEmpty(), 1000, 1000, protected = page == protectedPage)
    override suspend fun snapshot(): ScreenSnapshot { log += "read"; if (failReads) error("unreadable"); return snap() }
    override suspend fun peek() = ScreenSnapshot("peek", pkg, pages[page].orEmpty(), 1000, 1000)
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult { pkg = packageName; page = "home"; return ActionResult.Ok() }
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        val e = pages[page].orEmpty().first { it.ref == ref }
        taps += e.name
        val to = links[page to e.name] ?: return ActionResult.Ok(changed = false)
        page = to
        return ActionResult.Ok(changed = true)
    }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        typed += text
        return ActionResult.Ok(verification = Verification.MATCH)
    }
}

internal class Events : RunSink {
    val all = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { all += e }
    override fun media(mediaId: Int, png: ByteArray) {}
}

internal object Notes {
    val home = listOf(
        ui("@e1", "text", "备忘录", listOf(0, 0, 999, 60), clickable = false),
        ui("@e2", "button", "新建", listOf(800, 900, 999, 999), rid = "n:id/new"),
        ui("@e3", "text", "全部笔记", listOf(0, 60, 999, 120), clickable = false),
    )
    val editor = listOf(
        ui("@e1", "text", "新笔记", listOf(0, 0, 700, 60), clickable = false),
        ui("@e2", "button", "完成", listOf(800, 0, 999, 60)),
        ui("@e3", "edit", "", listOf(0, 100, 999, 900), rid = "n:id/body", editable = true),
    )
    val homeA = Anchors("com.notes", listOf("备忘录", "全部笔记"))
    val editorA = Anchors("com.notes", listOf("新笔记", "完成"))
    val steps = listOf(
        RouteStep("phone_launch_app", """{"package":"com.notes"}""", null, Anchors(null, emptyList()), homeA),
        RouteStep("phone_tap", "{}", Selector("button", "新建", "n:id/new", listOf(800, 900, 999, 999), 0, true), homeA, editorA),
        RouteStep("phone_type_text", """{"text":"{1}"}""", Selector("edit", "", "n:id/body", listOf(0, 100, 999, 900), 0, true), editorA, Anchors("com.notes", emptyList())),
    )
    fun route(steps: List<RouteStep> = this.steps) = Route("r1", "在备忘录里写{1}", 1, listOf("com.notes"), emptyMap(), steps, lastUsedMs = 0)
    fun screen() = PagedScreen(mapOf("home" to home, "editor" to editor)).apply { links["home" to "新建"] = "editor" }
}

/** 再生はループ内と同じです。アクションは実行エントリ、同じガード、確認レベル、レコードを通過します。 */
internal fun replayerOn(d: ToolDispatcher, sink: RunSink, gate: suspend () -> Unit = {}) =
    Replayer(d, ActionExecutor(d, OperationGuards(), ConfirmationGate(), gate, ActionRecorder(sink)), gate = gate, sink = sink)

class ReplayerTest {
    private fun replayer(s: ScreenPort, sink: RunSink = Events(), gate: suspend () -> Unit = {}) = toolDispatcher(s).also { it.captureTargets = true }.let { d ->
        replayerOn(d, sink, gate)
    }

    @Test fun completesWhenAllAnchorsMatch() = runTest {
        val s = Notes.screen(); val sink = Events()
        val out = replayer(s, sink).run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertEquals(ReplayOutcome.Completed(3), out)
        assertEquals(listOf("今天天气不错"), s.typed)
        assertEquals(listOf("新建"), s.taps)
        val replays = sink.all.filterIsInstance<RunEvent.Replay>()
        assertEquals(3, replays.size); assertTrue(replays.all { it.matched })
        // 再生アクションは、ルートに必要なレコードも残し、コール ID に応じてツールの結果とペアになります。
        val targets = sink.all.filterIsInstance<RunEvent.ActionTarget>()
        assertEquals(3, targets.size)
        assertTrue(targets.all { t -> sink.all.filterIsInstance<RunEvent.ToolResult>().any { it.callId == t.callId && it.source == "replay" } })
    }

    @Test fun divergesOnMissingAnchor() = runTest {
        val s = Notes.screen()
        s.pages["home"] = listOf(Notes.home[1], ui("@e4", "text", "登录", listOf(0, 0, 999, 60), clickable = false))
        val out = replayer(s).run(RouteChoice(Notes.route(), listOf("x天气")))
        assertTrue(out is ReplayOutcome.Diverged); assertEquals(1, out.done)
        assertFalse((out as ReplayOutcome.Diverged).executed)
        assertTrue(s.taps.isEmpty()); assertTrue(s.typed.isEmpty())
    }

    @Test fun stopsWhenTargetNotUnique() = runTest {
        // アンカーはすべて存在しますが、同じ名前と ID の「新規」が 2 つあります (リスト内の別の連絡先と同じ名前のボタンなど)。クリックできません。
        val s = Notes.screen()
        s.pages["home"] = Notes.home + ui("@e9", "button", "新建", listOf(820, 880, 999, 980), rid = "n:id/new")
        val out = replayer(s).run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertTrue(out is ReplayOutcome.Diverged); assertEquals(1, out.done)
        assertTrue(s.taps.isEmpty())
    }

    @Test fun stopsBeforeIrreversible() = runTest {
        val s = Notes.screen()
        s.pages["home"] = Notes.home + ui("@e5", "button", "提交订单", listOf(0, 900, 300, 999), rid = "n:id/send")
        val steps = Notes.steps.take(1) + RouteStep("phone_tap", "{}", Selector("button", "提交订单", "n:id/send", listOf(0, 900, 300, 999), 0, true), Notes.homeA, Notes.editorA)
        val out = replayer(s).run(RouteChoice(Notes.route(steps), listOf("x")))
        assertEquals(ReplayOutcome.StoppedForConfirmation(1), out)
        assertTrue(s.taps.isEmpty())
    }

    @Test fun typedTextOnlyFromRouteOrGoal() = runTest {
        val s = Notes.screen()
        val steps = Notes.steps + RouteStep("phone_type_text", """{"text":"固定词"}""", Notes.steps[2].target, Anchors("com.notes", listOf("新笔记")), Anchors("com.notes", emptyList()))
        val out = replayer(s).run(RouteChoice(Notes.route(steps), listOf("周末去爬山")))
        assertEquals(ReplayOutcome.Completed(4), out)
        // ポートが受信する単語のソースは 2 つだけです。スロット値 (元の会話から) とルート内の固定テキストです。
        assertEquals(listOf("周末去爬山", "固定词"), s.typed)
    }

    @Test fun slotNamedTargetResolvedFromNewGoal() = runTest {
        val s = Notes.screen()
        s.pages["home"] = Notes.home + ui("@e6", "button", "购物", listOf(0, 200, 999, 260), rid = "n:id/folder") +
            ui("@e7", "button", "工作", listOf(0, 260, 999, 320), rid = "n:id/folder")
        s.links["home" to "工作"] = "editor"
        val steps = Notes.steps.take(1) + RouteStep("phone_tap", "{}", Selector("button", "{1}", "n:id/folder", listOf(0, 200, 999, 260), 0, true), Notes.homeA, Notes.editorA)
        val route = Route("r2", "打开{1}文件夹", 1, listOf("com.notes"), emptyMap(), steps, lastUsedMs = 0)
        assertEquals(ReplayOutcome.Completed(2), replayer(s).run(RouteChoice(route, listOf("工作"))))
        assertEquals(listOf("工作"), s.taps)
    }

    @Test fun divergesWhenActionHasNoEffect() = runTest {
        val s = Notes.screen()
        s.links.clear() // 「新規」をクリックしても反応がなく、アクション後にアンカーポイントが表示されません。
        val out = replayer(s).run(RouteChoice(Notes.route(), listOf("x")))
        assertTrue(out is ReplayOutcome.Diverged); assertEquals(1, out.done)
        // クリックしましたが、期待した変化は見られませんでした。それを返すときに、このステップが実行されたことをモデルに知らせます。
        assertTrue((out as ReplayOutcome.Diverged).executed)
        assertEquals(listOf("新建"), s.taps)
    }

    @Test fun placeholderInAnyStringArgIsFilledOrRefused() = runTest {
        val s = Notes.screen()
        val bad = listOf(RouteStep("phone_launch_app", """{"package":"{2}"}""", null, Anchors(null, emptyList()), Notes.homeA))
        val out = replayer(s).run(RouteChoice(Notes.route(bad), listOf("x")))
        assertTrue(out is ReplayOutcome.Diverged); assertEquals(null, s.pkg)
        val ok = listOf(RouteStep("phone_launch_app", """{"package":"{1}"}""", null, Anchors(null, emptyList()), Notes.homeA))
        assertEquals(ReplayOutcome.Completed(1), replayer(s).run(RouteChoice(Notes.route(ok), listOf("com.notes"))))
        assertEquals("com.notes", s.pkg)
        val nested = listOf(RouteStep("phone_swipe", """{"startX":{"v":"{1}"}}""", null, Anchors(null, emptyList()), Notes.homeA))
        assertTrue(replayer(s).run(RouteChoice(Notes.route(nested), listOf("1"))) is ReplayOutcome.Diverged)
    }

    @Test fun gateBeforeReadAndProtectedPageSeenHidden() = runTest {
        val s = Notes.screen()
        val out = replayer(s, gate = { s.log += "gate" }).run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertEquals(ReplayOutcome.Completed(3), out)
        assertEquals("gate", s.log.first())
        // 各ステップで画面を読む前に、最初にレベルを通過してください
        assertEquals(3, s.log.windowed(2).count { it == listOf("gate", "read") })

        val p = Notes.screen().apply { protectedPage = "home" }
        val hidden = replayer(p).run(RouteChoice(Notes.route(), listOf("x"))) as ReplayOutcome.Diverged
        assertEquals("a protected page", hidden.seen)
    }

    @Test fun stableRefsTranslatedToPortRefs() = runTest {
        // 安定した番号がオンです: 再生によりモデル番号が読み取られます (デスクトップが最初にいくつかを占有します)。クリックすると、ディストリビュータを通じてこの画面のポート番号に変換されます。
        val s = Notes.screen().apply {
            pages["launcher"] = listOf(ui("@e1", "text", "桌面", listOf(0, 0, 999, 60), clickable = false), ui("@e2", "button", "相机", listOf(0, 100, 200, 200)))
        }
        val sink = Events()
        val d = toolDispatcher(s, stableRefs = true).also { it.captureTargets = true }
        d.execute(com.wangzan101.mika.core.model.Part.ToolCall("o", "phone_observe", "{}"))
        val out = replayerOn(d, sink)
            .run(RouteChoice(Notes.route(), listOf("今天天气不错")))
        assertEquals(ReplayOutcome.Completed(3), out)
        assertEquals(listOf("新建"), s.taps)
        assertEquals(listOf("今天天气不错"), s.typed)
        // 実行記録には 2 セットの番号があります: モデル番号はポート番号ではありません
        val tap = sink.all.filterIsInstance<RunEvent.ToolResult>().single { it.tool == "phone_tap" && it.source == "replay" }
        assertTrue(tap.resultJson, "\"portRef\":\"@e2\"" in tap.resultJson)
    }

    private fun linkRoute() = Notes.route(listOf(
        RouteStep("phone_open_link", """{"uri":"notes://new","package":"com.notes"}""", null, Anchors(null, emptyList()), Notes.editorA),
    ))

    private class LinkScreen(val result: ActionResult) : ScreenPort by Notes.screen() {
        var opened = 0
        override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult { opened++; return result }
    }

    // ルートに記憶されているリンクは、現時点では参考資料にありません。開かず、返すと実行されなかったと表示されます。
    @Test fun linkNoLongerAllowedStopsBeforeOpening() = runTest {
        val s = LinkScreen(ActionResult.Ok(changed = true))
        val d = toolDispatcher(s, deepLinks = true).also { it.captureTargets = true }
        val out = replayerOn(d, Events()).run(RouteChoice(linkRoute(), emptyList()))
        assertTrue(out is ReplayOutcome.Diverged)
        assertFalse((out as ReplayOutcome.Diverged).executed)
        assertEquals(0, s.opened)
    }

    // は実行前に拒否されました (別のタスクがアプリを占有しています): 実行は false で、エラー コードがモデルに与えられます
    @Test fun preExecutionRejectionIsNotExecuted() = runTest {
        val s = LinkScreen(ActionResult.Failed("app_in_use_by_other_task"))
        val d = toolDispatcher(s, deepLinks = true).also { it.captureTargets = true; it.allowLinks(listOf(Link("新建笔记", "notes://new", "com.notes"))) }
        val out = replayerOn(d, Events()).run(RouteChoice(linkRoute(), emptyList()))
        out as ReplayOutcome.Diverged
        assertFalse(out.executed)
        assertEquals("app_in_use_by_other_task", out.error)
        assertEquals(1, s.opened)
    }
}
