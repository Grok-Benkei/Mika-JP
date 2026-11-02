package com.wangzan101.mika.core.run

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * この実行では、「ユーザーが中断しましたが、モデルに引き渡されていません」という信号 ([RunInbox] によって与えられる) が、実行中のコルーチン コンテキストにインストールされます。
 * 呼び出し (アクション前の純粋な待機、一時停止、間隔、画像モデルの表示を要求するだけ) は [waitOrSteer] によってラップされ、割り込みが到着するとすぐに早期終了します。
 * により、モデルはこの文をすぐに読み上げることができます。すでに開始されているアクションは影響を受けません。
 */
class SteerSignal internal constructor(internal val pending: StateFlow<Boolean>) : AbstractCoroutineContextElement(SteerSignal) {
    companion object Key : CoroutineContext.Key<SteerSignal>
}

/** モデルへのキャンセルされた呼び出しのエラー コード: 今回は何も行われず (待機が切断され、アクション前の一時停止が完了しませんでした)、ユーザーのニュースが最初に読み取られました。 */
const val SKIPPED_FOR_NEW_MESSAGE = "skipped_for_new_message"

/**
 * しばらく走った後に外しても何も失われません。 [block]: ユーザーの介入がすでに待機中であるか、進行中に到着した場合は、それを削除して null を返します。それ以外の場合は、その結果を返します。
 * が実行されていない場合 ([SteerSignal] がない場合)、通常どおり実行されます。
 */
suspend fun <T : Any> waitOrSteer(block: suspend () -> T): T? {
    val pending = coroutineContext[SteerSignal]?.pending ?: return block()
    if (pending.value) return null
    return coroutineScope {
        val work = async { block() }
        val steer = async { pending.first { it } }
        select<T?> {
            work.onAwait { steer.cancel(); it }
            steer.onAwait { work.cancel(); null }
        }
    }
}
