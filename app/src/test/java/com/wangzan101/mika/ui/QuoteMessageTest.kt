package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 会話ページの引用: 添付ファイルが添付されている場合、それらは互いに属します。タイトルに引用はありません。回答はプレーンテキストにコピーされます。どのように単語に書き込まれるかについては、コアの QuoteMessageTest を参照してください。 */
class QuoteMessageTest {
    @get:Rule val texts = ChineseTexts()

    private val mika = QuoteMessage.Quote(QuoteMessage.From.Mika, "明天上午有小雨\n下午转晴")

    // 引用して添付ファイルを追加: 添付ファイルは単語の後に接続され、分解するとそれぞれの場所に移動します。
    @Test fun quotesAndAttachmentsTogether() {
        val goal = ChatViewModel.withAttachments(QuoteMessage.compose(mika, "照这个改"), listOf("/workspace/inbox/a.pdf" to 2048L))
        val q = QuoteMessage.parse(goal)
        assertEquals(mika, q.quote)
        val a = AttachmentMessage.parse(q.words)
        assertEquals("照这个改", a.words)
        assertEquals(listOf("a.pdf"), a.files.map { it.name })
        // 引用と添付のみで、単語は含まれません
        val only = QuoteMessage.parse(ChatViewModel.withAttachments(QuoteMessage.compose(mika, ""), listOf("/workspace/inbox/a.pdf" to 2048L)))
        assertEquals(mika, only.quote)
        assertEquals("a.pdf", AttachmentMessage.plain(only.words))
    }

    // この文章はタイトルやお知らせ等に引用なしで呼ばれています。
    @Test fun titlesSkipTheQuote() {
        val goal = QuoteMessage.compose(mika, "那要带伞吗")
        assertEquals("那要带伞吗", ChatCopy.plainTitle(goal))
        assertEquals("那要带伞吗", ScreenCopy.plain(goal))
    }

    @Test fun answersCopyAsPlainText() {
        assertEquals(
            "上海今天晴,详见 天气\n\n要点\n\n• 早上 12 度\n• 下午 20 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\nval x = 1\n\n城市\t天气\n上海\t晴",
            MarkdownModel.toPlainText(
                "上海今天**晴**,详见 [天气](https://example.com)\n\n## 要点\n\n- 早上 12 度\n- 下午 *20* 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\n" +
                    "```kotlin\nval x = 1\n```\n\n---\n\n| 城市 | 天气 |\n| --- | --- |\n| 上海 | 晴 |",
            ),
        )
        assertEquals("", MarkdownModel.toPlainText(""))
    }
}
