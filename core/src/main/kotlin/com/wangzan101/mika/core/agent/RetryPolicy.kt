package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.model.ModelError
import kotlin.random.Random

/**
 * モデル呼び出しで一時的な障害が発生した場合、再試行する必要がありますか? また、どれくらい待つ必要がありますか?一時的な障害: 電流制限 (429)、サーバー エラー (5xx)、接続失敗/接続切断/タイムアウト、
 * ストリーミング応答接続は、終了に達する前に切断されました (この時点では、応答は履歴に書き込まれておらず、ツールも実行されていないため、安全に再送信できます)。
 * 他のエラー (キー、クォータ、モデル名、コンテンツが拒否されました、その他の 4xx、解析できません) は、再試行しても同じになります。再試行は必要ありません。コンテキストが長すぎるため、圧縮されます。
 * Retry-After で電流制限が設定されている場合は、そのまま待機します (上限があります)。残りは指数バックオフとジッターに基づいており、各呼び出しは最大で [maxRetries] 回再試行されます。
 * タスクのリトライ待機時間の合計には上限があります [taskWaitBudgetMs] (デフォルトは 5 分、単なるセーフティネット): 使用後にエラーが発生した場合は、待たずにそのまま引き渡してください。
 * 純粋関数: ジッターは [jitter] (0..1) で与えられ、テストでは固定値に変更されます。
 */
class RetryPolicy(
    val maxRetries: Int = 5,
    private val baseMs: Long = 2_000,
    private val maxBackoffMs: Long = 30_000,
    private val retryAfterCapMs: Long = 30_000,
    /** タスク内のすべてのリトライ待機の合計の上限。 */
    val taskWaitBudgetMs: Long = 5 * 60_000,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    init {
        require(maxRetries >= 0 && baseMs > 0 && maxBackoffMs >= baseMs && retryAfterCapMs >= 0 && taskWaitBudgetMs >= 0)
    }

    /**
     * この呼び出しは [retriesSoFar] 回再試行され、[e] が検出されました。このタスクは再試行を待機しています。 [waitedInTaskMs]: 再試行するまで待機するミリ秒数を返します。
     * は再試行しないでください (一時的な失敗ではなく、回数が期限切れであり、今回待機するとタスクの待機制限を超えます) は null です。
     */
    fun delayFor(e: Throwable, retriesSoFar: Int, waitedInTaskMs: Long = 0): Long? {
        if (retriesSoFar >= maxRetries || !transient(e)) return null
        val wait = (e as? ModelError.RateLimited)?.retryAfterMs?.coerceIn(0, retryAfterCapMs) ?: run {
            val ceiling = minOf(maxBackoffMs, baseMs shl minOf(retriesSoFar, 20))
            // 半分固定、半分ランダム: 同時に問題が発生した複数のタスクが同時に衝突することはありません
            ceiling / 2 + (ceiling / 2 * jitter().coerceIn(0.0, 1.0)).toLong()
        }
        return wait.takeIf { waitedInTaskMs + it <= taskWaitBudgetMs }
    }

    companion object {
        /** 再試行なし: エラーが発生した場合は引き継ぎます (軽量モデルでエラーが発生した場合、このステップは直接メイン モデルに変更されます)。 */
        val NONE = RetryPolicy(maxRetries = 0)

        /** は一時的な失敗です。しばらくしてから同じリクエストを送信しても問題ない場合があります。 */
        fun transient(e: Throwable): Boolean = when (e) {
            is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut -> true
            is ModelError.Server -> e.status in 500..599
            // エージェントはしばらく起動せず、再起動しました。しばらくすると接続される可能性があります。アカウントとパスワードが間違っているため、再試行しても無駄です。
            is ModelError.Proxy -> e.failure == ProxyFailure.Unreachable
            else -> false
        }

        /** 操作記録内のこの障害の名前。 */
        fun label(e: Throwable): String = when (e) {
            is ModelError.RateLimited -> "rate_limited"
            is ModelError.Network -> "network"
            is ModelError.StreamCut -> "stream_cut"
            is ModelError.Server -> "server_${e.status}"
            is ModelError.Proxy -> if (e.failure == ProxyFailure.Unreachable) "proxy_unreachable" else "proxy_auth"
            else -> e::class.simpleName.orEmpty()
        }
    }
}
