package com.wangzan101.mika.core.gate

import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.vision.FRAME_A
import com.wangzan101.mika.core.vision.JvmTestImageOps
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.vision.says
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * と Zhang San のチャット ページ: タイトル、入力ボックス、右下のボタン。チャット内で支払う必要がある「送金 25 円」があります。画面上に支払額があり、ここでの「注文の送信」にはお金が必要です。質問する場合は、まず同意する必要があります。
 * 入力していないときはボタンは「+」ですが、入力すると「注文を送信」になります（多くのチャットアプリがこれを行います）。
 * [snapshot] は画面読み取り用に登録します (番号 s1、s2...)。 [peek] は登録しません (番号 p1、p2...)。
 */
private class ChatScreen(var app: String = "com.chat") : ScreenPort {
    val taps = mutableListOf<String>()
    var reads = 0
    var peeks = 0
    var typed = ""
    var png: ByteArray? = null
    /** 最初の数回のピークの後、画面を読み取ることができません。 */
    var peekFailsAfter = Int.MAX_VALUE
    /** 自己描画インターフェース: 画面読み上げの要素はありません。 */
    var blank = false
    /** は、3 番目のボタン (支払いボタンなど) の単語を置き換えます。 */
    var button: String? = null
    private fun read(id: String) = ScreenSnapshot(id, app, if (blank) emptyList() else listOf(
        UiElement("@e1", "text", "张三", 500, 100, listOf(0, 0, 999, 80), null, false, false, false),
        UiElement("@e2", "edit", typed.ifEmpty { "发消息" }, 400, 2300, listOf(0, 900, 790, 999), null, true, true, false, hint = typed.isEmpty()),
        UiElement("@e3", "button", button ?: if (typed.isEmpty()) "+" else "提交订单", 950, 2300, listOf(800, 900, 990, 990), null, true, false, false),
        UiElement("@e4", "text", "转账 ¥25", 500, 1000, listOf(0, 400, 600, 460), null, false, false, false),
    ), 1080, 2400)
    override suspend fun snapshot(): ScreenSnapshot = read("s${++reads}")
    override suspend fun peek(): ScreenSnapshot {
        if (peeks >= peekFailsAfter) throw IllegalStateException("screen unreadable")
        return read("p${++peeks}")
    }
    override suspend fun screenshotPng(): ByteArray? = png
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { taps += ref; return ActionResult.Ok(changed = true) }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { taps += "$x,$y"; return ActionResult.Ok(changed = true) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { typed = text; return ActionResult.Ok(changed = true) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
    override suspend fun back() = ActionResult.Ok()
    override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
    override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = listOf("com.chat" to "Chat")
}

private class ListSink : RunSink {
    val events = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { events += e }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("g${++seq}", name, args)
private fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
private const val DONE = "送信しました"
private val OBSERVE get() = tools(call("phone_observe"))
private val FINISH get() = com.wangzan101.mika.core.testing.answerReply("送信しました")
private val TYPE get() = tools(call("phone_type_text", """{"text":"明天见","ref":"@e2","snapshotId":"s1"}"""))
private fun tapSend(snapshot: String = "s1") = tools(call("phone_tap", """{"ref":"@e3","snapshotId":"$snapshot"}"""))
private fun ask(action: String = "order", target: String = " 张三 ", content: String = "明天见") =
    tools(call("request_confirmation", """{"action":"$action","target":"$target","content":"$content","amount":"¥25","app":"com.chat"}"""))
private fun failure(msg: String) = tools(call("end_not_done", """{"message":"$msg"}"""))

/** 最後のツールの結果は [i] リクエストになります。 */
private fun FakeModel.result(i: Int): JsonObject =
    Json.parseToJsonElement(requests[i].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content).jsonObject

private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

/** 各ステップでのこれまでの会話に基づいて応答を計算します。@vN の snapshotId はランダムであり、最後のツールの結果から取得する必要があります。 */
private class StepModel(vararg steps: (List<Message>) -> ModelReply) : ChatModel {
    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
    private val steps = steps.toMutableList()
    val results = mutableListOf<JsonObject>()
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        messages.lastOrNull { it.role == Role.Tool }?.parts?.filterIsInstance<Part.ToolResult>()?.firstOrNull()
            ?.let { results += Json.parseToJsonElement(it.content).jsonObject }
        return steps.removeAt(0)(messages)
    }
}

private fun step(r: ModelReply): (List<Message>) -> ModelReply = { r }
private fun locate(target: String, text: String) = step(tools(call("phone_find_visually", """{"target":"$target","text":"$text"}""")))
private fun StepModel.tapLocated(): (List<Message>) -> ModelReply = {
    val r = results.last()
    tools(call("phone_tap", """{"ref":"${r.s("ref")}","snapshotId":"${r.s("snapshotId")}"}"""))
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopGateTest {
    private fun loop(m: FakeModel, s: ChatScreen, sink: RunSink = RunSink.NONE, gate: ConfirmationGate? = ConfirmationGate(), maxSteps: Int = 60) =
        AgentLoop(m, toolDispatcher(s), "BASE", maxSteps = maxSteps, sink = sink, confirmations = gate)

    @Test fun blockedWithoutConfirmation() = runTest {
        val s = ChatScreen(); val sink = ListSink()
        val m = FakeModel(OBSERVE, TYPE, tapSend(), tools(call("phone_type_text", """{"text":"明天见","pressEnter":true}""")), failure("送信していません"))
        assertEquals(TaskState.Failed("送信していません", FailKind.GaveUp), loop(m, s, sink).run("给张三发明天见"))
        assertTrue(s.taps.isEmpty())
        // モデルの手にあるスクリーンリーダー（s1）のボタンは「＋」のままですが、「注文を送信」に変わりました：現時点での判断による
        val r = m.result(3)
        assertEquals("false", r.s("ok")); assertEquals("confirmation_required", r.s("error"))
        assertEquals("order", r.s("action")); assertEquals("提交订单", r.s("matched"))
        assertTrue(r.s("message").startsWith("この操作（order）には支出が伴うため、実行していません。まずrequest_confirmation"))
        assertTrue(r.s("message"), "その成否にかかわらず使い切られる" in r.s("message"))
        // 送信するために入力した後、Enter キーを押します: メッセージの送信には料金はかかりません。画面上に金額が表示されていても、通常どおりに操作してください。
        assertEquals("confirmation_required", m.result(4)["error"]?.jsonPrimitive?.content)
        assertTrue("request_confirmation" in m.offeredTools[0])
        assertEquals(listOf("required", "required"), sink.events.filterIsInstance<RunEvent.Confirmation>().map { it.decision })
    }

    // C1: 画面を読み込んだ後にインターフェースが変わり(「+」が「送信オーダー」に変わります)、ターゲットのない座標クリックもブロックされます。
    @Test fun coordinateTapJudgedOnFreshScreen() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, tools(call("phone_tap", """{"x":900,"y":950}""")), TYPE, tools(call("phone_tap", """{"x":900,"y":950}""")), failure("送信していません"))
        loop(m, s).run("t")
        assertEquals("「+」は通常どおりタップできます", 1, s.taps.size)
        assertEquals("confirmation_required", m.result(4).s("error"))
        assertEquals(1, s.taps.size)
    }

    @Test fun allowedOnceAfterApproval() = runTest {
        val s = ChatScreen(); val sink = ListSink()
        val m = FakeModel(OBSERVE, TYPE, ask(), tapSend(), tapSend(), FINISH)
        val l = loop(m, s, sink)
        val run = async { l.run("给张三发明天见") }
        advanceUntilIdle()
        assertEquals(TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.order, "张三", "明天见", "¥25", "com.chat")), l.state.value)
        l.answerConfirmation(true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, run.await())
        assertEquals("true", m.result(3).s("approved"))
        assertTrue("その成否にかかわらず使い切られる" in m.result(3).s("message"))
        assertEquals(listOf("@e3"), s.taps)
        assertEquals("confirmation_required", m.result(5).s("error"))
        assertEquals(listOf(null, "approved", "required"), sink.events.filterIsInstance<RunEvent.Confirmation>().map { it.decision })
    }

    // C3: 宣言内容をバインドすることに同意します。途中をクリックした場合は無効となります。
    @Test fun approvalBoundToRequestAndNextAction() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, TYPE, ask(target = "李四"), tapSend(), ask(), tools(call("phone_tap", """{"x":500,"y":40}""")), tapSend(), failure("送信していません"))
        val l = loop(m, s)
        val run = async { l.run("t") }
        advanceUntilIdle(); l.answerConfirmation(true)
        advanceUntilIdle(); l.answerConfirmation(true)
        run.await()
        val r = m.result(4)
        assertEquals("confirmation_mismatch", r.s("error"))
        assertTrue(r.s("message"), "request_confirmationを再度呼び" in r.s("message"))
        // 二度目の同意後、タイトル（実行解除）をクリックしました。契約は無効になったので、もう一度送信をクリックして確認する必要がありました。
        assertEquals("confirmation_required", m.result(7).s("error"))
        assertEquals(1, s.taps.size)
    }

    @Test fun declinedReturnsErrorAndModelStops() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, TYPE, ask(), tapSend(), ask(target = "张三"), failure("ユーザーの同意がなかったため、送信していません"))
        val l = loop(m, s)
        val run = async { l.run("给张三发明天见") }
        advanceUntilIdle()
        l.answerConfirmation(false)
        assertEquals(TaskState.Failed("ユーザーの同意がなかったため、送信していません", FailKind.GaveUp), run.await())
        assertEquals("confirmation_declined", m.result(3).s("error"))
        assertEquals("ユーザーは承認しなかった。この操作を再試行しない。現在の状況を報告して終える。", m.result(3).s("message"))
        assertEquals("confirmation_required", m.result(4).s("error"))
        // M2: 同じアプリで同様の操作を実行した場合、質問はされず、直接拒否されます。
        assertEquals("confirmation_declined", m.result(5).s("error"))
        assertTrue(s.taps.isEmpty())
    }

    @Test fun blockDoesNotTripFailureCircuit() = runTest {
        val s = ChatScreen()
        val taps = listOf(800 to 900, 850 to 900, 900 to 900, 950 to 900, 800 to 950, 850 to 950)
            .map { (x, y) -> tools(call("phone_tap", """{"x":$x,"y":$y}""")) }
        val m = FakeModel(OBSERVE, TYPE, *taps.toTypedArray(), FINISH)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m, s).run("t"))
        assertTrue(s.taps.isEmpty())
        for (i in 3..8) {
            val res = m.result(i)
            assertEquals("confirmation_required", res.s("error"))
            assertFalse(res.toString(), "too_many_failures" in res.toString())
        }
    }

    @Test fun mismatchWhenDeclaredAppIsNotOnScreen() = runTest {
        val s = ChatScreen(app = "com.other")
        val m = FakeModel(OBSERVE, ask(), FINISH)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m, s).run("t"))
        assertEquals("confirmation_mismatch", m.result(2).s("error"))
        // M4: 番号を登録せずにフロントデスクで確認してください
        assertEquals(1, s.reads)
    }

    // I1
    @Test fun paymentRequestIsRefused() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, ask(action = "pay"), FINISH)
        val l = loop(m, s)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("t"))
        val r = m.result(2)
        assertEquals("payment_requires_takeover", r.s("error"))
        assertTrue(r.s("message"), "hand_to_user" in r.s("message"))
    }

    @Test fun payTapBlockedPointsToTakeoverOnly() = runTest {
        val s = ChatScreen().apply { button = "立即支付" }
        val m = FakeModel(OBSERVE, tapSend(), FINISH)
        loop(m, s).run("t")
        assertTrue(s.taps.isEmpty())
        val r = m.result(2)
        assertEquals("confirmation_required", r.s("error")); assertEquals("pay", r.s("action"))
        val msg = r.s("message")
        // 支払い命令には回避策があります。最初に申請確認を求めず、それを使用せずに支払いを行うことです。
        assertTrue(msg, "まずrequest_confirmation" !in msg)
        assertTrue(msg, "hand_to_user" in msg && "reason=payment" in msg)
        assertTrue(msg, "item" in msg && "amount" in msg && "address" in msg)
    }

    @Test fun cancelWhileWaitingConfirmation() = runTest {
        val m = FakeModel(OBSERVE, ask())
        val l = loop(m, ChatScreen())
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertTrue(l.state.value is TaskState.WaitingConfirmation)
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
        assertEquals(TaskState.Cancelled, l.state.value)
    }

    @Test fun waitingDoesNotUseSteps() = runTest {
        val s = ChatScreen()
        val l = loop(FakeModel(OBSERVE, TYPE, ask(), tapSend(), FINISH), s, maxSteps = 5)
        val run = async { l.run("t") }
        advanceUntilIdle()
        l.answerConfirmation(true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, run.await())
        assertEquals(listOf("@e3"), s.taps)
    }

    @Test fun approvalDoesNotCarryToNextTask() = runTest {
        val s = ChatScreen()
        // 同意しましたが、まだ送信していないため、回答を送信する必要がありました。最初に 1 回催促され、2 回目は通常どおり承認されました。
        val m = FakeModel(OBSERVE, TYPE, ask(), FINISH, FINISH, OBSERVE, tapSend("s2"), FINISH)
        val l = loop(m, s)
        val first = async { l.run("t1") }
        advanceUntilIdle()
        l.answerConfirmation(true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, first.await())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("t2"))
        assertTrue(s.taps.isEmpty())
        assertEquals("confirmation_required", m.result(7).s("error"))
    }

    @Test fun withoutGateNothingIsBlocked() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, TYPE, tapSend(), ask(), FINISH)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m, s, gate = null).run("t"))
        assertEquals(listOf("@e3"), s.taps)
        assertFalse("request_confirmation" in m.offeredTools[0])
        // M5
        assertEquals("unknown_tool", m.result(4).s("error"))
    }

    // M3
    @Test fun unresolvedTapIsLoggedWithWhere() = runTest {
        val sink = ListSink()
        loop(FakeModel(OBSERVE, tools(call("phone_tap", """{"x":500,"y":500}""")), tools(call("phone_tap", """{"ref":"@e3","snapshotId":"gone"}""")), FINISH), ChatScreen(), sink).run("t")
        val logs = sink.events.filterIsInstance<RunEvent.State>().filter { it.name == "gate_unresolved" }.map { it.detail }
        assertEquals(2, logs.size)
        assertEquals("phone_tap x=500,y=500 freshRead=true", logs[0])
        assertEquals("phone_tap ref=@e3 snapshotId=gone age=unknown", logs[1])
    }

    private fun visionLoop(m: ChatModel, s: ChatScreen, vararg vision: Any, approvals: ApprovalSettings = ApprovalSettings.ASK) = AgentLoop(
        m, toolDispatcher(s, vision = VisionService(says(*vision), JvmTestImageOps)), "BASE", confirmations = ConfirmationGate(),
        approvals = approvals,
    )

    private fun lookFor(target: String) = step(tools(call("phone_find_visually", """{"target":"$target"}""")))

    // 自作インターフェイスの絵を見てわかるポイント（画面読み上げ要素なし）：ビジュアルモデルに「送信」と表示されたら、お金をかけずに直接クリックしてください。
    @Test fun locatedSendOnBlankScreenRunsWithoutAsking() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; blank = true }
        lateinit var m: StepModel
        m = StepModel(lookFor("右下角的图标"), { m.tapLocated()(it) }, step(failure("停")))
        visionLoop(m, s, """{"bbox_2d":[800,900,990,990],"seen":"发送"}""", "是").run("t")
        assertEquals("vision", m.results[0].s("source"))
        assertEquals(0, s.taps.size)
    }

    // 画面の読み取りにはテキストがなく、ビジュアル モデルにもテキストが表示されません。お金がかかることが分からない場合は、とにかくクリックしてください。
    @Test fun locatedTapWithNothingToJudgeRunsWithoutAsking() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; blank = true }
        lateinit var m: StepModel
        m = StepModel(lookFor("右下角的图标"), { m.tapLocated()(it) }, step(failure("停")))
        visionLoop(m, s, """{"bbox_2d":[800,900,990,990]}""", "是").run("t")
        assertEquals(1, s.taps.size)
    }

    // 判定: 自己描画インターフェイス上で再読および確認した後に表示される文字が判定の基礎となります。取り消し不能な単語がない場合は許可されます（痕跡を残します）
    @Test fun locatedTapWithHarmlessSeenIsAllowed() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; blank = true }
        val sink = ListSink()
        lateinit var m: StepModel
        m = StepModel(lookFor("开始按钮"), { m.tapLocated()(it) }, step(FINISH))
        val l = AgentLoop(m, toolDispatcher(s, vision = VisionService(says("""{"bbox_2d":[300,400,700,500],"seen":"开始游戏"}""", "是"), JvmTestImageOps)),
            "BASE", sink = sink, confirmations = ConfirmationGate())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("t"))
        assertEquals(1, s.taps.size)
        assertEquals(1, sink.events.filterIsInstance<RunEvent.State>().count { it.name == "gate_unresolved" })
    }

    // レビュー 2:phone_describe を表示した後、座標をクリックして、画像から取得された点として扱います。
    @Test fun coordinateTapAfterDescriptionIsVisionSourced() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; blank = true }
        val desc = tools(call("phone_describe", """{"task":"点右下角","reason":"读屏是空的"}"""))
        val m = FakeModel(desc, tools(call("phone_tap", """{"x":900,"y":950}""")), tools(call("phone_tap", """{"x":900,"y":950,"target":"右下角图标"}""")), failure("停"))
        visionLoop(m, s, "[屏幕] 自绘页面").run("t")
        val noTarget = m.result(2)
        assertEquals("invalid_arguments", noTarget.s("error")); assertTrue("target" in noTarget.s("message"))
        // どれくらいの金額がかかるかわかりませんが、とにかく注文してください
        assertEquals(1, s.taps.size)
    }

    @Test fun coordinateTapAfterDescriptionOnNamedElementIsAllowed() = runTest {
        val s = ChatScreen().apply { png = FRAME_A }
        val desc = tools(call("phone_describe", """{"task":"看聊天对象","reason":"确认"}"""))
        val m = FakeModel(OBSERVE, desc, tools(call("phone_tap", """{"x":500,"y":40,"target":"标题 张三"}""")), FINISH)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, visionLoop(m, s, "[屏幕] 聊天页").run("t"))
        assertEquals(1, s.taps.size)
    }

    // M6:phone_find_visually で @vN を見つけ、その時点の要素に基づいて判断する
    @Test fun locatedTapIsGated() = runTest {
        val s = ChatScreen().apply { png = FRAME_A }
        lateinit var m: StepModel
        m = StepModel(step(OBSERVE), step(TYPE), locate("提交订单按钮", "提交订单"), { m.tapLocated()(it) }, step(failure("送信していません")))
        assertEquals(TaskState.Failed("送信していません", FailKind.GaveUp), visionLoop(m, s).run("给张三发明天见"))
        assertEquals("@v1", m.results[2].s("ref"))
        assertEquals("confirmation_required", m.results[3].s("error"))
        assertEquals("order", m.results[3].s("action"))
        assertTrue(s.taps.isEmpty())
    }

    @Test fun harmlessLocatedTapIsAllowed() = runTest {
        val s = ChatScreen().apply { png = FRAME_A }
        lateinit var m: StepModel
        m = StepModel(step(OBSERVE), locate("聊天标题", "张三"), { m.tapLocated()(it) }, step(FINISH))
        com.wangzan101.mika.core.testing.assertAnswered(DONE, visionLoop(m, s).run("t"))
        assertEquals(listOf("540,96"), s.taps)
    }

    // その点は現時点では画面上では読み取れません。お金がかかるわけではないので、とりあえずクリックしてみます
    @Test fun locatedTapRunsWhenScreenUnreadable() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; peekFailsAfter = 1 }
        lateinit var m: StepModel
        m = StepModel(step(OBSERVE), locate("聊天标题", "张三"), { m.tapLocated()(it) }, step(FINISH))
        visionLoop(m, s).run("t")
        assertEquals(0, s.taps.size)
    }

    // 申告書に金額を持参する必要があります（お金を使う場合のみ同意が必要です）
    @Test fun requestNeedsAmount() = runTest {
        val noAmount = tools(call("request_confirmation", """{"action":"order","target":"耳机","content":"买一副耳机","app":"com.chat"}"""))
        val m = FakeModel(OBSERVE, noAmount, FINISH)
        loop(m, ChatScreen()).run("t")
        val r = m.result(2)
        assertEquals("invalid_arguments", r.s("error"))
        assertTrue(r.s("message"), "amount" in r.s("message"))
    }

    // 同意が手元にある場合は受け入れられません do_steps: 最初に同意されたステップを個別に実行します。同意はバッチ内のステップでは使用されません。
    @Test fun approvalRejectsDoStepsUpFront() = runTest {
        val s = ChatScreen()
        val batch = tools(call("do_steps", """{"steps":[{"tool":"phone_tap","args":{"ref":"@e3","snapshotId":"s1"}},{"tool":"phone_back","args":{}}]}"""))
        val m = FakeModel(OBSERVE, TYPE, ask(), batch, tapSend(), FINISH)
        val l = AgentLoop(m, toolDispatcher(s), "BASE", confirmations = ConfirmationGate(), flags = SpeedFlags(batchSteps = true))
        val run = async { l.run("给张三发明天见") }
        advanceUntilIdle()
        l.answerConfirmation(true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, run.await())
        assertEquals("approved_step_first", m.result(4).s("error"))
        assertTrue(m.result(4).s("message").startsWith("承認された手順をまず単独で行う"))
        assertEquals(listOf("@e3"), s.taps)
    }
}
