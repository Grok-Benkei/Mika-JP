package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.tool.FakeScreen
import org.junit.Assert.assertEquals
import org.junit.Test

/** ユーザー自身の 1 文の単語: 会話ページとブラウザー ページのスペルに従って、今回入力した単語 ([UserWords.typed]) と記憶とスキルの引用 ([UserWords.said])。 */
class UserWordsTest {
    private val tools = toolDispatcher(FakeScreen(), web = FakeWebPort())
    private val mika = QuoteMessage.Quote(QuoteMessage.From.Mika, "你常用的收货地址是幸福路 8 号")
    private val own = QuoteMessage.Quote(QuoteMessage.From.You, "我家猫叫团子")
    private val pdf = listOf("/workspace/inbox/a.pdf" to "2 KB")

    private fun words(text: String) = UserWords.typed(text, tools) to UserWords.said(text, tools)

    // は、Mika の回答を引用しました。引用された段落はカウントされません。添付ファイル行もカウントされません。添付ファイルのみが含まれ、入力が行われていない場合は空になります。
    @Test fun aQuotedReplyIsNotTheUserWords() {
        assertEquals("照这个改" to "照这个改", words(AttachmentLines.compose(QuoteMessage.compose(mika, "照这个改"), pdf)))
        assertEquals("" to "", words(AttachmentLines.compose(QuoteMessage.compose(mika, ""), pdf)))
    }

    // は彼の発言を引用しました: 今回のみ入力し、暗記した引用文を引用された段落と一緒に読み、前に置きました
    @Test fun anOwnQuoteIsSaidButNotTyped() {
        assertEquals("这个记一下" to "我家猫叫团子\n这个记一下", words(AttachmentLines.compose(QuoteMessage.compose(own, "这个记一下"), pdf)))
        assertEquals("" to "我家猫叫团子", words(AttachmentLines.compose(QuoteMessage.compose(own, ""), pdf)))
    }

    // ブラウザ ページに関する質問: ページの説明は通常どおり削除する必要があります
    @Test fun thePageDescriptionIsNotTheUserWords() {
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        assertEquals("这一页的地址对不对" to "这一页的地址对不对", words(AttachedPage.compose(page, "这一页的地址对不对")))
    }

    // 引用や添付ファイルがない場合は、そのまま表示されます。 「>」で始まる北京語や、引用文の後ろにある「>」で始まる言葉は引用とみなされません。
    @Test fun plainWordsStayAsTheyAre() {
        assertEquals("查天气" to "查天气", words("查天气"))
        assertEquals("> 这句是我自己写的\n后面" to "> 这句是我自己写的\n后面", words("> 这句是我自己写的\n后面"))
        assertEquals("> 这句什么意思\n解释一下" to "> 这句什么意思\n解释一下", words(QuoteMessage.compose(mika, "> 这句什么意思\n解释一下")))
    }
}
