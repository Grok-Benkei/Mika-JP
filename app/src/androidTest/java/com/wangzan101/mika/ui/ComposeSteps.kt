package com.wangzan101.mika.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage

/**
 * インターフェイス テストのいくつかのステップは、前のテストによって残されたステータスに依存しません。各テストのアクティビティは新たに開かれますが、前のテストのウィンドウ (アクティビティ、ポップアップ ウィンドウ) は
 * 終了するまでに時間がかかります。クリックする前に、実際にテスト ポイントが 1 つだけ画面に表示されるまで待ちます。クリックしたものは古いウィンドウに存在しないか、まだ配置されていません。
 */
internal fun ComposeContentTestRule.awaitOne(text: String, inDialog: Boolean = false, timeoutMs: Long = 5_000): SemanticsNodeInteraction {
    val matcher = if (inDialog) hasText(text) and hasAnyAncestor(isDialog()) else hasText(text)
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
    return onNode(matcher, useUnmergedTree = true)
}

/** ポップアップ ウィンドウのボタン: ポップアップ ウィンドウが表示されるまで待ってからクリックしてください。 */
internal fun ComposeContentTestRule.clickInDialog(text: String) {
    awaitOne(text, inDialog = true).performClick()
    waitForIdle()
}

/**
 * ダイアログ ページの入力ボックスに [text] と入力し、[送信] をクリックします。画面座標ポイントを押さずにボタンのクリック アクションを送信します。書き込み時に、入力ボックスがフォーカスを取得し、入力メソッドがポップアップし始めます。
 * 入力フィールドはキーボードとともに上に移動します。座標点をクリックすると、アニメーションの中央に位置が取られますが、多くの場合、入力フィールドの外に位置します。クリックして送信するわけではありません。
 * も「入力領域外をクリック」とみなされ、キーボードはしまい込まれ、単語はボックス内に残され、タスクは開始されませんでした。ボタン名は、アプリの現在のインターフェイス言語に基づいています。
 */
internal fun ComposeContentTestRule.typeAndSend(text: String) {
    onNode(hasSetTextAction()).performTextInput(text)
    onNodeWithContentDescription(appString(R.string.chat_send)).performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/** アプリの現在のインターフェイス言語に基づくテキスト。 */
internal fun appString(id: Int): String =
    AppLanguage.resources(InstrumentationRegistry.getInstrumentation().targetContext).getString(id)
