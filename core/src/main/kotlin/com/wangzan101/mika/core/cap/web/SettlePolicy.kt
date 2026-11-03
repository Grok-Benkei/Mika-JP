package com.wangzan101.mika.core.cap.web

import kotlinx.coroutines.delay

/**
 * Web ページのアクション後の待機時間。ページが静かになり、以前と同じになるまで待ちます (古い上限は [domQuiet]、[domCapMs]、ページが変化し続ける場合は、上限に達するまで待機し、レポートしません)。
 * ネットワークは別の条件です。このアクションによって送信されたリクエストがまだ転送中 ([networkBusy]) の場合にのみ、後で待機すると、自動アクションの合計が最大 [netCapMs] になり始めます。
 * リクエストが終了したら、残りの時間は静かにページをもう一度見てください。ネットワークがすでにアイドル状態の場合、待機時間はネットワーク録画がない場合と同じくらい長くなります。
 * は、ネットワークが上限内で安定したかどうかを返します (ネットワークが待機していない場合は true)。
 */
object SettlePolicy {
    suspend fun settle(
        clock: () -> Long,
        domQuiet: suspend (capMs: Long) -> Boolean,
        networkBusy: () -> Boolean,
        domCapMs: Long,
        netCapMs: Long,
        pollMs: Long,
    ): Boolean {
        val deadline = clock() + netCapMs
        domQuiet(domCapMs)
        if (!networkBusy()) return true
        while (networkBusy()) {
            if (clock() >= deadline) return false
            delay(pollMs)
        }
        val left = deadline - clock()
        if (left > 0) domQuiet(minOf(left, domCapMs))
        return true
    }

    /**
     * ページを読み取る前にテキストが安定するまで待ちます (ページが静かであるだけではありません。DOM は静かで、遅延読み込みやバッチ レンダリング中に移動する可能性があります)。
     * は [intervalMs] ごとにテキスト (長さ、要約) を取得し、連続した [stableIntervals] 回は最後の時間として安定しているとみなされます。
     * にまだ単語が含まれていない場合 (ページはまだロード中である可能性があります)、連続して [emptyIntervals] 回かかります。 [capMs] がまだ安定していない場合は false が返されます。サンプルが取得できない場合は false が返されます。
     */
    suspend fun contentStable(
        clock: () -> Long,
        sample: suspend () -> Pair<Int, Int>?,
        intervalMs: Long,
        stableIntervals: Int,
        emptyIntervals: Int,
        capMs: Long,
    ): Boolean {
        val deadline = clock() + capMs
        var last = sample() ?: return false
        var same = 0
        while (true) {
            if (clock() + intervalMs > deadline) return false
            delay(intervalMs)
            val now = sample() ?: return false
            same = if (now == last) same + 1 else 0
            last = now
            if (same >= if (now.first == 0) emptyIntervals else stableIntervals) return true
        }
    }
}
