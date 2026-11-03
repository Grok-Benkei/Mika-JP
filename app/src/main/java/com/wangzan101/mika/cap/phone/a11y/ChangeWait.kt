package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.display.FrameMath

/**
 * アクションの後、インターフェイスが安定するまで待ってから、変更があるかどうかを判断します。イベントを静かに見守るだけでは十分ではありません。応答が遅いページは、静かなときには描画を開始していない可能性があります。
 * は変更されていないと誤判定され、モデルが繰り返されます。
 *
 * - アクセシビリティ イベントは静かな [QUIET_MS] であり、コントロール ツリーが変更されました。変更されたとすぐに判断され、画像が停止するのを待ちません (ビデオとライブ ブロードキャストの画像は常に動いています)。
 * - 絵のみが変更されました (自己描画インターフェイス): 絵の途中のトランジションを結果とはみなさないように、前後 2 つのフレームが類似している場合にのみ、変更されたと判断できます。
 * - 「変化なし」と判断するには、イベントが静かで、画像が安定している必要があります。それまでは、[CAP_MS] までお待ちください。
 * - [CAP_MS] に到達すると、イベントがまだ発生しているか、画面がまだ動いているかに関係なく、現在の状態に基づいて結論が導き出されます。
 *   コントロールツリーや画面がアクション前と異なる場合は変更されています。ノンストップイベント (プログレスバー、タイマー) を含むページでは、アクションが上限を超えてスタックすることはありません。
 *
 * は、イベントが静かな場合に、残りの静かな時間だけスリープする必要があります。通常のページは、最後のイベントの後、約 [QUIET_MS] に戻ります。
 * クロック、待機、およびフレームのフェッチはすべて呼び出し元によって挿入されるため、JVM でのテストが簡単になります。
 */
internal object ChangeWait {
    const val POLL_MS = 150L
    const val QUIET_MS = 400L
    const val CAP_MS = 2_500L
    private const val MIN_SLEEP_MS = 10L

    /**
     * @param フレーム 現在のプレビュー フレームの明るさグリッド、画像がない場合は null (現時点ではコントロール ツリーとイベントのみが静かです)
     * @param QuietForMs 最後のインターフェイス イベントからどれくらい時間が経ちましたか?
     * @param TreeChanged コントロール ツリーのフィンガープリントがアクション前と異なるかどうか
     * @param beforeアクション前のフレーム輝度グリッド 現時点では、
     */
    suspend fun awaitStableChange(
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
        frame: () -> IntArray?,
        quietForMs: () -> Long,
        treeChanged: () -> Boolean,
        beforeFrame: IntArray?,
    ): Boolean {
        val deadline = now() + CAP_MS
        var prev = frame()
        var wait = POLL_MS
        while (true) {
            sleep(minOf(wait, (deadline - now()).coerceAtLeast(0)))
            val cur = frame()
            val quietFor = quietForMs()
            val quiet = quietFor >= QUIET_MS
            if (quiet && treeChanged()) return true
            val stable = if (prev == null || cur == null) prev == null && cur == null else !FrameMath.differs(prev, cur)
            val frameChanged = FrameMath.differs(beforeFrame, cur)
            if (quiet && stable && frameChanged) return true
            if (now() >= deadline) return frameChanged || treeChanged()
            wait = if (quiet) POLL_MS else minOf(POLL_MS, QUIET_MS - quietFor).coerceAtLeast(MIN_SLEEP_MS)
            prev = cur
        }
    }
}
