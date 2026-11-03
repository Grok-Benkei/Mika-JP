package com.wangzan101.mika.core.resource

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * 生きて死ぬシステムリソース群（仮想画面、Web ページ表示など）：[acquire] でビルド、ビルド後その場で登録、登録と逆の順序で [close] を解放します。
 * 「構築完了後、発信者に返却して現場に記録する」の間にハングがあります。この時点でタスクはキャンセルされ、構築したものを配置したことを誰も覚えていません。工事と同じ区画で登記をすれば、そのようなズレは生じません。
 * はどのスレッドでも使用できます。各リソースは 1 回だけ配置され、[close] は繰り返し調整できます。
 */
class ResourceScope {
    private class Entry(val context: CoroutineContext, val release: () -> Unit)

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var closed = false

    /**
     * は [context] の [create] に調整され、作成後すぐに登録されます。最後に、[release] も [context] に調整されます。
     * のビルド後は、タスクがキャンセルされても (ハンドバック時にキャンセルがスローされます)、リソースはスコープに返されます。スコープが閉じられたときに作成されたものはすぐに破棄され、[IllegalStateException] がスローされます。
     */
    suspend fun <T> acquire(context: CoroutineContext, release: (T) -> Unit, create: suspend () -> T): T =
        withContext(context) {
            val made = create()
            val owned = synchronized(lock) { if (!closed) entries += Entry(context) { release(made) }; !closed }
            if (!owned) {
                release(made)
                throw IllegalStateException("resource scope already closed")
            }
            made
        }

    /** 登録とは逆の順序ですべてを解除します。 1 つをリリースしなくても他のものには影響せず、最初のエラーはリリース後にスローされます。キャンセルによる中断なし。 */
    suspend fun close() {
        val todo = synchronized(lock) {
            closed = true
            entries.asReversed().toList().also { entries.clear() }
        }
        var failure: Throwable? = null
        for (e in todo) {
            try {
                withContext(e.context + NonCancellable) { e.release() }
            } catch (t: Throwable) {
                failure?.addSuppressed(t) ?: run { failure = t }
            }
        }
        failure?.let { throw it }
    }
}
