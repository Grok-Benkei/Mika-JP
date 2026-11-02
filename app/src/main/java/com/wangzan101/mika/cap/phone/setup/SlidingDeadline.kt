package com.wangzan101.mika.cap.phone.setup

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * を押し戻す時間制限: 最後の [extend] から始まる [lengthMs]。 [PairingCoordinator] ペアリングにかかる​​時間を判断するために使用します。
 * ユーザーがペアリング プロセスに戻るたびに ([ワイヤレス デバッグに進む] をクリックして、ペアリング ステップで停止したガイド ページから離れる)、それ以降、計算が再計算されます。
 */
internal class SlidingDeadline(private val lengthMs: Long, private val now: () -> Long) {
    @Volatile private var until = 0L

    fun extend() {
        until = now() + lengthMs
    }

    /** は [job] が完了するまで待機します。待機中に制限時間を遅らせることができます。完了した場合は true、制限時間が先に来た場合は false です。 */
    suspend fun await(job: Job): Boolean {
        while (true) {
            val left = until - now()
            if (left <= 0) return false
            if (withTimeoutOrNull(left) { job.join() } != null) return true
        }
    }
}
