package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.RunEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 1 回の実行で測定されたモデル リクエストの合計数。それを実行し、独自のコルーチン コンテキストに配置します。 [metered] に関連付けられたモデルは、各リクエストの最後に追加されます。
 * したがって、メイン ループ外のすべてのバイパス呼び出し (意図の判断、検証、画像の表示、圧縮など) と失敗した再試行は、この実行名でカウントされます。
 * このコンテキスト (応答を渡した後の終了判定、メモリの検索) に含まれない呼び出しはカウントされず、トークン使用状況ページは引き続き記録されます。メモリ内にのみ追加され、マルチスレッドセーフです。
 */
class RunUsageTally : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunUsageTally>

    private val calls = AtomicInteger()
    private val unreported = AtomicInteger()
    private val input = AtomicLong()
    private val output = AtomicLong()
    private val cached = AtomicLong()

    /** はリクエストを記録します。 [usage] は null で、使用状況が報告されないか、呼び出しが失敗したことを示します。 この時点の */
    fun add(usage: Usage?) {
        calls.incrementAndGet()
        if (usage == null) {
            unreported.incrementAndGet()
            return
        }
        input.addAndGet(usage.inputTokens.toLong())
        output.addAndGet(usage.outputTokens.toLong())
        cached.addAndGet(usage.cachedInputTokens.toLong())
    }

    /** の合計が走行記録上1つの[RunEvent.ModelCalls]とみなされます。 */
    fun event(atMs: Long) = RunEvent.ModelCalls(atMs, calls.get(), unreported.get(), input.get(), output.get(), cached.get())
}
