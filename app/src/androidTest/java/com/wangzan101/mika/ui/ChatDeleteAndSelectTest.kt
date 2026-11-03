package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * サイドバーの会話を長押しすると、その場でメニューがポップアップ表示されます (上部に固定、名前変更、削除、外側をクリックして閉じる): 削除するには、最初に確認して削除すると、リストがその場で更新されます。名前を変更すると、リストは新しい名前に置き換えられます。ピン留めした後、押しピンで上まで漕ぎます。
 * には、会話ページで回答を長押しすることでテキストをコピー/引用/選択する機能もあり、ユーザーの言葉がポップアップ表示されます。テキストを選択した後、それを選択すると、引用が次の文とともに送信されます。実機を必要としない作業環境。
 */
@RunWith(AndroidJUnit4::class)
class ChatDeleteAndSelectTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "chat-delete-test-" + UUID.randomUUID()))
    private val removed = mutableListOf<String>()
    private val started = mutableListOf<String>()

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { started += goal }
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private fun vm(impact: DeleteImpact = DeleteImpact(runningTask = false, schedules = 0)) = ChatViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
        deletionImpact = { impact },
        removeConversation = { removed += it; store.delete(it) },
        branchTitle = { it + " · 分支" },
    )

    private fun show(vm: ChatViewModel, drawerOpen: Boolean) {
        compose.setContent {
            ChatWithDrawer(
                vm, Prerequisites.Check(emptyList()), reduceMotion = true,
                conversations = { store.list() }, conversationChanges = store.changes, conversationTitle = store::title,
                onPlans = {}, onMemory = {}, onBrowser = {}, onSettings = {}, reopen = drawerOpen,
            ) { open ->
                ChatScreen(vm, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false), reduceMotion = true,
                    onOpenDrawer = open, openSettings = {})
            }
        }
        compose.waitForIdle()
    }

    @Test fun longPressARowAsksThenDeletes() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        store.append("b", Turn("订票", "好了", "succeeded"))
        val vm = vm(DeleteImpact(runningTask = true, schedules = 2))
        show(vm, drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-delete").performClick()
        compose.onNodeWithText(appString(R.string.drawer_delete_title)).assertExists()
        val body = compose.onNodeWithTag("delete-conversation-body").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(body, "天气" in body && "停止" in body && "2" in body)

        // キャンセル: 何も削除しません
        compose.onNodeWithText(appString(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertTrue(removed.isEmpty())

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-delete").performClick()
        compose.onNodeWithTag("confirm-delete-conversation").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { removed.isNotEmpty() }
        assertEquals(listOf("a"), removed)
        assertEquals(0, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        compose.onNodeWithText("订票").assertExists()
    }

    /** 長押しすると、メニューが表示されます。隆起した行 (同じタイトル) と、固定、名前変更、削除の 3 つの項目です。メニューを開いただけでは何も変わりません。 */
    @Test fun longPressShowsTheThreeActions() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu").assertExists()
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText(appString(R.string.pin)))
        compose.onNodeWithTag("drawer-menu-rename").assert(hasText(appString(R.string.drawer_rename)))
        compose.onNodeWithTag("drawer-menu-delete").assert(hasText(appString(R.string.confirm_delete)))
        compose.onNodeWithText(appString(R.string.unpin)).assertDoesNotExist()
        // メニューが開いているとき、次のコンテンツは読み取りにアクセスできず、浮き上がった行のみが残ります。
        assertEquals(1, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        assertEquals("天气", store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /** メニューの外側をクリックしてメニューを閉じると、何も変更されません。横の列の線はまだ残っています。 */
    @Test fun tappingOutsideClosesTheMenu() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assertExists()
        // 右下隅: メニューはその線の下の左側に接続されており、タッチできません。
        compose.onNodeWithTag("drawer-menu").performTouchInput { click(percentOffset(0.95f, 0.95f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer-menu-pin").assertDoesNotExist()
        compose.onNodeWithText("天气").assertExists()
        assertTrue(removed.isEmpty())
        assertEquals("天气", store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /** 名前の変更: 入力ボックス内の現在のタイトルが新しいタイトルに置き換えられ、保存されます。リストの名前はその場で変更され、保存されたリストも新しいものになります。 */
    @Test fun renameUpdatesTheRow() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-rename").performClick()
        compose.onNodeWithTag("rename-conversation-field").assert(hasText("天气"))
        compose.onNodeWithTag("rename-conversation-field").performTextReplacement("   ")
        compose.onNodeWithTag("confirm-rename-conversation").assertIsNotEnabled()
        compose.onNodeWithTag("rename-conversation-field").performTextReplacement("上海出行")
        compose.onNodeWithTag("confirm-rename-conversation").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("上海出行").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        assertEquals("上海出行", store.title("a"))
        // 後から入荷した良質なタイトルはブロックされません。
        assertTrue(!store.setTitle("a", "天气"))
    }

    /** ピン: 画鋲を使用して「トップ」セクションの上部に配置します。その後、ピンを解除して「最近」に戻ります。 */
    @Test fun pinMovesTheRowToTheTop() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        store.append("b", Turn("订票", "好了", "succeeded"))
        show(vm(), drawerOpen = true)
        compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).assertCountEquals(0)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText(appString(R.string.pin))).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(appString(R.string.pin)).assertExists()
        compose.onAllNodesWithTag("conversation-row")[0].assert(hasText("天气"))
        assertTrue(store.pinnedAt("a") != null)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText(appString(R.string.unpin))).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(appString(R.string.pin)).assertDoesNotExist()
        assertEquals(null, store.pinnedAt("a"))
    }

    // メッセージを長押ししてメニューをポップアップ表示します。 「テキストの選択」を選択すると、テキストがシステムの長押し選択に戻り、サイドバーが開きません。
    @Test fun answerAndUserTextAreSelectableFromTheMenu() {
        store.append("s", Turn("今天天气怎么样", "上海今天**晴**,详见 [天气](https://example.com)", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("s") }
        show(vm, drawerOpen = false)

        compose.onNode(hasText("上海今天", substring = true) and hasAnyAncestor(hasTestTag("selectable-answer"))).assertDoesNotExist()
        compose.onNodeWithText("上海今天", substring = true).performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-select").assert(hasText(appString(R.string.chat_select_text))).performClick()
        compose.waitForIdle()
        val answer = compose.onNode(hasText("上海今天", substring = true) and hasAnyAncestor(hasTestTag("selectable-answer")))
        answer.assertExists()
        answer.performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer").assertIsNotDisplayed()

        compose.onNodeWithText("今天天气怎么样").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-select").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("今天天气怎么样") and hasAnyAncestor(hasTestTag("selectable-user"))).assertExists()
    }

    // 回答を長押しして「引用」を選択します。入力ボックスの上に小さな引用バーが表示され、文を書いて送信します。引用はこの文を含むタスクに渡され、小さなバーは消えます。引用符はバブルの上に表示されます
    @Test fun longPressQuoteThenSend() {
        store.append("q", Turn("今天天气怎么样", "上海今天**晴**", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("q") }
        show(vm, drawerOpen = false)

        compose.onNodeWithText("上海今天晴").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-copy").assert(hasText(appString(R.string.chat_copy)))
        compose.onNodeWithTag("message-menu-quote").assert(hasText(appString(R.string.chat_quote))).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("message-menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("quote-chip").assertExists()

        compose.onNodeWithTag("composer-field").performTextReplacement("那明天呢")
        compose.onNodeWithContentDescription(appString(R.string.chat_send)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("> [Quoting your earlier reply]\n> 上海今天晴\n\n那明天呢"), started)
        compose.onNodeWithTag("quote-chip").assertDoesNotExist()
        compose.onNodeWithTag("sent-quote").assertExists()

        // × 参照を削除
        compose.runOnUiThread { vm.quote(QuoteMessage.From.You, "今天天气怎么样") }
        compose.onNodeWithContentDescription(appString(R.string.chat_quote_remove)).performClick()
        compose.onNodeWithTag("quote-chip").assertDoesNotExist()
    }

    // 完成した回答を長押しします「ここから分岐」: このラウンド (包括的) にコピーし、新しい会話に切り替えます。タイトルにはサフィックスが付き、元の会話は変更されません。
    @Test fun branchFromAnAnswerOpensTheCopiedChat() {
        store.append("s", Turn("今天天气怎么样", "上海今天晴", "succeeded"))
        store.append("s", Turn("那明天呢", "明天多云", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("s") }
        show(vm, drawerOpen = false)

        compose.onNodeWithText("明天多云").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-branch").assert(hasText(appString(R.string.chat_branch))).performClick()
        compose.waitUntil(10_000) { vm.ui.value.conversationId != "s" }
        val branch = vm.ui.value.conversationId
        assertNotNull(branch)
        assertEquals(listOf("今天天气怎么样", "那明天呢"), store.turns(branch!!).map { it.userText })
        assertEquals("今天天气怎么样 · 分支", store.title(branch))
        assertEquals(store.branchOrigin(branch)?.parentId, "s")
        assertEquals(listOf("今天天气怎么样", "那明天呢"), vm.ui.value.exchanges.map { it.goal })
        // 元の会話はまだ 2 ラウンドです
        assertEquals(2, store.turns("s").size)
    }

    // 自分が言ったことを長押しします。「ここからもう一度質問する」: このラウンドをコピーする前に、入力ボックスには元の単語があらかじめ入力されており、自動的には送信されません。
    @Test fun retryFromAUserMessagePrefillsTheNewChat() {
        store.append("s", Turn("今天天气怎么样", "上海今天晴", "succeeded"))
        store.append("s", Turn("那明天呢", "明天多云", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("s") }
        show(vm, drawerOpen = false)

        compose.onNodeWithText("那明天呢").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-branch-retry").assert(hasText(appString(R.string.chat_branch_retry))).performClick()
        compose.waitUntil(10_000) { vm.ui.value.conversationId != "s" }
        val branch = vm.ui.value.conversationId
        assertNotNull(branch)
        assertEquals(listOf("今天天气怎么样"), store.turns(branch!!).map { it.userText })
        assertEquals(false, store.branchOrigin(branch)?.afterTurn)
        compose.onNodeWithTag("composer-field").assertTextContains("那明天呢")
    }
}
