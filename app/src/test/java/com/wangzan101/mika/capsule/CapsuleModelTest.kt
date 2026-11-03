package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.takeover.Takeover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleModelTest {
    @get:Rule val texts = ChineseTexts()

    private val model = CapsuleModel(reasonLabel = { if (it == "payment") "付款" else null })

    private fun inputs(
        vararg runs: CapsuleRun,
        mode: CapsuleMode = CapsuleMode.DuringTasks,
        foreground: Boolean = false,
        interactive: Boolean = true,
        service: Boolean = true,
        cardOpen: Boolean = false,
        kept: KeptPage? = null,
    ) = CapsuleInputs(runs.toList(), mode, foreground, interactive, service, cardOpen, kept)

    private val kept = KeptPage(1, "c-k", "r-k", "把这门课点开,挂着别关", "course.example.com", null, null, 0, 7_200_000)

    private fun run(key: String, state: TaskState, stage: Takeover.Stage = Takeover.Stage.None) = CapsuleRun(key, "c-$key", state, stage)
    private fun running(said: String? = null, step: Int = 3, reason: String? = null, progress: RunProgress? = null) =
        TaskState.Running(step, "note", "tap", progress, reason, narration = said)
    private val takeover = TaskState.WaitingTakeover("payment", "请输入支付密码")
    private val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))

    private fun shown(v: CapsuleView): CapsuleView.Shown = v as? CapsuleView.Shown ?: error("expected shown, got $v")

    // ---------- 非表示時 ----------

    @Test fun hiddenWhenOffForegroundLockedOrNoService() {
        val r = run("a", running("在搜牙膏"))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, mode = CapsuleMode.Off), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, interactive = false), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, service = false), 0))
        assertEquals("在搜牙膏", shown(model.update(inputs(r), 0)).text)
    }

    @Test fun noTasksHiddenDuringTasksIdleIconWhenAlways() {
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 0))
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(mode = CapsuleMode.Always), 0)).kind)
        // は、Mika インターフェイスに常に表示され、画面がロックされている場合は表示されません。
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Always, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Always, interactive = false), 0))
    }

    // ---------- バックグラウンドで実行されたままのページ ----------

    @Test fun keptPageStaysShownWhenNoTaskRuns() {
        // にはタスクがありません。「バックグラウンド Web ページが実行中」に展開します。これは、元の会話を指します。アイドルアイコンではなく「常に表示」の場合も同様です
        for (mode in listOf(CapsuleMode.DuringTasks, CapsuleMode.Always)) {
            val v = shown(model.update(inputs(mode = mode, kept = kept), 0))
            assertEquals(CapsuleKind.KeptPage, v.kind)
            assertEquals("バックグラウンドのページが動作中", v.text)
            assertEquals("c-k", v.conversationId)
            assertTrue(v.pill)
        }
        // スイッチは「表示しない」ですが、Mika では、画面がロックされているときはまだ表示されません (通知に依存しています)。
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Off, kept = kept), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(foreground = true, kept = kept), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(interactive = false, kept = kept), 0))
    }

    @Test fun runningTaskAndFreshResultComeBeforeKeptPage() {
        assertEquals(CapsuleKind.Running, shown(model.update(inputs(run("a", running("在搜")), kept = kept), 0)).kind)
        // 処理されたばかりの結果はしばらく一時停止されます。一時停止後もページは残ります。
        val done = run("a", TaskState.Succeeded("好了"))
        assertEquals(CapsuleKind.Done, shown(model.update(inputs(done, kept = kept), 10)).kind)
        assertEquals(CapsuleKind.KeptPage, shown(model.update(inputs(done, kept = kept), 10 + CapsuleModel.LINGER_MS)).kind)
        assertTrue(!CapsuleKind.KeptPage.active && !CapsuleKind.KeptPage.needsYou)
    }

    @Test fun modeParsesWithAlwaysDefault() {
        assertEquals(CapsuleMode.Always, CapsuleMode.parse(null))
        assertEquals(CapsuleMode.Always, CapsuleMode.parse("something-old"))
        CapsuleMode.entries.forEach { assertEquals(it, CapsuleMode.parse(it.name)) }
    }

    // ---------- その行 ----------

    @Test fun runningTextNarrationThenReasonThenFallback() {
        assertEquals("在搜 牙膏", CapsuleModel.runningText(running("  在搜\n牙膏 ", reason = "找搜索框")))
        assertEquals("找搜索框", CapsuleModel.runningText(running(" ", reason = "找搜索框")))
        assertEquals(CapsuleModel.THINKING, CapsuleModel.runningText(running()))
        assertEquals(CapsuleModel.PREPARING, CapsuleModel.runningText(running(step = 0)))
        assertEquals("手順をたどっています 3/7", CapsuleModel.runningText(running("在搜牙膏", progress = RunProgress.Replay(3, 7))))
        assertEquals("4 ステップを一度に", CapsuleModel.runningText(running(progress = RunProgress.Batch(4))))
    }

    @Test fun eachStateHasItsLine() {
        fun line(state: TaskState, stage: Takeover.Stage = Takeover.Stage.None) =
            shown(CapsuleModel(reasonLabel = { if (it == "payment") "付款" else null }).update(inputs(run("a", state, stage)), 0))
        assertEquals(CapsuleKind.NeedTakeover to "対応が必要: 付款", line(takeover, Takeover.Stage.Waiting).let { it.kind to it.text })
        assertEquals("対応が必要: 请登录", line(TaskState.WaitingTakeover("login", "请登录"), Takeover.Stage.Waiting).text)
        assertEquals(CapsuleKind.NeedConfirm to "許可が必要: 送信", line(confirm).let { it.kind to it.text })
        assertEquals(CapsuleKind.NeedAnswer to "Mika からの質問: 要哪个颜色？", line(TaskState.WaitingInput("要哪个颜色？")).let { it.kind to it.text })
        assertEquals(CapsuleKind.Held to "保留中: 你打开了这个 App", line(TaskState.Held(GateReason("paused", "你打开了这个 App"))).let { it.kind to it.text })
        // は携帯電話を移動してほしいと思っています (システム選択ボックスで 1 つを選択してください): あなたに処理してもらいたいです
        val pick = line(TaskState.Held(GateReason("waiting_user", "「聊天」在问用哪个，请在屏幕上选一下", needsYou = true)))
        assertEquals(CapsuleKind.NeedStep to "対応が必要: 「聊天」在问用哪个，请在屏幕上选一下", pick.kind to pick.text)
        assertTrue(pick.kind.needsYou)
        assertEquals("保留中", line(TaskState.Held(GateReason("waiting_unlock"))).text)
        val bar = line(takeover, Takeover.Stage.HandedOver)
        assertEquals(CapsuleKind.HandedOver, bar.kind)
        assertEquals(CapsuleModel.HANDED_OVER, bar.text)
        assertEquals("付款 · 終わったら右側をタップ", bar.detail)
        // は返還されましたが、そのサイクルはまだ続いていません。もうあなたは必要ありません。
        assertEquals(CapsuleKind.Running, line(takeover).kind)
    }

    // ---------- 優先順位と個数 ----------

    @Test fun highestPriorityWinsAndCountsTasks() {
        val v = shown(model.update(inputs(run("a", running("在搜牙膏")), run("b", TaskState.WaitingInput("要哪个？"))), 0))
        assertEquals(CapsuleKind.NeedAnswer, v.kind)
        assertEquals("b", v.runKey)
        assertEquals("c-b", v.conversationId)
        assertEquals(2, v.count)
        assertEquals(1, v.needCount)
        assertEquals("1 件が対応待ち · 全部で 2 件", v.text)
    }

    @Test fun severalRunningAddCount() {
        val v = shown(model.update(inputs(run("a", running("在搜牙膏")), run("b", running("在看天气"))), 0))
        // 事の始まりは、
        assertEquals("a", v.runKey)
        assertEquals("在搜牙膏 · 全部で 2 件", v.text)
        assertEquals(0, v.needCount)
    }

    @Test fun priorityOrder() {
        val held = TaskState.Held(GateReason("paused", "等一下"))
        val all = listOf(
            run("run", running("x")), run("held", held), run("ask", TaskState.WaitingInput("?")), run("ok", confirm),
            run("need", takeover, Takeover.Stage.Waiting), run("hand", TaskState.WaitingTakeover("login", "登录"), Takeover.Stage.HandedOver),
        )
        val expected = listOf("hand", "need", "ok", "ask", "held", "run")
        for (i in expected.indices) {
            val left = all.filter { it.key in expected.drop(i) }
            assertEquals(expected[i], shown(CapsuleModel().update(inputs(*left.toTypedArray()), 0)).runKey)
        }
    }

    @Test fun handedOverBarSaysWhoIsWaitingBehind() {
        val v = shown(model.update(inputs(
            run("a", takeover, Takeover.Stage.HandedOver), run("b", TaskState.WaitingTakeover("login", "登录"), Takeover.Stage.Waiting),
        ), 0))
        assertEquals(CapsuleKind.HandedOver, v.kind)
        assertEquals("このあと、あと 1 件が待っています", v.detail)
    }

    // ---------- 各文ごとに少なくとも 1.5 秒間一時停止してください ----------

    @Test fun sentenceStaysAtLeastMinimumAndLatestWins() {
        assertEquals("一", shown(model.update(inputs(run("a", running("一"))), 0)).text)
        assertEquals("一", shown(model.update(inputs(run("a", running("二"))), 500)).text)
        val v = shown(model.update(inputs(run("a", running("三"))), 900))
        assertEquals("一", v.text)
        assertEquals(CapsuleModel.MIN_SENTENCE_MS, model.nextChangeAt)
        // 時間が来たらその時点の最新の文章に置き換えると途中の文章は表示されません。
        assertEquals("三", shown(model.update(inputs(run("a", running("三"))), CapsuleModel.MIN_SENTENCE_MS)).text)
        assertNull(model.nextChangeAt)
    }

    @Test fun stateChangeSwitchesAtOnce() {
        model.update(inputs(run("a", running("一"))), 0)
        val v = shown(model.update(inputs(run("a", TaskState.WaitingInput("要哪个？"))), 100))
        assertEquals("Mika からの質問: 要哪个？", v.text)
    }

    // ---------- 幅が広いだけで狭くはありません ----------

    @Test fun widthEpochHoldsWithinStateAndRenewsOnChange() {
        val first = shown(model.update(inputs(run("a", running("一"))), 0)).widthEpoch
        assertEquals(first, shown(model.update(inputs(run("a", running("长一点的一句话"))), 2_000)).widthEpoch)
        assertEquals(first, shown(model.update(inputs(run("a", running("短"))), 4_000)).widthEpoch)
        val need = shown(model.update(inputs(run("a", TaskState.WaitingInput("?"))), 5_000)).widthEpoch
        assertNotEquals(first, need)
        val back = shown(model.update(inputs(run("a", running("一"))), 6_000)).widthEpoch
        assertNotEquals(need, back)
        // 1 つ変更し、幅をリセットしました
        assertNotEquals(back, shown(model.update(inputs(run("b", running("一"))), 7_000)).widthEpoch)
    }

    // ---------- ひとつはまだ展開中 ----------

    @Test fun runningRestsAsBallAndStepsNeverExpandIt() {
        val first = shown(model.update(inputs(run("a", running("在搜牙膏"))), 0))
        assertEquals(CapsuleKind.Running to false, first.kind to first.pill)
        // はいくつかのステップを変更しました: まだ 1 つ
        for ((t, said) in listOf(2_000L to "在比价", 4_000L to "在看运费", 6_000L to "在下单")) {
            assertTrue(!shown(model.update(inputs(run("a", running(said))), t)).pill)
        }
        assertTrue(!shown(model.update(inputs(run("a", TaskState.Held(GateReason("paused", "等一下")))), 7_000)).pill)
    }

    @Test fun severalRunsCountOnTheBall() {
        val v = shown(model.update(inputs(run("a", running("x")), run("b", running("y")), run("c", TaskState.Held(GateReason("paused")))), 0))
        assertEquals(3, v.count)
        assertTrue(!v.pill)
    }

    @Test fun needYouStaysAPillUntilTheCardIsOpened() {
        model.update(inputs(run("a", running("x"))), 0)
        val need = shown(model.update(inputs(run("a", confirm)), 100))
        assertEquals(CapsuleKind.NeedConfirm to true, need.kind to need.pill)
        // は自動的に撤回されず、次の変更をスケジュールすることもありません。
        assertNull(model.nextChangeAt)
        assertTrue(shown(model.update(inputs(run("a", confirm)), 600_000)).pill)
        // カードを開いて見てみました。しまったら、1個（まだオレンジ色）ありました。
        model.update(inputs(run("a", confirm), cardOpen = true), 600_100)
        val ball = shown(model.update(inputs(run("a", confirm)), 600_200))
        assertEquals(CapsuleKind.NeedConfirm to false, ball.kind to ball.pill)
    }

    @Test fun aNewNeedExpandsAgain() {
        model.update(inputs(run("a", confirm)), 0)
        model.update(inputs(run("a", confirm), cardOpen = true), 10)
        assertTrue(!shown(model.update(inputs(run("a", confirm)), 20)).pill)
        // 同じものが別の wait:Expand に置き換えられます。
        val ask = TaskState.WaitingInput("要哪个颜色？")
        assertTrue(shown(model.update(inputs(run("a", ask)), 30)).pill)
        model.update(inputs(run("a", ask), cardOpen = true), 40)
        assertTrue(!shown(model.update(inputs(run("a", ask)), 50)).pill)
        // もう 1 つ要望があります: 「対処してほしい 2 つのこと」を展開して書き込みます
        val two = shown(model.update(inputs(run("a", ask), run("b", takeover, Takeover.Stage.Waiting)), 60))
        assertEquals("2 件が対応待ち · 全部で 2 件" to true, two.text to two.pill)
    }

    @Test fun aNeedThatComesBackAfterBeingResolvedExpandsAgain() {
        model.update(inputs(run("a", confirm)), 0)
        model.update(inputs(run("a", confirm), cardOpen = true), 10)
        model.update(inputs(run("a", running("x"))), 20)
        assertTrue(shown(model.update(inputs(run("a", confirm)), 30)).pill)
    }

    // ---------- 完了後 ----------

    @Test fun doneExpandsThenBallWithBadgeThenGone() {
        model.update(inputs(run("a", running("x"))), 0)
        val done = shown(model.update(inputs(run("a", TaskState.Succeeded("好了"))), 1_000))
        assertEquals(CapsuleKind.Done to true, done.kind to done.pill)
        assertEquals("c-a", done.conversationId)
        assertEquals(1_000 + CapsuleModel.PILL_MS, model.nextChangeAt)
        // タスクはリストから削除され、モデルはそれを独自に記憶します。引っ込めても完了（コーナーマーク）
        val ball = shown(model.update(inputs(), 1_000 + CapsuleModel.PILL_MS))
        assertEquals(CapsuleKind.Done to false, ball.kind to ball.pill)
        assertEquals(1_000 + CapsuleModel.LINGER_MS, model.nextChangeAt)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 1_000 + CapsuleModel.LINGER_MS))
    }

    @Test fun afterLingerAlwaysModeReturnsToIdle() {
        model.update(inputs(run("a", TaskState.Failed("没找到", FailKind.GaveUp)), mode = CapsuleMode.Always), 0)
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(mode = CapsuleMode.Always), CapsuleModel.LINGER_MS)).kind)
    }

    @Test fun endedRunStaysGoneThoughStillListed() {
        // タスク リストが変更されない場合、呼び出し元は停止したタスクをリストし続けます。
        val a = run("a", TaskState.Cancelled)
        assertTrue(shown(model.update(inputs(a), 0)).pill)
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), CapsuleModel.LINGER_MS))
        // ミカを入力して出てきます: 「停止」を展開しなくなりました
        assertEquals(CapsuleView.Hidden, model.update(inputs(a, foreground = true), CapsuleModel.LINGER_MS + 1_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), CapsuleModel.LINGER_MS + 2_000))
        assertNull(model.nextChangeAt)
    }

    @Test fun endedWhileHiddenCountsItsTimeToo() {
        // はミカで終了し、他のアプリに戻るのに 10 秒かかりました。再度表示されません。
        val a = run("a", TaskState.Succeeded("好了"))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 60_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 60_100))
        // は、画面がオフまたはロックされている場合も同様に終了します。
        val b = run("b", TaskState.Cancelled)
        assertEquals(CapsuleView.Hidden, model.update(inputs(b, interactive = false), 100_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(b), 200_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(b), 200_100))
    }

    @Test fun failedAndCancelledHaveTheirLines() {
        assertEquals(CapsuleKind.Failed, shown(CapsuleModel().update(inputs(run("a", TaskState.Failed("x", FailKind.Model))), 0)).kind)
        val stopped = shown(CapsuleModel().update(inputs(run("a", TaskState.Cancelled)), 0))
        assertEquals(CapsuleKind.Cancelled to "停止しました", stopped.kind to stopped.text)
    }

    @Test fun activeTaskOutranksLingeringDone() {
        model.update(inputs(run("a", TaskState.Succeeded("好了"))), 0)
        val v = shown(model.update(inputs(run("b", running("在看天气"))), 100))
        assertEquals(CapsuleKind.Running, v.kind)
        assertEquals("b", v.runKey)
        // 端は「合計 N 個」にはカウントされません
        assertEquals(1, v.count)
    }

    // ---------- カードを広げたとき ----------

    @Test fun openCardStopsTheClockForEndedRuns() {
        model.update(inputs(run("a", running("x"))), 0)
        model.update(inputs(run("a", TaskState.Succeeded("好了")), cardOpen = true), 1_000)
        // が展開中: 時間が経っても消えず、次の変更は予定されていません。
        val v = shown(model.update(inputs(cardOpen = true), 100_000))
        assertEquals(CapsuleKind.Done, v.kind)
        assertNull(model.nextChangeAt)
        assertEquals(listOf("a"), model.cardRuns().map { it.run.key })
        // 畳んだ後：見たよ、角マークの星、そしてずっと続けて
        val after = shown(model.update(inputs(), 200_000))
        assertEquals(CapsuleKind.Done to false, after.kind to after.pill)
        assertEquals(200_000 + CapsuleModel.LINGER_MS, model.nextChangeAt)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 200_000 + CapsuleModel.LINGER_MS))
    }

    @Test fun openCardKeepsAlreadyLingeringRunsUntilClosed() {
        model.update(inputs(run("a", TaskState.Succeeded("好了"))), 0)
        model.update(inputs(cardOpen = true), 5_000)
        assertEquals(1, model.cardRuns().size)
        model.update(inputs(cardOpen = true), 1_000_000)
        assertEquals(1, model.cardRuns().size)
        // 展開前に経過した時間は引き続きカウントされます。折りたたみ後は、LINGER_MS - 5_000 だけが残ります。
        assertTrue(!shown(model.update(inputs(), 1_000_000)).pill)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 1_000_000 + CapsuleModel.LINGER_MS - 5_000))
    }

    @Test fun idleIconOpensTheIdleCardAfterAResultHasGone() {
        val a = run("a", TaskState.Cancelled)
        model.update(inputs(a, mode = CapsuleMode.Always), 0)
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(a, mode = CapsuleMode.Always), CapsuleModel.LINGER_MS)).kind)
        // 発信者がまだ停止したタスクをリストしている場合は、アイドル アイコンをクリックします。これはアイドル カードであり、タスクではありません。
        val v = shown(model.update(inputs(a, mode = CapsuleMode.Always, cardOpen = true), CapsuleModel.LINGER_MS + 60_000))
        assertEquals(CapsuleKind.Idle, v.kind)
        val idle = CardView.Chat("c-b", title = "订票")
        assertEquals(idle, CapsuleCards.of(model.cardRuns(), idle = idle))
    }

    @Test fun deletedConversationsEndedRunsLeaveAtOnce() {
        val a = run("a", TaskState.Cancelled)
        val a2 = CapsuleRun("a2", "c-a", TaskState.Succeeded("好了"))
        val b = run("b", TaskState.Succeeded("好了"))
        val r = run("r", running("在搜"))
        model.update(inputs(a, a2, b, r), 0)
        // この会話で終了したすべてのアイテムを削除します。
        model.forget("c-a")
        // は通常通り継続中
        model.forget("c-r")
        // 発信者はまだリストに表示されており、戻ってきません。
        model.update(inputs(a, a2, b, r), 100)
        assertEquals(listOf("r", "b"), model.cardRuns().map { it.run.key })
        model.forget("c-b")
        // は削除された時点ではまだ進行中であり、終了後は通常どおり表示されます (削除された会話が終了するまで待機しない場合は、終了したラウンドが再確立されます)。
        val v = shown(model.update(inputs(a, a2, b, run("r", TaskState.Cancelled)), 200))
        assertEquals(CapsuleKind.Cancelled to "c-r", v.kind to v.conversationId)
        assertEquals(listOf("r"), model.cardRuns().map { it.run.key })
    }

    @Test fun deletedConversationLeavesTheOpenCardToo() {
        val a = run("a", TaskState.Cancelled)
        model.update(inputs(a), 0)
        model.update(inputs(a, cardOpen = true), 1_000)
        // カードを広げたら削除（時計を停止）：すぐに取り出し、しまった後は戻さないでください。
        model.forget("c-a")
        assertTrue(model.cardRuns().isEmpty())
        model.update(inputs(a, cardOpen = true), 1_100)
        assertTrue(model.cardRuns().isEmpty())
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 5_000))
        assertNull(model.nextChangeAt)
    }

    @Test fun cardRunsActiveByPriorityThenEndedNewestFirst() {
        model.update(inputs(run("old", TaskState.Succeeded("a")), run("r", running("在搜"))), 0)
        model.update(inputs(run("new", TaskState.Failed("x", FailKind.Model)), run("r", running("在搜")), run("q", TaskState.WaitingInput("要哪个？"))), 10)
        val rows = model.cardRuns()
        assertEquals(listOf("q", "r", "new", "old"), rows.map { it.run.key })
        assertEquals(CapsuleKind.NeedAnswer to "Mika からの質問: 要哪个？", rows[0].kind to rows[0].line)
        assertEquals(0L, rows[1].startedAt)
        assertNull(rows[0].startedAt)
        assertEquals(CapsuleKind.Failed, rows[2].kind)
    }

    @Test fun cardRunsKnowTakeoverQueueOrder() {
        model.update(inputs(run("a", takeover, Takeover.Stage.Waiting)), 0)
        model.update(inputs(run("a", takeover, Takeover.Stage.Waiting), run("b", takeover, Takeover.Stage.Waiting)), 10)
        val rows = model.cardRuns().associateBy { it.run.key }
        assertEquals(0 to 1, rows.getValue("a").ahead to rows.getValue("a").behind)
        assertEquals(1 to 0, rows.getValue("b").ahead to rows.getValue("b").behind)
        // 前の項目が返されます。後ろの項目は前のキューに入れられます。
        model.update(inputs(run("a", running("接着做")), run("b", takeover, Takeover.Stage.Waiting)), 20)
        assertEquals(0, model.cardRuns().first { it.run.key == "b" }.ahead)
    }

    // ---------- テイクオーバーキュー ----------

    private fun queued(key: String, ahead: Int, behind: Int, stage: Takeover.Stage = Takeover.Stage.Waiting) =
        run(key, takeover, stage).copy(queueAhead = ahead, queueBehind = behind)

    @Test fun realQueueOrderBeatsFirstSeen() {
        // a が最初に表示されますが、チーム内では b が最初に表示されます。カプセルとカードの両方で b が最初に表示されます。
        model.update(inputs(queued("a", 1, 0)), 0)
        val v = shown(model.update(inputs(queued("a", 1, 0), queued("b", 0, 1)), 10))
        assertEquals("b", v.runKey)
        val rows = model.cardRuns()
        assertEquals(listOf("b", "a"), rows.map { it.run.key })
        assertEquals(0 to 1, rows[0].ahead to rows[0].behind)
        assertEquals(1 to 0, rows[1].ahead to rows[1].behind)
    }

    @Test fun barCountsWhoIsBehindInTheRealQueue() {
        val v = shown(model.update(inputs(queued("a", 0, 2, Takeover.Stage.HandedOver), queued("b", 1, 1)), 0))
        assertEquals("このあと、あと 2 件が待っています", v.detail)
    }

    @Test fun afterHandBackTheNextOneNeedsYouAtOnce() {
        model.update(inputs(queued("a", 0, 1, Takeover.Stage.HandedOver), queued("b", 1, 0)), 0)
        // a それを渡して、それを続けます: b キューの先頭に移動し、すぐに「need you」になります (文の置換を待たずにスロットルします)
        val v = shown(model.update(inputs(run("a", running("接着做")), queued("b", 0, 0)), 100))
        assertEquals(CapsuleKind.NeedTakeover to "b", v.kind to v.runKey)
        assertEquals(0, model.cardRuns().first { it.run.key == "b" }.ahead)
    }

    // ---------- 半透明 ----------

    @Test fun ballFadesAfterIdleButNeverWhenNeedingYouOrExpanded() {
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Running, 0, pill = false, idleFor = CapsuleFade.IDLE_MS - 1))
        assertEquals(CapsuleFade.BALL, CapsuleFade.alpha(CapsuleKind.Running, 0, pill = false, idleFor = CapsuleFade.IDLE_MS))
        assertEquals(CapsuleFade.BALL, CapsuleFade.alpha(CapsuleKind.Done, 0, pill = false, idleFor = 60_000))
        // が進行中なので、対処する必要があります。その他のアイテムの中には、対処が必要なアイテムがいくつかあります。 「あなたがそれを扱う」もの：軽くありません。
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Done, 0, pill = true, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.NeedConfirm, 1, pill = false, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Running, 1, pill = false, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.HandedOver, 0, pill = false, idleFor = 60_000))
    }

    // ---------- 画面の読み取り ----------

    @Test fun ballDescribesItsStatus() {
        assertEquals("Mika が作業中: 在搜牙膏 · 共 2 件、タップで詳細", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Running, "在搜牙膏 · 共 2 件", count = 2)))
        assertEquals("許可が必要: 送信、タップして対応", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.NeedConfirm, "許可が必要: 送信")))
        assertEquals("Mika: 保留中: 待機中、タップで詳細", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Held, "保留中: 待機中")))
        assertEquals("Mika 办好了 · 点开看结果", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Done, "办好了 · 点开看结果")))
        assertEquals(CapsuleCopy.IDLE, CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Idle, "")))
    }
}
