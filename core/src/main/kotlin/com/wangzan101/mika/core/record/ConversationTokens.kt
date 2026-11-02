package com.wangzan101.mika.core.record

/**
 * 会話の各ラウンドで使用されるトークンの数。各ラウンドの実行概要 ([RunMetrics]) に従って計算されます。口径は「プロセスの表示」の上部にある行と同じです。
 * ([RunMetrics.tokens]: メイン ループは毎回入力と出力を報告します)。
 *
 * [missing] は不完全なラウンドです。概要はまだ書かれておらず、非常に古いためクリアされています (最新の [RunMetricsStore.DEFAULT_KEEP] のコピーのみが残っています)。
 * 一部の返信は、サービス プロバイダーが使用状況を報告しなかったことを示す場合があります。この場合、インターフェイスには「少なくとも」と表示され、推定は行われません。
 */
data class ConversationTokens(val tokens: Long, val turns: Int, val missing: Int) {
    val partial: Boolean get() = missing > 0

    companion object {
        /** トークンのラウンド。要約がない場合、または何も報告されない場合 (すべて 0)、null になり、「0 トークン」はインターフェースに書き込まれません。 */
        fun turn(m: RunMetrics?): Long? = m?.tokens?.takeIf { it > 0 }

        /** [runIds] は、このダイアログで終了したラウンドです (同じものは 1 回だけカウントされます)。数値がない場合は null となり、インターフェイスは表示されません。 */
        fun of(runIds: List<String>, metrics: Map<String, RunMetrics>): ConversationTokens? {
            val ids = runIds.distinct()
            var sum = 0L
            var missing = 0
            for (id in ids) {
                val m = metrics[id]
                if (m == null) { missing++; continue }
                sum += m.tokens
                if (m.callsWithoutUsage > 0) missing++
            }
            if (sum <= 0) return null
            return ConversationTokens(sum, ids.size, missing)
        }
    }
}
