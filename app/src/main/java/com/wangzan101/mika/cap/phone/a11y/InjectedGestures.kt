package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 注入したジェスチャーが浮動レイヤーに触れないよう隔離します。カウンターと [isolate] はメインスレッド専用です。 */
internal class InjectedGestures(private val isolate: (Boolean) -> Unit) {
    private var depth = 0
    val isolated: Boolean get() = depth > 0

    suspend fun <T> passThrough(block: suspend () -> T): T {
        var entered = false
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                depth++
                // 呼出し元へ戻る途中のキャンセルにも備え、カウンターの変更直後から finally で復元します。
                entered = true
                if (depth == 1) isolate(true)
            }
            return withContext(NonCancellable) { block() }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            // メインスレッド待ちの間にキャンセルされた処理は、他のジェスチャーのカウンターを減らしません。
            if (entered) try {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    depth = (depth - 1).coerceAtLeast(0)
                    if (depth == 0) isolate(false)
                }
            } catch (restoration: Throwable) {
                // 復元の失敗で、キャンセル・ジェスチャー・ウィンドウ隔離の元の例外を隠しません。
                val original = failure
                if (original == null) throw restoration
                if (original !== restoration) original.addSuppressed(restoration)
            }
        }
    }
}

/** 全ウィンドウの更新を試してから最初の例外を投げ、残りを抑制例外として添付します。 */
internal fun <T> forEachGestureWindow(windows: List<T>, update: (T) -> Unit) {
    var failure: Throwable? = null
    for (window in windows) {
        try {
            update(window)
        } catch (t: Throwable) {
            val first = failure
            if (first == null) failure = t else if (first !== t) first.addSuppressed(t)
        }
    }
    failure?.let { throw it }
}
