package com.wangzan101.mika.capsule

import com.wangzan101.mika.chat.CurrentChat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * アイドル アイコンをクリックしたときに、どの会話がカードに続きますか: ユーザーがアプリ内で参加している会話 ([CurrentChat])、記録がない場合は、最新の会話が続きます (発信者によって読み取られる最後の行)。
 * まだ話されていない新たに開かれたダイアログは、新しいダイアログ カードです。純粋なロジックで、ディスクの読み取りはありません。
 */
class CapsuleIdleTest {
    private val recent = CardView.Chat("c-old", title = "订票")

    @Test fun noRecordFallsBackToTheMostRecentConversation() {
        assertEquals(recent, CapsuleIdle.chat(null, recent))
        // ダイアログが 1 つもありません: 新しいダイアログ カード
        assertEquals(CardView.Chat(null), CapsuleIdle.chat(null, null))
    }

    @Test fun aBrandNewConversationIsAFreshCard() {
        val card = CapsuleIdle.chat(CurrentChat.Fresh, recent)
        assertEquals(CardView.Chat(null, fresh = true), card)
        // 番号なし: カードで話すときに発信者によって新しい番号が開かれます
        assertEquals(null, card.target)
    }

    @Test fun theConversationOnScreenWins() {
        val asked = mutableListOf<String>()
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { id -> asked += id; "查快递" }
        assertEquals(CardView.Chat("c-new", title = "查快递", current = true), card)
        // タイトルのみを確認してください: 一番下のタイトルは役に立ちません
        assertEquals(listOf("c-new"), asked)
        // これは「ユーザーが現在いるもの」であることに注意してください。カードはそれに応じて古いタスクが押されるのを防ぎます。
        assertEquals(true, card.current)
    }

    /** タイトルのない会話（番号は付けられていますが、まだ名前は付けられていません）は通常通り続きますが、カードの上部にはタイトルが書かれていません。 */
    @Test fun aConversationWithoutATitleIsStillContinued() {
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { null }
        assertEquals(CardView.Chat("c-new", title = null, current = true), card)
        assertEquals("c-new", card.target)
    }
}
