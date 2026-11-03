package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleThreadTest {
    @get:Rule val texts = ChineseTexts()

    private fun turn(user: String, outcome: String, status: String = "succeeded", runId: String? = null, steers: List<String> = emptyList()) =
        Turn(user, outcome, status, runId = runId, steers = steers)

    private fun live(key: String, state: TaskState, kind: CapsuleKind, runId: String? = null, draft: String? = null, line: String = "") = CardRun(
        CapsuleRun(key, "c1", state, goal = "问题$key", runId = runId,
            exchange = Exchange("问题$key", emptyList(), ExchangeStatus.Working, live = true, draft = draft)),
        kind, line,
    )

    private fun texts(t: ChatThread) = t.items.map {
        when (it) {
            is ThreadItem.User -> "你:${it.text}"
            is ThreadItem.Note -> "注:${it.text}"
            is ThreadItem.Answer -> if (it.cut) "断:${it.text}" else "答:${it.text}"
        }
    }

    // ---------- 1ラウンドの並べ方 ----------

    @Test fun chatTurnIsBubbleAndFullAnswer() {
        // 答えをMarkdownのままにして、カードにフォーマットします
        val t = CapsuleThreads.build(listOf(PastTurn(turn("在吗", "# 在\n\n**有事**请说\n\n| 项 | 值 |\n|---|---|\n| a | 1 |\n"))), 10, emptyList())
        assertEquals(listOf("你:在吗", "答:# 在\n\n**有事**请说\n\n| 项 | 值 |\n|---|---|\n| a | 1 |"), texts(t))
        assertEquals("在吗", t.title)
    }

    @Test fun appTurnCollapsesToOneLineThenItsAnswer() {
        val past = listOf(
            PastTurn(turn("买牙膏", "下单了，共 ¥19.9", runId = "r1"), places = listOf("购物")),
            PastTurn(turn("查车票", "没有余票", status = "failed", runId = "r2"), places = listOf("12306", "浏览器", "地图")),
            PastTurn(turn("发消息", "已取消", status = "cancelled"), places = listOf("聊天")),
        )
        assertEquals(listOf(
            "你:买牙膏", "注:✓ 购物 · 完了", "答:下单了，共 ¥19.9",
            "你:查车票", "注:12306、浏览器 など · うまくいきませんでした", "答:没有余票",
            "你:发消息", "注:聊天 · 停止しました",
        ), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    @Test fun plainChatFailuresSayItAndSteersAreBubbles() {
        val past = listOf(
            PastTurn(turn("算一下", "模型没回", status = "failed")),
            PastTurn(turn("写首诗", "已取消", status = "cancelled", steers = listOf("短一点"))),
        )
        assertEquals(listOf("你:算一下", "注:うまくいきませんでした", "答:模型没回", "你:写首诗", "你:短一点", "注:停止しました"), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    // 停止ラウンド: 「停止」という行の下に説明があります。説明がまだ書かれている段階では、「これまでに行われたことを整理しています...」
    @Test fun stoppedTurnShowsItsSummaryUnderTheNote() {
        val past = listOf(
            PastTurn(turn("看设置", "停止前看完了网络、蓝牙;显示、声音还没看。", status = "cancelled", runId = "r1"), places = listOf("设置")),
            PastTurn(turn("写首诗", "已取消", status = "cancelled", runId = "r2")),
        )
        assertEquals(listOf(
            "你:看设置", "注:设置 · 停止しました", "答:停止前看完了网络、蓝牙;显示、声音还没看。",
            "你:写首诗", "注:停止しました", "注:ここまでの内容をまとめています…",
        ), texts(CapsuleThreads.build(past, 10, emptyList(), summarizing = setOf("r2"))))
        // は書き込めません。その行のみを残してください
        assertEquals(listOf("你:写首诗", "注:停止しました"), texts(CapsuleThreads.build(past.drop(1), 10, emptyList())))
        // まだセリフに入っていないものも同様です。
        val r = live("a", TaskState.Cancelled, CapsuleKind.Cancelled, runId = "r3")
        assertEquals(listOf("你:问题a", "注:停止しました", "注:ここまでの内容をまとめています…"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(r), summarizing = setOf("r3"))))
    }

    @Test fun oldEvidenceSectionIsDropped() {
        val t = CapsuleThreads.build(listOf(PastTurn(turn("几点开门", "九点开门\n依据：官网"))), 10, emptyList())
        assertEquals(listOf("你:几点开门", "答:九点开门"), texts(t))
    }

    @Test fun placesAreAppsAndSitesInOrder() {
        fun act(target: String?) = RunEvent.Activity(0, 1, "act", "tap", target)
        val acts = listOf(act("com.shop.app"), act("网站 example.com"), act("ウェブサイト new.example.com"),
            act("ウェブサイト example.com"), act("com.shop.app"), act("ls -l"), act(null), act("/workspace/a.txt"))
        assertEquals(listOf("買い物", "example.com", "new.example.com"), CapsuleThreads.places(acts) { if (it == "com.shop.app") "買い物" else it })
    }

    // ---------- 進行中のもの ----------

    @Test fun streamingAnswerGrowsAfterTheQuestion() {
        val r = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, runId = "ra", draft = "**今天**晴")
        val t = CapsuleThreads.build(listOf(PastTurn(turn("早", "早上好", runId = "r0"))), 10, listOf(r))
        assertEquals(listOf("你:早", "答:早上好", "你:问题a", "答:**今天**晴"), texts(t))
        assertTrue((t.items.last() as ThreadItem.Answer).streaming)
        // まだ言葉を書いていません: 今何をしているのか教えてください
        val thinking = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, line = "在想下一步")
        assertEquals(listOf("你:问题a", "注:在想下一步"), texts(CapsuleThreads.build(emptyList(), 10, listOf(thinking))))
    }

    @Test fun liveWaitsShowTheQuestionAndAppLine() {
        val ask = live("a", TaskState.WaitingInput("要大的还是小的？"), CapsuleKind.NeedAnswer)
        assertEquals(listOf("你:问题a", "答:要大的还是小的？"), texts(CapsuleThreads.build(emptyList(), 10, listOf(ask))))
        val inApp = live("b", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "在看")
        assertEquals(listOf("你:问题b", "注:购物 · 作業中", "答:在看"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(inApp)) { listOf("购物") }))
        val done = live("c", TaskState.Succeeded("好了"), CapsuleKind.Done)
        assertEquals(listOf("你:问题c", "注:✓ 购物 · 完了", "答:好了"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(done)) { listOf("购物") }))
    }

    @Test fun liveTailSkipsWhatIsAlreadyRecorded() {
        val recorded = live("a", TaskState.Succeeded("好了"), CapsuleKind.Done, runId = "r1")
        val notYet = live("b", TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed, runId = "r2")
        val queuedCancel = live("c", TaskState.Cancelled, CapsuleKind.Cancelled)
        val going = live("d", TaskState.Running(1, "n"), CapsuleKind.Running)
        val other = live("e", TaskState.Running(1, "n"), CapsuleKind.Running).let { it.copy(run = it.run.copy(conversationId = "c2")) }
        // カードの最後のカードが最前列にあります。前後の最初のものが上にあり、進行中のものが最後にあります。
        val tail = CapsuleThreads.liveTail(listOf(going, other, notYet, recorded, queuedCancel), "c1", setOf("r1"))
        assertEquals(listOf("b", "d"), tail.map { it.run.key })
    }

    @Test fun keysStayWhenTheLiveTurnGetsRecorded() {
        val r = live("a", TaskState.Succeeded("好了"), CapsuleKind.Done, runId = "r9")
        val before = CapsuleThreads.build(emptyList(), 10, listOf(r)).items.map { it.key }
        val after = CapsuleThreads.build(listOf(PastTurn(turn("问题a", "好了", runId = "r9"))), 10, emptyList()).items.map { it.key }
        assertEquals(before, after)
    }

    // ---------- ページングおよびオープン時の停止場所 ----------

    @Test fun latestTenTurnsFirstThenEarlier() {
        val past = (1..25).map { PastTurn(turn("问$it", "答$it", runId = "r$it")) }
        val first = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList())
        assertTrue(first.hasEarlier)
        assertEquals("你:问16", texts(first).first())
        assertEquals("问1", first.title)
        val more = CapsuleThreads.build(past, CapsuleThreads.PAGE * 2, emptyList())
        assertEquals("你:问6", texts(more).first())
        // 前に追加されたラウンドはすべて前にあります。元の項目のキーは変更されず、それに応じてリストは元の場所で停止します。
        assertEquals(first.items.map { it.key }, more.items.takeLast(first.items.size).map { it.key })
        val all = CapsuleThreads.build(past, CapsuleThreads.PAGE * 3, emptyList())
        assertTrue(!all.hasEarlier)
        assertEquals(50, all.items.size)
    }

    @Test fun anchorIsTheLatestQuestion() {
        val past = listOf(PastTurn(turn("一", "甲")), PastTurn(turn("二", "乙\n很长的回答")))
        val t = CapsuleThreads.build(past, 10, emptyList())
        assertEquals(2, t.anchor)
        assertEquals(ThreadItem.User("p1-u0", "二"), t.items[t.anchor])
        // 現在進行中の質問は最後にあります。そこから読み始めてください。
        val going = CapsuleThreads.build(past, 10, listOf(live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "在写")))
        assertEquals("你:问题a", texts(going)[going.anchor])
        assertEquals(0, ChatThread(emptyList()).anchor)
    }

    // 進行中の間投詞の後に元の文が続き、その下に宛先が書かれます（対話ページと同じ文）。最新の文章がオープニングの着地点です。
    @Test fun liveSteersFollowTheGoalWithWhereTheyWent() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "在写")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "改成后天", SteerState.Joined),
            SteerNote("s2", "顺便订午饭", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        val users = t.items.filterIsInstance<ThreadItem.User>()
        assertEquals(listOf("问题a", "改成后天", "顺便订午饭"), users.map { it.text })
        assertEquals(listOf(null, "実行中のタスクに追加しました", "了解です — 次のステップへ"), users.map { it.note })
        assertEquals("你:顺便订午饭", texts(t)[t.anchor])
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
    }

    @Test fun withdrawnSteersAreNotShown() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running)
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "已撤回的话", SteerState.Withdrawn),
            SteerNote("s2", "留下的话", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("问题a", "留下的话"), t.items.filterIsInstance<ThreadItem.User>().map { it.text })
    }

    // 中断によって中断された返信は、中断の前にランク付けされます (会話ページと同じ)。記録されたラウンドは進行中のラウンドと同じ行にランク付けされ、キーは繰り返されません。
    @Test fun interruptedReplySitsAboveItsSteer() {
        val past = listOf(PastTurn(Turn("查天气", "明天多云", "succeeded", runId = "r1",
            steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴,最高", ""))))
        assertEquals(listOf("你:查天气", "断:今天晴,最高", "你:改成查明天", "你:顺便看看后天", "答:明天多云"),
            texts(CapsuleThreads.build(past, 10, emptyList())))

        val base = live("a", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "明天")
        val run = base.copy(run = base.run.copy(steers = listOf(SteerNote("s1", "改成查明天", SteerState.Joined, interrupted = "今天晴,最高"))))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("你:问题a", "断:今天晴,最高", "你:改成查明天", "答:明天"), texts(t))
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertEquals("你:改成查明天", texts(t)[t.anchor])
    }

    // 中断前に終了した返信は、中断前に回答済みとしてリストされます。まだモデルに渡されていない割り込みは、回答が書かれた後にリストされます。
    @Test fun finishedReplyAndWaitingSteerOrder() {
        val past = listOf(PastTurn(Turn("查天气", "明天多云", "succeeded", runId = "r1",
            steers = listOf("改成查明天"), finishedReplies = listOf("今天晴,最高 25 度。"))))
        assertEquals(listOf("你:查天气", "答:今天晴,最高 25 度。", "你:改成查明天", "答:明天多云"),
            texts(CapsuleThreads.build(past, 10, emptyList())))

        val base = live("a", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "明天多")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "改成查明天", SteerState.Joined, finished = "今天晴"),
            SteerNote("s2", "简短点", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("你:问题a", "答:今天晴", "你:改成查明天", "答:明天多", "你:简短点"), texts(t))
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertEquals("你:简短点", texts(t)[t.anchor])
    }
}
