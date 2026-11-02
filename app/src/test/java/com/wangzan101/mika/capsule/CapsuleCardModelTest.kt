package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import com.wangzan101.mika.ui.StepRow
import com.wangzan101.mika.ui.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleCardModelTest {
    @get:Rule val texts = ChineseTexts()

    private fun card(
        key: String, state: TaskState, kind: CapsuleKind = CapsuleKind.Running, line: String = "", ahead: Int = 0, behind: Int = 0,
        exchange: Exchange? = null, onScreen: Boolean = true,
    ) = CardRun(CapsuleRun(key, "c-$key", state, goal = "目标$key", exchange = exchange, onScreen = onScreen), kind, line, ahead = ahead, behind = behind)

    /** そのカードはどのことについて話していますか (タスク カードまたは対話カード)。 */
    private fun keyOf(v: CardView?): String? = when (v) {
        is CardView.Detail -> v.run.run.key
        is CardView.Chat -> v.run?.run?.key
        else -> null
    }

    private val running = TaskState.Running(2, "note", "tap")

    // ---------- ただ一つだけ ----------

    @Test fun keptPageCardWhenNothingElse() {
        val kept = KeptPage(3, "c-k", "r-k", "播放课程", "course.example.com", null, null, 0, 7_200_000)
        val v = CapsuleCards.of(emptyList(), null, idle = CardView.Chat("c-x"), kept = kept)
        assertEquals(CardView.Kept(kept), v)
        // カードには入力ボックスもストップもありません。 「ダイアログを開く」は元のダイアログに移動します
        assertNull(CapsuleCards.input(v!!))
        assertNull(CapsuleCards.stopKey(v))
        assertEquals("c-k", CapsuleCards.conversationOf(v))
        // タスクがある場合でもタスクと表示されます (進行中または終了したばかり)
        assertEquals("a", keyOf(CapsuleCards.of(listOf(card("a", running)), null, kept = kept)))
    }

    @Test fun oneRunOpensItsDetailDirectly() {
        val v = CapsuleCards.of(listOf(card("a", running)), null) as CardView.Detail
        assertEquals("a", v.run.run.key)
    }

    @Test fun severalRunsShowOnlyTheFirstInImportanceOrder() {
        // CardRun の順序 (処理する必要がある、進行中、終了したばかり) に従って、最初の項目のみが取得され、リストはありません
        val need = card("n", TaskState.WaitingInput("几点？"), CapsuleKind.NeedAnswer)
        val done = card("d", TaskState.Succeeded("好了"), CapsuleKind.Done)
        assertEquals("n", keyOf(CapsuleCards.of(listOf(need, card("a", running), done))))
        assertEquals("a", keyOf(CapsuleCards.of(listOf(card("a", running), done))))
        // 終了したばかりのもののみ: リストではなく 1 つの作品
        assertEquals("d", keyOf(CapsuleCards.of(listOf(done, card("e", TaskState.Cancelled, CapsuleKind.Cancelled)))))
        assertNull(CapsuleCards.of(emptyList(), "c-a"))
    }

    // ---------- タスクカードまたはダイアログカード ----------

    @Test fun taskCardOnlyWhileOperatingAScreenNow() {
        val waits = listOf(
            running to CapsuleKind.Running,
            TaskState.Held(GateReason("q")) to CapsuleKind.Held,
            TaskState.WaitingTakeover("login", "登录") to CapsuleKind.NeedTakeover,
            TaskState.WaitingInput("几点？") to CapsuleKind.NeedAnswer,
            TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat")) to CapsuleKind.NeedConfirm,
        )
        for ((s, k) in waits) {
            assertTrue("$s on screen", CapsuleCards.of(listOf(card("a", s, k)), null) is CardView.Detail)
            // 純粋なチャット (仮想画面や Web ページなし)、同じステータスはダイアログ カードです
            val chat = CapsuleCards.of(listOf(card("a", s, k, onScreen = false)), null) as CardView.Chat
            assertEquals("c-a", chat.target)
            assertEquals(CapsuleCards.content(card("a", s, k)), chat.content)
        }
    }

    @Test fun finishedAppTaskIsAChatCard() {
        // がアプリを操作し、「現状では対話カードです（画面が解放されていなくても同様です）」と回答しました。
        for ((s, k) in listOf(
            TaskState.Succeeded("好了") to CapsuleKind.Done,
            TaskState.Failed("没找到", FailKind.GaveUp) to CapsuleKind.Failed,
            TaskState.Cancelled to CapsuleKind.Cancelled,
        )) {
            assertTrue("$s", CapsuleCards.of(listOf(card("a", s, k, onScreen = true)), null) is CardView.Chat)
            assertTrue(!CapsuleCards.taskCard(card("a", s, k, onScreen = true)))
        }
        // 操作しているものは純粋なチャットになります（画面が解放されます）：すぐに会話カードに変更されます
        assertTrue(CapsuleCards.taskCard(card("a", running)))
        assertTrue(!CapsuleCards.taskCard(card("a", running, onScreen = false)))
    }

    @Test fun stopKeyWhileStillGoing() {
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", running)), null)!!))
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", running, onScreen = false)), null)!!))
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.WaitingInput("几点？"), CapsuleKind.NeedAnswer, onScreen = false)), null)!!))
        assertNull(CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)), null)!!))
        assertNull(CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.WaitingTakeover("login", "登录"), CapsuleKind.NeedTakeover)), null)!!))
        assertNull(CapsuleCards.stopKey(CardView.Chat("c1")))
    }

    // ---------- 各状態に何を入れるか ----------

    @Test fun workingListsAllStepsEndingWithTheCurrentLine() {
        val ex = Exchange("g", listOf("打开购物", "点搜索框", "输入牙膏", "看结果").map { StepRow(1, it, "", StepState.Ok) }, ExchangeStatus.Working, live = true)
        assertEquals(listOf("打开购物", "点搜索框", "输入牙膏", "看结果", "挑销量高的"), CapsuleCards.steps(ex, "挑销量高的"))
        // この時点の文は最後のステップです: 繰り返しはありません
        assertEquals(listOf("打开购物", "点搜索框", "输入牙膏", "看结果"), CapsuleCards.steps(ex, "看结果"))
        assertEquals(listOf("在想下一步"), CapsuleCards.steps(null, "在想下一步"))
        assertEquals(CardContent.Working(listOf("在想")), CapsuleCards.content(card("a", running, line = "在想")))
    }

    @Test fun takeoverSaysWhyWithPaymentAndQueue() {
        val s = TaskState.WaitingTakeover("payment", "请输入支付密码", item = "牙膏", amount = "¥19.9", address = "家")
        val first = CapsuleCards.content(card("a", s, CapsuleKind.NeedTakeover, "需要你：付款", behind = 2)) as CardContent.Takeover
        assertEquals("需要你：付款", first.need)
        assertEquals("请输入支付密码\n商品: 牙膏\n金額: ¥19.9\n住所: 家", first.body)
        assertEquals("このあと、あと 2 件が待っています", first.queueLine)
        assertTrue(first.canTakeOver)
        val queued = CapsuleCards.content(card("b", s, CapsuleKind.NeedTakeover, ahead = 1)) as CardContent.Takeover
        assertEquals(CapsuleCards.QUEUED_AHEAD, queued.queueLine)
        assertTrue(!queued.canTakeOver)
        assertNull((CapsuleCards.content(card("c", s, CapsuleKind.NeedTakeover)) as CardContent.Takeover).queueLine)
    }

    @Test fun confirmUsesTheChatCardCopy() {
        val s = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.order, "店", "两支牙膏", "¥19.9", "com.shop"))
        val ex = Exchange("g", emptyList(), ExchangeStatus.NeedsConfirmation("要你同意:下单", "在 购物\n金额:¥19.9", 42), live = true)
        assertEquals(CardContent.Confirm("要你同意:下单", "在 购物\n金额:¥19.9", 42), CapsuleCards.content(card("a", s, CapsuleKind.NeedConfirm, exchange = ex)))
        // この質問にまだ答えていなくても同意できます。番号は会話ページや通知に使用される番号と同じです。
        val raw = CapsuleCards.content(card("a", s, CapsuleKind.NeedConfirm)) as CardContent.Confirm
        assertEquals(ConfirmationNotifier.requestKey(s), raw.key)
        assertTrue(raw.body.contains("金額: ¥19.9"))
    }

    @Test fun questionHeldAndResults() {
        assertEquals(CardContent.Question("要哪个？", listOf("大", "小")), CapsuleCards.content(card("a", TaskState.WaitingInput(" 要哪个？ ", listOf("大", "", "小")))))
        assertEquals(CardContent.Question("几点？", emptyList()), CapsuleCards.content(card("a", TaskState.WaitingInput("几点？"))))
        assertEquals(CardContent.Held("排队中", true), CapsuleCards.content(card("a", TaskState.Held(GateReason("q", "排队中", resumable = true)))))
        assertEquals(CardContent.Held("保留中", false), CapsuleCards.content(card("a", TaskState.Held(GateReason("q")))))
        assertEquals(CardContent.Result(CapsuleKind.Done), CapsuleCards.content(card("a", TaskState.Succeeded("下单了"))))
        assertEquals(CardContent.Result(CapsuleKind.Failed), CapsuleCards.content(card("a", TaskState.Failed("没找到", FailKind.GaveUp))))
        assertEquals(CardContent.Result(CapsuleKind.Cancelled), CapsuleCards.content(card("a", TaskState.Cancelled)))
    }

    @Test fun workingStepsAreCappedKeepingTheNewest() {
        val ex = Exchange("g", (1..80).map { StepRow(1, "第 $it 步", "", StepState.Ok) }, ExchangeStatus.Working, live = true)
        val steps = CapsuleCards.steps(ex, "在想下一步")
        assertEquals(CapsuleCards.STEPS, steps.size)
        assertEquals("第 32 步", steps.first())
        assertEquals("在想下一步", steps.last())
    }

    @Test fun screenOnlyBesideWorkTakeoverAndHeld() {
        assertTrue(CapsuleCards.showsScreen(CardContent.Working(emptyList())))
        assertTrue(CapsuleCards.showsScreen(CardContent.Takeover("", "", null, true)))
        assertTrue(CapsuleCards.showsScreen(CardContent.Held("", false)))
        assertTrue(!CapsuleCards.showsScreen(CardContent.Question("", emptyList())))
        assertTrue(!CapsuleCards.showsScreen(CardContent.Result(CapsuleKind.Done)))
    }

    // ---------- 入力ボックス ----------

    @Test fun inputGoesToTheRightPlace() {
        fun inputOf(state: TaskState, kind: CapsuleKind) = CapsuleCards.input(CapsuleCards.of(listOf(card("a", state, kind)), null)!!)?.kind
        assertEquals(CardInputKind.Steer, inputOf(running, CapsuleKind.Running))
        assertEquals(CardInputKind.Steer, inputOf(TaskState.Held(GateReason("q")), CapsuleKind.Held))
        assertEquals(CardInputKind.Answer, inputOf(TaskState.WaitingInput("几点？", listOf("8 点")), CapsuleKind.NeedAnswer))
        assertEquals(CardInputKind.Continue, inputOf(TaskState.Succeeded("好了"), CapsuleKind.Done))
        assertEquals(CardInputKind.Continue, inputOf(TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed))
        // 引継ぎや同意をして欲しい場合は、入力ボックスを表示せずに、まずその件を処理してください。
        assertNull(inputOf(TaskState.WaitingTakeover("login", "登录"), CapsuleKind.NeedTakeover))
        val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))
        assertNull(inputOf(confirm, CapsuleKind.NeedConfirm))
        assertEquals(CardInput(CapsuleCards.HINT_START, CardInputKind.Start), CapsuleCards.input(CardView.Chat(null)))
        assertEquals(CardInput(CapsuleCards.HINT_CONTINUE, CardInputKind.Continue), CapsuleCards.input(CapsuleCards.of(listOf(card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)), null)!!))
        // 純粋なチャットでは、応答中に中断することもできます。同意が必要な場合は、まずその問題に対処してください。
        assertEquals(CardInputKind.Steer, CapsuleCards.input(CapsuleCards.of(listOf(card("a", running, onScreen = false)), null)!!)?.kind)
        assertNull(CapsuleCards.input(CapsuleCards.of(listOf(card("a", confirm, CapsuleKind.NeedConfirm, onScreen = false)), null)!!))
    }

    @Test fun afterSendingTheCardFollowsThatConversation() {
        val done = card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)
        val other = card("b", running)
        // メッセージの送信後、新しいタスクはまだ開始されていません。次の会話には終了した会話のみが含まれています
        assertEquals("a", keyOf(CapsuleCards.of(listOf(other, done), follow = "c-a")))
        // 新しいタスクが起動しています: 進行中のタスクが優先されます
        val again = card("a2", running).let { it.copy(run = it.run.copy(conversationId = "c-a")) }
        assertEquals("a2", keyOf(CapsuleCards.of(listOf(other, done, again), follow = "c-a")))
        // 次のダイアログにはタスクがありません: 最初の項目を順番に実行してください
        assertEquals("b", keyOf(CapsuleCards.of(listOf(other, done), follow = "c-gone")))
    }

    @Test fun idleCardWhenNoRuns() {
        val idle = CardView.Chat("c1", title = "买牙膏")
        assertEquals(idle, CapsuleCards.of(emptyList(), null, idle = idle))
        assertEquals("c1", idle.target)
        assertNull(idle.copy(fresh = true).target)
        // タスクがあるときにアイドルカードを表示しません
        assertTrue(CapsuleCards.of(listOf(card("a", running)), null, idle = idle) is CardView.Detail)
    }

    /**
     * ユーザーが現在参加している会話は、終了してまだ保留中の他の会話よりも優先されます。それ以外の場合、会話ページで新しい会話を開いた後、カプセルをクリックすると、古い会話の結果カードが表示されたままになります。
     * 同じ会話内で終了したばかりのタスクは引き続きカウントされ、進行中のタスクが引き続き優先されます。
     */
    @Test fun idleCardOfTheConversationOnScreenBeatsAnEndedRunFromAnother() {
        val ended = card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)
        val fresh = CardView.Chat(null, fresh = true, current = true)
        assertEquals(fresh, CapsuleCards.of(listOf(ended), null, idle = fresh))
        // 新しいダイアログが送信され、実行中です。タスク カードはまだ同じです
        assertTrue(CapsuleCards.of(listOf(card("r", running)), null, idle = fresh) is CardView.Detail)
        // 同じ会話で終了したばかりのアイテム: (結果とともに) 表示します。空のカードに置き換えないでください。
        assertEquals("a", keyOf(CapsuleCards.of(listOf(ended), null, idle = CardView.Chat("c-a", current = true))))
        // 山の一番下にある「最近の会話」を取得しないでください。これはユーザーが現在いる状況ではありません。
        assertEquals("a", keyOf(CapsuleCards.of(listOf(ended), null, idle = CardView.Chat("c-z", title = "别的"))))
        // タスクはありませんが、バックグラウンドにまだ 1 ページあります。それは依然としてそのページ上のカードであり、アイドル カードによって押し出されることはありません。
        val kept = KeptPage(3, "c-k", "r-k", "播放课程", "course.example.com", null, null, 0, 7_200_000)
        assertEquals(CardView.Kept(kept), CapsuleCards.of(emptyList(), null, idle = fresh, kept = kept))
    }
}
