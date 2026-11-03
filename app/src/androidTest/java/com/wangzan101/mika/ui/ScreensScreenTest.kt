package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 「背景画面」ページ: 空の状態、1 行に 1 つの画面、クリックすると小さな画面が開きます (偽のデータのみが使用され、実際の画面は作成されません)。 */
@RunWith(AndroidJUnit4::class)
class ScreensScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(rows: List<ScreenRow>) = compose.setContent {
        MikaTheme {
            ScreensContent(rows, onBack = { calls += "back" }, onOpenConversation = { calls += "open $it" })
        }
    }

    private fun row(displayId: Int, key: String = "k$displayId") =
        ScreenRow(key, "conv-$key", "打开设置看一下电池", displayId, TaskState.Running(1, "点了一下"), foreground = "设置")

    @Test fun emptyPageSaysNoTaskIsRunning() {
        show(emptyList())
        compose.onNodeWithText(ScreensCopy.EMPTY).assertIsDisplayed()
    }

    /** タスクごとに 1 枚のカード: 何をしているか、どのアプリを使用しているか、ステータスがすべて表示されます。その行をクリックすると、その行が属する会話に戻ります。 */
    @Test fun oneCardPerTaskOpensItsConversation() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithText(appString(R.string.screens_using_app, "设置")).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.screens_no_app)).assertIsDisplayed()
        compose.onAllNodesWithText("打开设置看一下电池").assertCountEquals(2)
        compose.onAllNodesWithText(appString(R.string.task_running)).assertCountEquals(2)
        compose.onNodeWithText(appString(R.string.screens_using_app, "设置")).performClick()
        assertEquals(listOf("open conv-k12"), calls)
    }

    /** 画面未構築エリアには「画面を見る」の項目がありません。見る画面がなく、クリックしても反応しない項目もありません。 */
    @Test fun aScreenThatIsNotBuiltYetHasNoPreview() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithTag("screens-preview-queued").assertDoesNotExist()
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().assertIsDisplayed()
    }

    /** クリックして展開し、クリックして折りたたむと、それに応じてボタン上の文字が変わります。 */
    @Test fun previewToggles() {
        show(listOf(row(12)))
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().performClick()
        compose.onNodeWithText(ScreensCopy.HIDE_PREVIEW).assertExists()
        compose.onNodeWithTag("screens-preview-k12").performClick()
        compose.onNodeWithText(ScreensCopy.PREVIEW).assertExists()
        assertEquals(emptyList<String>(), calls)
    }
}
