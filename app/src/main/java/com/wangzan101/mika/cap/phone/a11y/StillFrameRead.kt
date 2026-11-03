package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.display.FrameMath

/**
 * 画面上のテキスト認識 (OCR) の前後の画面要件。自己描画インターフェイスは、アクセシビリティ イベントをほとんど送信しません。 「イベントが静かである」ということは、描画が完了したことを意味するものではありません。
 * 最初にページに入ってキーボードを上げたときにトランジションフレームを認識していたか、認識後に画面が切り替わっていたので破棄する必要がありました。
 *
 * - 認識する前に画面が安定するまで待ちます。前後の 2 つのフレームは似ています ([FrameMath.differs] は false)。最大でも [STILL_CAP_MS] まで待ちます。常に動いている部分（アニメーション化されたアバター、カーソル）がある場合は、それを認識します。
 * - ページ全体が変更されたことを認識します ([FrameMath.pageChanged]): 文字の位置が一致しなくなりました。新しいページを押して、最大 [ATTEMPTS] 回再認識します。ローカルな変更は文字の位置に影響を与えないので、そのまま使用してください。
 *
 * クロック、待機、およびフレームのフェッチはすべて呼び出し元によって挿入されるため、JVM でのテストが簡単になります。
 */
internal object StillFrameRead {
    const val POLL_MS = 120L
    const val STILL_CAP_MS = 1_200L
    const val ATTEMPTS = 2

    /** [recognize] は現在のフレームを認識します。 null (画像なし) の場合は、null を直接返します。ページ全体が変更されるたびに、null が返されます。 */
    suspend fun <T : Any> read(now: () -> Long, sleep: suspend (Long) -> Unit, frame: () -> IntArray?, recognize: suspend () -> T?): T? {
        repeat(ATTEMPTS) {
            awaitStill(now, sleep, frame)
            val before = frame()
            val result = recognize() ?: return null
            if (!FrameMath.pageChanged(before, frame())) return result
        }
        return null
    }

    /** 前後の 2 つのフレームが類似するまで待ちます。制限時間に達したときにまだ動いている場合は false を返します。画像がない場合は待ちません。 */
    suspend fun awaitStill(now: () -> Long, sleep: suspend (Long) -> Unit, frame: () -> IntArray?): Boolean {
        val deadline = now() + STILL_CAP_MS
        var prev = frame() ?: return true
        while (true) {
            sleep(minOf(POLL_MS, (deadline - now()).coerceAtLeast(0)))
            val cur = frame() ?: return true
            if (!FrameMath.differs(prev, cur)) return true
            if (now() >= deadline) return false
            prev = cur
        }
    }
}
