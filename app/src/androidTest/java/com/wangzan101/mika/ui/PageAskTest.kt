package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.browser.PageAskMode
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.capsule.CapsuleModel
import com.wangzan101.mika.capsule.CapsuleRun
import com.wangzan101.mika.capsule.ChatThread
import com.wangzan101.mika.capsule.ThreadItem
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ブラウザ ページの「ミカに聞く」セクションは次のようになります: 通常の入力バー → 作業中の半画面パネル (ライト エッジ、ステップ、ストップ) → ページでの作業後に小さなカードを受け取ります。
 * 一番上のメッセージは「続行」です。ミカが尋ねるとパネルが勝手に開きます。タスクを実行せず、ステータスを直接与えるだけです。
 */
@RunWith(AndroidJUnit4::class)
class PageAskTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var chat: PageChat
    private val model = CapsuleModel()

    private fun card(state: TaskState) = model.cardRunOf(CapsuleRun("run-1", "conv-1", state, goal = "挑一个评价最好的", onScreen = true))
    private val thread = ChatThread(listOf(ThreadItem.User("u0", "挑一个评价最好的"), ThreadItem.Answer("a0", "好的，我先看一下这一页。")))

    private fun start() {
        compose.setContent {
            MikaTheme {
                val scope = rememberCoroutineScope()
                chat = remember { PageChat(ctx, UserBrowser.of(ctx), scope) }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { PageAskOverlay(chat, site = "shop.example.com", pageTitle = "降噪耳机搜索结果") }
                    if (chat.mode == PageAskMode.Bar) PageAskBar(chat)
                }
            }
        }
    }

    @Test
    fun barThenPanelThenPausedCard() {
        start()
        compose.onNodeWithTag("page-ask-bar").assertIsDisplayed()
        compose.onNodeWithText(PageAskCopy.HINT).assertIsDisplayed()

        // ユーザーからの質問: パネルは開いており、ミカは作業中です。周囲に光があり、停止ボタンは入力ボックスの右側にあります。
        compose.runOnIdle {
            chat.show("conv-1", card(TaskState.Running(2, "", reason = "正在点「加入购物车」")), thread)
            chat.open = true
        }
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithTag("page-glow").assertExists()
        compose.onNodeWithText(PageAskCopy.FROM_PAGE, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("page-panel-open-chat").assertIsDisplayed()
        compose.onNodeWithText("好的，我先看一下这一页。").assertIsDisplayed()
        compose.onNodeWithTag("capsule-stop").assertIsDisplayed()

        // ページ上で行います。小さなカードを集め、上部に「続行」を置き、エッジを点灯させなくなります。
        compose.runOnIdle { chat.show("conv-1", card(TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))), thread) }
        compose.onNodeWithTag("page-card").assertIsDisplayed()
        compose.onNodeWithTag("page-paused-banner").assertIsDisplayed()
        compose.onNodeWithTag("page-card-resume").assertIsDisplayed()
        compose.onNodeWithTag("page-glow").assertDoesNotExist()
        compose.onNodeWithTag("page-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(PageAskMode.Card, chat.mode) }

        // カードをクリックしてパネルに戻ります
        compose.onNodeWithTag("page-card").performClick()
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithTag("page-panel-collapse").performClick()
        compose.onNodeWithTag("page-card").assertIsDisplayed()
    }

    @Test
    fun questionOpensThePanelAndEndedRunReturnsToTheBar() {
        start()
        compose.runOnIdle { chat.show("conv-1", card(TaskState.Running(1, "")), thread) }
        compose.onNodeWithTag("page-card").assertIsDisplayed()
        compose.runOnIdle { chat.show("conv-1", card(TaskState.WaitingInput("要哪个颜色？", listOf("午夜黑", "云白"))), thread) }
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithText("午夜黑").assertIsDisplayed()
        // 終了、片付けます: 入力バーに戻ります
        compose.runOnIdle {
            chat.show("conv-1", card(TaskState.Succeeded("加好了")), thread)
            chat.open = false
        }
        compose.onNodeWithTag("page-ask-bar").assertIsDisplayed()
    }

    /** サイドバーの 1 ページから始まる会話: タイトルの後ろに小さなラベル「Web ページ」があり、下の行が Web サイトです。他に会話はありません。 */
    @Test
    fun drawerRowShowsTheWebTagAndSite() {
        compose.setContent {
            MikaTheme {
                Column {
                    ConversationRowContent(DrawerConversation("a", "挑一个降噪耳机", "", false, false, true, site = "shop.example.com"))
                    ConversationRowContent(DrawerConversation("b", "明早的闹钟", "", false, false, true))
                }
            }
        }
        compose.onNodeWithTag("conversation-web").assertIsDisplayed()
        compose.onNodeWithText(PageAskCopy.WEB).assertIsDisplayed()
        compose.onNodeWithText("shop.example.com").assertIsDisplayed()
        compose.onNodeWithText("明早的闹钟").assertIsDisplayed()
        compose.onAllNodesWithTag("conversation-web").assertCountEquals(1)
    }
}
