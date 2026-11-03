package com.wangzan101.mika.capsule

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performTouchInput
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleCardContentTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = object : CardActions {
        override fun collapse() { calls += "collapse" }
        override fun stop(runKey: String) { calls += "stop $runKey" }
        override fun takeOver(runKey: String) { calls += "takeOver $runKey" }
        override fun resume(runKey: String) { calls += "resume $runKey" }
        override fun answer(runKey: String, text: String) { calls += "answer $runKey $text" }
        override fun approve(runKey: String, requestKey: Int) { calls += "approve $runKey $requestKey" }
        override fun decline(runKey: String, requestKey: Int) { calls += "decline $runKey $requestKey" }
        override fun openMika(conversationId: String) { calls += "open $conversationId" }
        override fun zoom(on: Boolean) { calls += "zoom $on" }
        override fun openInput() { calls += "openInput" }
        override fun draft(text: String) { calls += "draft $text" }
        override fun send() { calls += "send" }
        override fun freshChat(fresh: Boolean) { calls += "fresh $fresh" }
        override fun loadEarlier() { calls += "earlier" }
        override fun captureScreen() { calls += "capture" }
        override fun dropScreen() { calls += "drop" }
    }

    private fun run(key: String, state: TaskState, kind: CapsuleKind, line: String = "", startedAt: Long? = null, ahead: Int = 0) =
        CardRun(CapsuleRun(key, "c-$key", state, goal = "买两支牙膏"), kind, line, startedAt, ahead)

    private fun detail(r: CardRun) = CardView.Detail(r, CapsuleCards.content(r))

    private fun show(card: CardView, preview: Bitmap? = null, zoomed: Boolean = false, thread: ChatThread? = null, input: CardInputUi = CardInputUi()) {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                androidx.compose.runtime.CompositionLocalProvider(com.wangzan101.mika.ui.LocalScreenShots provides shots) {
                    CapsuleCard(card, preview, zoomed, actions, { if (it == null) null else "0:23" }, thread = thread, height = 420.dp, input = input)
                }
            }
        }
    }

    /** 偽画面ストレージ: s1 は設定ページの画面、s2 はスクリーンショットを許可しないページです。 */
    private val at = java.time.LocalDateTime.of(2026, 10, 28, 19, 42).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val shots = com.wangzan101.mika.ui.ScreenShots { _, id ->
        when (id) {
            "s1" -> com.wangzan101.mika.ui.ShownScreen(com.wangzan101.mika.core.userscreen.UserScreen("s1", at, "com.android.settings", "设置"),
                Bitmap.createBitmap(90, 200, Bitmap.Config.ARGB_8888).asImageBitmap())
            "s2" -> com.wangzan101.mika.ui.ShownScreen(com.wangzan101.mika.core.userscreen.UserScreen("s2", at, "com.example.bank", "银行", blocked = true), null)
            else -> null
        }
    }

    private fun idleThread(vararg userTexts: String) = CapsuleThreads.build(
        userTexts.mapIndexed { i, t -> PastTurn(com.wangzan101.mika.core.agent.Turn(t, "收到", "succeeded", runId = "r$i")) },
        CapsuleThreads.PAGE, emptyList(),
    )

    @Test fun threadShowsScreenMessageWithTagAndWords() {
        show(CardView.Chat("c1", title = "t"), thread = idleThread("[[screen:s1]]", "[[screen:s1]]\n第三条有没有坑?", "[[screen:s2]]"))
        val tag = "你此刻的屏幕 · 设置 · 19:42"
        // カードが最新の文で止まります。目の前に2番目の画面(追記あり)とスクリーンショット禁止の画面があり、1番目の画面は上にありまだ排出されていません。
        compose.waitUntil(5_000) { compose.onAllNodesWithText(tag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithTag("screen-thumb", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("第三条有没有坑?", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(com.wangzan101.mika.ui.ScreenCopy.BLOCKED, useUnmergedTree = true).assertIsDisplayed()
        // 画面のある文章では番号行が単語として表示されません。
        compose.onAllNodesWithText("[[screen:", substring = true, useUnmergedTree = true).assertCountEquals(0)
        // 最初の文までスクロールアップ: 画面のみが送信され、追記バブルはありません
        compose.onNodeWithTag("capsule-thread").performScrollToIndex(0)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(tag, useUnmergedTree = true)[0].assertIsDisplayed()
        compose.onAllNodesWithText("[[screen:", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun cameraButtonLeftOfInputCapturesScreen() {
        show(CardView.Chat("c1", title = "t"), thread = idleThread())
        compose.onNodeWithTag("capsule-camera").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("capture"), calls) }
        val camera = compose.onNodeWithTag("capsule-camera").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("capsule-input").fetchSemanticsNode().boundsInRoot
        assertTrue("スクリーンショットボタンを入力欄の左に配置する", camera.right <= field.left)
    }

    @Test fun attachmentChipShowsScreenAndCanBeSentOrRemoved() {
        val screen = com.wangzan101.mika.core.userscreen.UserScreen("", at, "com.android.settings", "设置")
        val pending = PendingScreen(com.wangzan101.mika.core.userscreen.CapturedScreen(byteArrayOf(1), screen),
            Bitmap.createBitmap(90, 200, Bitmap.Config.ARGB_8888).asImageBitmap())
        show(CardView.Chat("c1", title = "t"), thread = idleThread(), input = CardInputUi(screen = pending))
        compose.onNodeWithTag("capsule-attachment").assertIsDisplayed()
        compose.onNodeWithText(com.wangzan101.mika.ui.ScreenCopy.TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("设置 · 19:42", useUnmergedTree = true).assertIsDisplayed()
        // はスクリーンショットを撮るだけで、書かずに直接送信できます。
        compose.onNodeWithTag("capsule-send").performClick()
        compose.onNodeWithTag("capsule-attachment-remove").performClick()
        compose.runOnIdle { assertEquals(listOf("send", "drop"), calls) }
    }

    @Test fun blockedChipSaysNotCaptured() {
        val screen = com.wangzan101.mika.core.userscreen.UserScreen("", at, "com.example.bank", "银行", blocked = true)
        show(CardView.Chat("c1", title = "t"), thread = idleThread(),
            input = CardInputUi(screen = PendingScreen(com.wangzan101.mika.core.userscreen.CapturedScreen(null, screen), null)))
        compose.onNodeWithText(com.wangzan101.mika.ui.ScreenCopy.BLOCKED, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun chat(r: CardRun) = CardView.Chat(r.run.conversationId, r, CapsuleCards.content(r))

    /** [n] ラウンドの質問と回答。各ラウンドの回答は [lines] 行です。 */
    private fun history(n: Int, lines: Int = 1) = CapsuleThreads.build(
        (1..n).map { i -> PastTurn(com.wangzan101.mika.core.agent.Turn("问题 $i", (1..lines).joinToString("\n") { "回答 $i 第 $it 行" }, "succeeded", runId = "r$i")) },
        CapsuleThreads.PAGE, emptyList(),
    )

    @Test fun runningShowsStepsElapsedStopAndLink() {
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜牙膏", startedAt = 0L)))
        compose.onNodeWithText("买两支牙膏").assertIsDisplayed()
        compose.onNodeWithText("在干活 · 0:23").assertIsDisplayed()
        compose.onNodeWithText("在搜牙膏").assertIsDisplayed()
        // は入力ボックス (黒い停止キー) の右側で停止し、単一のテキスト ボタンではなくなりました。
        compose.onAllNodesWithText(CardCopy.STOP).assertCountEquals(0)
        compose.onNodeWithContentDescription(CardCopy.STOP).performClick()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).performClick()
        compose.onNodeWithContentDescription(CardCopy.COLLAPSE).performClick()
        assertEquals(listOf("stop a", "open c-a", "collapse"), calls)
    }

    @Test fun previewShowsAndZooms() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜")), preview = frame)
        compose.onNodeWithContentDescription(CardCopy.SCREEN).performClick()
        assertEquals(listOf("zoom true"), calls)
    }

    @Test fun zoomedClosesOnTap() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜")), preview = frame, zoomed = true)
        compose.onNodeWithTag("capsule-zoom").performClick()
        assertEquals(listOf("zoom false"), calls)
    }

    @Test fun noPreviewWithoutFrames() {
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜")))
        compose.onAllNodesWithTag("capsule-screen").assertCountEquals(0)
    }

    @Test fun takeoverHasTakeOverAndStop() {
        show(detail(run("a", TaskState.WaitingTakeover("payment", "请输入支付密码"), CapsuleKind.NeedTakeover, "需要你：付款")))
        compose.onNodeWithText("请输入支付密码").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.TAKE_OVER).performClick()
        compose.onNodeWithText(CardCopy.STOP).performClick()
        assertEquals(listOf("takeOver a", "stop a"), calls)
    }

    @Test fun queuedTakeoverCannotTakeOverYet() {
        show(detail(run("a", TaskState.WaitingTakeover("login", "请登录"), CapsuleKind.NeedTakeover, "需要你：登录", ahead = 1)))
        compose.onNodeWithText(CapsuleCards.QUEUED_AHEAD).assertIsDisplayed()
        compose.onAllNodesWithTag("capsule-card").assertCountEquals(1)
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertDoesNotExist()
    }

    // お金を稼ぐ操作のみ同意が必要です。カードには「今回のみ同意する」と「同意しない」のみが表示されます。
    @Test fun confirmOffersOnceOrDecline() {
        val r = run("a", TaskState.Idle, CapsuleKind.NeedConfirm)
        show(CardView.Detail(r, CardContent.Confirm("要你同意:下单", "金额:¥19.9", 9)))
        compose.onNodeWithText(ConfirmationCopy.NOTE).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.APPROVE_ONCE_ONLY).performClick()
        compose.onNodeWithText(CardCopy.DECLINE).performClick()
        assertEquals(listOf("approve a 9", "decline a 9"), calls)
    }

    @Test fun questionOptionsAnswer() {
        show(detail(run("a", TaskState.WaitingInput("要哪个？", listOf("大的", "小的")), CapsuleKind.NeedAnswer)))
        compose.onNodeWithText("小的").performClick()
        assertEquals(listOf("answer a 小的"), calls)
    }

    @Test fun questionInputOpensOnTap() {
        show(detail(run("a", TaskState.WaitingInput("几点出发？"), CapsuleKind.NeedAnswer)))
        compose.onNodeWithTag("capsule-input").performClick()
        assertEquals(listOf("openInput"), calls)
    }

    @Test fun openInputTypesAndSends() {
        var ui by mutableStateOf(CardInputUi(open = true))
        val typing = object : CardActions by actions {
            override fun draft(text: String) { ui = ui.copy(draft = text) }
        }
        val card = detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, "在搜"))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, typing, { null }, input = ui) }
        }
        compose.onNodeWithTag("capsule-field").performTextInput("换成薄荷味")
        compose.onNodeWithContentDescription(CardCopy.SEND).performClick()
        assertEquals("换成薄荷味", ui.draft)
        assertEquals(listOf("send"), calls)
    }

    @Test fun keptDraftShowsClosedAndTypingContinuesAtTheEnd() {
        var ui by mutableStateOf(CardInputUi(draft = "帮我把这段"))
        val typing = object : CardActions by actions {
            override fun draft(text: String) { ui = ui.copy(draft = text) }
        }
        val card = detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, "在搜"))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, typing, { null }, input = ui) }
        }
        compose.onNodeWithText("帮我把这段").assertIsDisplayed()
        ui = ui.copy(open = true)
        compose.onNodeWithTag("capsule-field").performTextInput("话")
        compose.runOnIdle { assertEquals("帮我把这段话", ui.draft) }
        // の言うとおり外から拾われ、次も最後でした。
        ui = ui.withSpoken("发给小王")
        compose.onNodeWithTag("capsule-field").performTextInput("吧")
        compose.runOnIdle { assertEquals("帮我把这段话发给小王吧", ui.draft) }
    }

    @Test fun noInputWhileTakeoverOrConfirm() {
        show(detail(run("a", TaskState.WaitingTakeover("login", "请登录"), CapsuleKind.NeedTakeover, "需要你：登录")))
        compose.onAllNodesWithTag("capsule-input").assertCountEquals(0)
    }

    @Test fun idleCardContinuesOrStartsFresh() {
        show(CardView.Chat("c1", title = "买牙膏"), thread = history(1))
        compose.onNodeWithText("接着「买牙膏」聊").assertIsDisplayed()
        compose.onNodeWithText("回答 1 第 1 行").assertIsDisplayed()
        compose.onNodeWithText(CapsuleCards.HINT_START).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.NEW_CHAT).performClick()
        assertEquals(listOf("fresh true"), calls)
    }

    @Test fun heldResumesOnlyWhenResumable() {
        show(detail(run("a", TaskState.Held(com.wangzan101.mika.core.agent.GateReason("x", "等你打开定位", resumable = true)), CapsuleKind.Held)))
        compose.onNodeWithText("等你打开定位").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.RESUME).performClick()
        assertEquals(listOf("resume a"), calls)
    }

    @Test fun finishedRunIsAChatCardWithItsConversation() {
        val r = run("a", TaskState.Succeeded("牙膏下单了"), CapsuleKind.Done)
        val past = listOf(PastTurn(com.wangzan101.mika.core.agent.Turn("早", "早上好", "succeeded", runId = "r0")))
        val thread = CapsuleThreads.build(past, CapsuleThreads.PAGE, listOf(r)) { listOf("购物") }
        show(chat(r), thread = thread)
        // 前回のやり取り、アプリを操作した行、回答の全文がすべてあります。段差や小さな画面はありません。
        // 会話のタイトルの最初の文は「朝」、その下の吹き出しも「朝」です: 両方の場所
        compose.onAllNodesWithText("早").assertCountEquals(2)
        compose.onNodeWithText("✓ 购物 · 办好了").assertIsDisplayed()
        compose.onNodeWithText("牙膏下单了").assertIsDisplayed()
        compose.onAllNodesWithTag("capsule-screen").assertCountEquals(0)
        compose.onNodeWithText(CapsuleCards.HINT_CONTINUE).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.BACK_TO_MIKA).performClick()
        assertEquals(listOf("open c-a"), calls)
    }

    @Test fun chatInputOpensOnTapAndStreamingChatHasStop() {
        val r = run("a", TaskState.Running(1, "n"), CapsuleKind.Running)
        show(chat(r), thread = CapsuleThreads.build(emptyList(), CapsuleThreads.PAGE, listOf(r)))
        compose.onNodeWithTag("capsule-input").performClick()
        compose.onNodeWithContentDescription(CardCopy.STOP).performClick()
        assertEquals(listOf("openInput", "stop a"), calls)
    }

    @Test fun chatQuestionKeepsItsOptionsPinned() {
        val r = run("a", TaskState.WaitingInput("要哪个？", listOf("大的", "小的")), CapsuleKind.NeedAnswer)
        show(chat(r), thread = CapsuleThreads.build(emptyList(), CapsuleThreads.PAGE, listOf(r)))
        compose.onNodeWithText("要哪个？").assertIsDisplayed()
        compose.onNodeWithText("小的").performClick()
        assertEquals(listOf("answer a 小的"), calls)
    }

    @Test fun chatOpensAtTheLatestQuestionAndScrollsUpForEarlier() {
        show(CardView.Chat("c1", title = "问题 1"), thread = history(25, lines = 3))
        // 開くときに最新の質問から読む
        compose.onNodeWithText("问题 25").assertIsDisplayed()
        compose.onNodeWithText("问题 20").assertIsNotDisplayed()
        repeat(30) { compose.onNodeWithTag("capsule-thread").performTouchInput { swipeDown() } }
        compose.onNodeWithText("问题 16").assertIsDisplayed()
        // 一番上までスクロール: さらに読み込んで進みます
        compose.runOnIdle { assertTrue("earlier" in calls) }
    }

    @Test fun longTakeoverScrollsWhileButtonsStay() {
        val body = (1..60).joinToString("\n") { "第 $it 行" }
        show(detail(run("a", TaskState.WaitingTakeover("login", body), CapsuleKind.NeedTakeover, "需要你：登录")))
        // 説明全体はテキスト ノードです。高さと位置で判断します。スクロール領域は全文よりも短く、テキストを上にスライドしてそれに沿って進みます。ボタンは移動しません。
        val area = compose.onNodeWithTag("capsule-scroll").fetchSemanticsNode()
        val text = compose.onNodeWithText("第 1 行", substring = true).fetchSemanticsNode()
        org.junit.Assert.assertTrue("capped: ${area.size.height} < ${text.size.height}", area.size.height < text.size.height)
        val before = text.positionInRoot.y
        repeat(3) { compose.onNodeWithTag("capsule-scroll").performTouchInput { swipeUp() } }
        val after = compose.onNodeWithText("第 1 行", substring = true).fetchSemanticsNode().positionInRoot.y
        org.junit.Assert.assertTrue("scrolled: $after < $before", after < before)
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).assertIsDisplayed()
    }

    @Test fun chatCardIsFixedWhateverTheContent() {
        var card by mutableStateOf<CardView>(chat(run("b", TaskState.Succeeded("好了"), CapsuleKind.Done)))
        var thread by mutableStateOf(history(1))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, actions, { null }, thread = thread, height = 420.dp) }
        }
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        // いつまで行ってもこの高さ 中に転がって
        thread = history(10, lines = 8)
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        // アイドル カードも同様
        card = CardView.Chat(null)
        thread = ChatThread(emptyList())
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
    }

    @Test fun taskCardWrapsItsContentAndTheChatCardDoesNot() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        val r = run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜")
        var card by mutableStateOf<CardView>(CardView.Detail(r, CardContent.Working(listOf("打开购物", "搜索牙膏", "选第一个"))))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, frame, false, actions, { null }, thread = history(1), height = 420.dp) }
        }
        val task = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        val screen = compose.onNodeWithTag("capsule-screen").fetchSemanticsNode().size.height
        val full = with(compose.density) { 420.dp.roundToPx() }
        // 小さな画面に 3 つのステップを追加します。ダイアログ カードよりも短く、下にスペースはなく、中央のセクションは少なくとも小さな画面全体をカバーする必要があります。
        assertTrue("wraps: $task < $full", task < full)
        assertTrue("fits the preview: $task > $screen", task > screen)
        compose.onNodeWithTag("capsule-screen").assertIsDisplayed()
        compose.onNodeWithText("选第一个").assertIsDisplayed()
        // がダイアログ カードに変更されました: 高さ固定
        card = chat(run("a", TaskState.Succeeded("好了"), CapsuleKind.Done))
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
    }

    @Test fun longTaskCardIsCappedAndScrollsWhileButtonsStay() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        val body = (1..60).joinToString("\n") { "第 $it 行" }
        show(detail(run("a", TaskState.WaitingTakeover("login", body), CapsuleKind.NeedTakeover, "需要你：登录")), preview = frame)
        // 説明がどれだけ長くても、タスク カードは最大でもダイアログ カードと同じ高さで、テキストは内部でスクロールし、ボタンとリンクは引き続き表示されます。
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        val area = compose.onNodeWithTag("capsule-scroll").fetchSemanticsNode()
        val text = compose.onNodeWithText("第 1 行", substring = true).fetchSemanticsNode()
        assertTrue("capped: ${area.size.height} < ${text.size.height}", area.size.height < text.size.height)
        compose.onNodeWithTag("capsule-screen").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).assertIsDisplayed()
    }

    /** は、リストに描画されていない (削除され、キューに入れられていない) 場合には表示されません。 */
    private fun offScreen(text: String) {
        val nodes = compose.onAllNodesWithText(text)
        if (nodes.fetchSemanticsNodes().isNotEmpty()) nodes[0].assertIsNotDisplayed()
    }

    private fun working(n: Int) = CardView.Detail(
        run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜"), CardContent.Working((1..n).map { "第 $it 步" }),
    )

    @Test fun manyStepsFitThePreviewHeightNewestAtTheBottom() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        var card by mutableStateOf<CardView>(working(30))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, frame, false, actions, { null }, height = 420.dp) }
        }
        val steps = compose.onNodeWithTag("capsule-steps").fetchSemanticsNode().size.height
        val screen = compose.onNodeWithTag("capsule-screen").fetchSemanticsNode().size.height
        val cardHeight = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        // 段数がいくつあっても、小さな画面と同じ高さになり、カードが伸びることはありません。
        assertTrue("steps fit the preview: $steps <= $screen", steps <= screen + 1)
        assertTrue("card not stretched: $cardHeight < ${with(compose.density) { 420.dp.roundToPx() }}", cardHeight < with(compose.density) { 420.dp.roundToPx() })
        compose.onNodeWithText("第 30 步").assertIsDisplayed()
        offScreen("第 1 步")
        // が一番下の新しいステップに到達します。それに従う
        card = working(31)
        compose.waitForIdle()
        compose.onNodeWithText("第 31 步").assertIsDisplayed()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(with(compose.density) { cardHeight.toDp() })
        // 最初のステップまでスクロールアップします
        repeat(10) { compose.onNodeWithTag("capsule-steps").performTouchInput { swipeDown() } }
        compose.onNodeWithText("第 1 步").assertIsDisplayed()
        // は、ページをめくるときに新しい一歩を踏み出しました。ページを一番下に戻さないでください。
        card = working(32)
        compose.waitForIdle()
        compose.onNodeWithText("第 1 步").assertIsDisplayed()
        offScreen("第 32 步")
    }

    @Test fun stepsWithoutPreviewAreCappedAndFewStaysCompact() {
        var card by mutableStateOf<CardView>(working(2))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, actions, { null }, height = 420.dp) }
        }
        val few = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        compose.onNodeWithText("第 1 步").assertIsDisplayed()
        card = working(40)
        compose.waitForIdle()
        val many = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        val full = with(compose.density) { 420.dp.roundToPx() }
        // 段数が少ない場合は高さは内容に応じます。段数が多すぎると、メイン画面のアスペクト比に基づいて計算された小画面の高さまでしか到達せず、最高レベルには到達しません。
        assertTrue("few is compact: $few < $many", few < many)
        assertTrue("many is capped: $many < $full", many < full)
        compose.onNodeWithText("第 40 步").assertIsDisplayed()
        offScreen("第 1 步")
    }

    @Test fun chatAnswerRendersMarkdownInsideTheCard() {
        val answer = "## 结果\n\n**牙膏**已下单\n\n| 商品 | 数量 | 单价 | 小计 | 备注 |\n|---|---|---|---|---|\n| 薄荷味牙膏特大号家庭装 | 2 | ¥9.95 | ¥19.9 | 明天送到家门口 |\n\n[订单详情](https://example.com/order)"
        val past = listOf(PastTurn(com.wangzan101.mika.core.agent.Turn("买牙膏", answer, "succeeded", runId = "r1")))
        show(CardView.Chat("c1", title = "买牙膏"), thread = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList()))
        // マークがそのまま露出しなくなりました
        compose.onNodeWithText("结果").assertExists()
        compose.onNodeWithText("牙膏已下单").assertExists()
        compose.onNodeWithText("薄荷味牙膏特大号家庭装").assertExists()
        compose.onAllNodesWithText("|", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("**", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("订单详情", substring = true).assertCountEquals(1)
        // セクション内で幅の広いテーブルを左右にスライドしても、カードの幅は広がりません。
        val card = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().boundsInRoot
        val thread = compose.onNodeWithTag("capsule-thread").fetchSemanticsNode().boundsInRoot
        assertTrue("thread inside card: $thread in $card", thread.right <= card.right && thread.left >= card.left)
    }

    @Test fun headerReportsItsHandleAndDraggingItTapsNothing() {
        var handle: android.graphics.Rect? = null
        val card = detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, "在搜"))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleCard(card, null, false, actions, { null }, height = 420.dp, onHandle = { handle = it })
            }
        }
        compose.runOnIdle { assertTrue("header bounds reported", handle?.isEmpty == false) }
        // タイトル バー上でのドラッグ (折りたたみボタンからのドラッグを含む): 折りたたみなし、ボタンのクリックなし
        compose.onNodeWithTag("capsule-header").performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription(CardCopy.COLLAPSE).performTouchInput { swipe(center, center + Offset(0f, 300f)) }
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }
}
