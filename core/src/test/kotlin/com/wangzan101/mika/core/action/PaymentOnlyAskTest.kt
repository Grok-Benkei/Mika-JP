package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.WebInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private fun el(ref: String, role: String, name: String, b: List<Int>, editable: Boolean = false, secret: Boolean = false) =
    UiElement(ref, role, name, 0, 0, b, null, !editable, editable, false, secret = secret)

/** と [title] のチャット ページ: タイトル、入力ボックス、「送信」、「アンインストール」ボタン、パスワード ボックス、通常ボタン、「削除」の行。 */
private fun chatPage(title: String = "张三") = listOf(
    el("@e1", "text", title, listOf(0, 0, 999, 80)),
    el("@e2", "edit", "明天见", listOf(0, 900, 790, 999), editable = true),
    el("@e3", "button", "发送", listOf(800, 900, 990, 990)),
    el("@e4", "button", "卸载", listOf(0, 300, 300, 360)),
    el("@e5", "edit", "", listOf(0, 400, 999, 460), editable = true, secret = true),
    el("@e6", "button", "表情", listOf(400, 300, 600, 360)),
    el("@e7", "button", "删除", listOf(800, 500, 990, 560)),
)

/** チェックアウト ページ: ストア名、合計金額、「注文を送信」および「今すぐ支払う」。 */
private val checkoutPage = listOf(
    el("@e1", "text", "耳机店", listOf(0, 0, 999, 80)),
    el("@e2", "text", "合计 ¥25", listOf(0, 800, 600, 860)),
    el("@e3", "button", "提交订单", listOf(600, 900, 990, 990)),
    el("@e4", "button", "立即支付", listOf(0, 900, 590, 990)),
)

/** 通常の提出画面にある待遇の金額範囲を、今回支払う金額として扱いません。 */
private val applicationPage = listOf(
    el("@e1", "text", "示例对象", listOf(0, 0, 999, 80)),
    el("@e2", "text", "150-200元/天", listOf(0, 200, 999, 260)),
    el("@e3", "button", "立即沟通", listOf(600, 900, 990, 990)),
)

/** 寄付は明示的な金額操作なので、合計の有無にかかわらず確認します。 */
private val donationPage = listOf(
    el("@e1", "text", "公益项目", listOf(0, 0, 999, 80)),
    el("@e2", "text", "150-200元", listOf(0, 200, 999, 260)),
    el("@e3", "button", "确认捐赠", listOf(600, 900, 990, 990)),
)

private class Chat(var app: String = "com.chat", var web: WebInfo? = null, var page: List<UiElement> = chatPage()) : ScreenPort {
    val taps = mutableListOf<String>()
    val typed = mutableListOf<String>()
    var opened = mutableListOf<String>()
    private var reads = 0
    private fun read(id: String) = ScreenSnapshot(id, app, page, 1080, 2400, web = web)
    override suspend fun snapshot(): ScreenSnapshot = read("s${++reads}")
    override suspend fun peek(): ScreenSnapshot = read("p$reads")
    override suspend fun screenshotPng(): ByteArray? = null
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { taps += ref; return ActionResult.Ok(changed = true) }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { taps += "$x,$y"; return ActionResult.Ok(changed = true) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { typed += text; return ActionResult.Ok(changed = true) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
    override suspend fun back() = ActionResult.Ok()
    override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult { opened += packageName; return ActionResult.Ok() }
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = listOf("com.chat" to "Chat")
}

private class Events : RunSink {
    val all = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { all += e }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("m${++seq}", name, args)
private fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
private val OBSERVE get() = tools(call("phone_observe"))
private val FINISH get() = com.wangzan101.mika.core.testing.answerReply("完了しました")
private fun tap(ref: String) = tools(call("phone_tap", """{"ref":"$ref","snapshotId":"s1"}"""))
private fun type(ref: String, text: String) = tools(call("phone_type_text", """{"text":"$text","ref":"$ref","snapshotId":"s1"}"""))
private val ASK_ORDER get() =
    tools(call("request_confirmation", """{"action":"order","target":"耳机店","content":"一副耳机","amount":"¥25","app":"com.chat"}"""))
private val GIVE_UP get() = tools(call("end_not_done", """{"message":"没做"}"""))

private fun FakeModel.result(i: Int): JsonObject =
    Json.parseToJsonElement(requests[i].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content).jsonObject
private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

private class Settable(var auto: Boolean = false) : ApprovalSettings {
    override fun autonomous() = auto
}

/** 質問するときは、最初にお金を使うステップについてのみユーザーに質問してください。他のすべての手順 (送信、削除、アンインストール、パスワード ボックス、一般的な変更) は直接行われます。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentOnlyAskTest {
    private fun loop(
        m: FakeModel, s: Chat, approvals: ApprovalSettings = Settable(), sink: RunSink = RunSink.NONE, timeout: Long? = null,
        flags: SpeedFlags = SpeedFlags.OFF, self: String? = null,
    ) = AgentLoop(m, toolDispatcher(s), "BASE", sink = sink, confirmations = ConfirmationGate(),
        approvals = approvals, confirmTimeoutMs = timeout, flags = flags, selfPackage = self)

    @Test fun sendsWithoutAskingAndAudits() = runTest {
        val s = Chat(); val sink = Events()
        val m = FakeModel(OBSERVE, tap("@e3"), FINISH)
        loop(m, s, sink = sink).run("t")
        assertTrue(s.taps.isEmpty())
        val audit = sink.all.filterIsInstance<RunEvent.Approval>().single()
        assertEquals(listOf("model", "send", "External", "false", "required"),
            listOf(audit.source, audit.action, audit.risk, audit.payment.toString(), audit.decision))
        assertEquals(listOf("required"), sink.all.filterIsInstance<RunEvent.Confirmation>().map { it.decision })
    }

    @Test fun deletingUninstallingTypingPasswordsAndPlainTapsAllRunWithoutAsking() = runTest {
        val s = Chat()
        val m = FakeModel(OBSERVE, tap("@e7"), tap("@e4"), type("@e5", "x"), tap("@e6"), FINISH)
        loop(m, s).run("t")
        assertEquals(listOf("@e4", "@e6"), s.taps)
        assertEquals(listOf("x"), s.typed)
    }

    @Test fun placingAnOrderAsksFirst() = runTest {
        val s = Chat(page = checkoutPage); val sink = Events()
        val m = FakeModel(OBSERVE, tap("@e3"), GIVE_UP)
        loop(m, s, sink = sink).run("t")
        assertTrue(s.taps.isEmpty())
        val r = m.result(2)
        assertEquals("confirmation_required", r.s("error"))
        assertTrue(r.s("message"), "request_confirmation" in r.s("message"))
        val audit = sink.all.filterIsInstance<RunEvent.Approval>().single()
        assertEquals(listOf("true", "required"), listOf(audit.payment.toString(), audit.decision))
    }

    @Test fun salaryRangeDoesNotCauseAConfirmationLoop() = runTest {
        val s = Chat(page = applicationPage); val sink = Events()
        val m = FakeModel(OBSERVE, tap("@e3"), FINISH)
        loop(m, s, sink = sink).run("t")
        assertTrue(s.taps.isEmpty())
        assertEquals("confirmation_required", m.result(2).s("error"))
    }

    @Test fun donationStillRequiresUserTakeover() = runTest {
        val s = Chat(page = donationPage)
        val m = FakeModel(OBSERVE, tap("@e3"), GIVE_UP)
        loop(m, s).run("t")
        assertTrue(s.taps.isEmpty())
        assertEquals("confirmation_required", m.result(2).s("error"))
        assertTrue(m.result(2).s("message"), "hand_to_user" in m.result(2).s("message"))
    }

    @Test fun payingIsHandedToTheUser() = runTest {
        val s = Chat(page = checkoutPage)
        val m = FakeModel(OBSERVE, tap("@e4"), GIVE_UP)
        loop(m, s).run("t")
        assertTrue(s.taps.isEmpty())
        val r = m.result(2)
        assertEquals("confirmation_required", r.s("error"))
        assertTrue(r.s("message"), "hand_to_user" in r.s("message"))
    }

    @Test fun anApprovedOrderRunsOnceOnlyAndTheCardHasOneChoice() = runTest {
        val s = Chat(page = checkoutPage)
        val m = FakeModel(OBSERVE, ASK_ORDER, tap("@e3"), tap("@e3"), GIVE_UP)
        val l = loop(m, s)
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertTrue(l.state.value is TaskState.WaitingConfirmation)
        l.answerConfirmation(ConfirmChoice.Once)
        run.await()
        assertEquals(listOf("@e3"), s.taps)
        assertEquals("confirmation_required", m.result(4).s("error"))
    }

    @Test fun confirmationIsOnlyForSpendingMoney() = runTest {
        // 金額の書き込み（メッセージの送信）ができない場合、アプリケーションはユーザーに渡されず、モデルに直接指示されます。
        val s = Chat()
        val send = tools(call("request_confirmation", """{"action":"send","target":"张三","content":"明天见","app":"com.chat"}"""))
        val m = FakeModel(OBSERVE, send, tap("@e3"), FINISH)
        val l = loop(m, s)
        l.run("t")
        val r = m.result(2)
        assertEquals("false", r.s("ok"))
        assertTrue(r.toString(), "amount" in r.toString())
        assertTrue(s.taps.isEmpty())
        assertEquals("confirmation_required", m.result(3).s("error"))
    }

    @Test fun switchingToAutonomousMidRunStopsAskingEvenForPaymentFromTheNextStep() = runTest {
        val s = Chat(page = checkoutPage)
        val approvals = Settable()
        val m = FakeModel(OBSERVE, tap("@e3"), tap("@e3"), FINISH)
        // 初めて注文を送信するためにクリックしたとき、私はまだ尋ねていました。「止められました。」モデルを再度クリックする前に、ユーザーは自律型に変更されました。
        val flipping = object : ChatModel by m {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (m.requests.size == 2) approvals.auto = true
                return m.complete(system, messages, tools)
            }
        }
        AgentLoop(flipping, toolDispatcher(s), "BASE", confirmations = ConfirmationGate(), approvals = approvals).run("t")
        assertEquals("confirmation_required", m.result(2).s("error"))
        assertEquals(listOf("@e3"), s.taps)
    }

    @Test fun batchStepsSendButStopAtPayment() = runTest {
        val s = Chat()
        val send = tools(call("do_steps", """{"steps":[{"tool":"phone_tap","args":{"ref":"@e3","snapshotId":"s1"}}]}"""))
        val sent = FakeModel(OBSERVE, send, FINISH)
        loop(sent, s, flags = SpeedFlags(batchSteps = true)).run("t")
        assertTrue(s.taps.isEmpty())
        assertEquals("confirmation_required", sent.result(2).s("error"))
        val shop = Chat(page = checkoutPage)
        val order = tools(call("do_steps", """{"steps":[{"tool":"phone_tap","args":{"ref":"@e3","snapshotId":"s1"}}]}"""))
        val m = FakeModel(OBSERVE, order, GIVE_UP)
        loop(m, shop, flags = SpeedFlags(batchSteps = true)).run("t")
        assertTrue(shop.taps.isEmpty())
        assertEquals("confirmation_required", m.result(2).s("error"))
    }

    @Test fun unansweredConfirmationIsDeclinedAfterTheTimeout() = runTest {
        val s = Chat(page = checkoutPage); val sink = Events()
        val m = FakeModel(OBSERVE, ASK_ORDER, GIVE_UP)
        loop(m, s, sink = sink, timeout = 60_000).run("t")
        assertEquals("confirmation_declined", m.result(2).s("error"))
        assertEquals(listOf(null, "timed_out"), sink.all.filterIsInstance<RunEvent.Confirmation>().map { it.decision })
    }

    @Test fun mikaNeverOperatesItself() = runTest {
        // 画面にはミカ自身が表示されます: クリックしないでください
        val s = Chat(app = "com.mika")
        val m = FakeModel(OBSERVE, tap("@e6"), GIVE_UP)
        loop(m, s, self = "com.mika").run("t")
        assertTrue(s.taps.isEmpty())
        assertEquals("own_app", m.result(2).s("error"))
        // 他のアプリからミカを開き、そのアプリケーションの詳細を開きます: これは行わないでください
        s.app = "com.chat"
        val m2 = FakeModel(OBSERVE, tools(call("phone_launch_app", """{"package":"com.mika"}""")), tools(call("phone_open_link", """{"uri":"package:com.mika"}""")), GIVE_UP)
        loop(m2, s, self = "com.mika").run("t")
        assertTrue(s.opened.isEmpty())
        assertEquals("own_app", m2.result(2).s("error"))
        assertEquals("own_app", m2.result(3).s("error"))
    }
}
