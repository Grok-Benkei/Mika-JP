package com.wangzan101.mika.takeover

import android.view.Display
import android.view.accessibility.AccessibilityEvent

/** は、その時点でユーザーがホーム画面で行ったこと、つまり操作の数とどのページで停止したかを返します (使用できない場合は null)。 */
data class HandoverActivity(val inputCount: Int, val stoppedAt: String?)

/**
 * の引き継ぎ中に、ホーム画面の [packageName] でのユーザーのクリック、入力、およびスクロールの数をカウントします。 1 回のスワイプで多くのスクロール イベントが連続して送信されます。
 * と [MERGE_MS] の間の距離は同じ時間としてカウントされます。同じ入力ボックス内のテキストの変更は同じです。連続するメッセージの最長の文字列は [BURST_CAP_MS] です。
 * 長時間のスワイプや入力を避けるため、超過部分は 1 回としてカウントされ、1 回だけ記録されます。ちなみに、ページタイトルが取得できない場合に使用できる、パッケージが最後に切り替えたアクティビティの短縮名をメモしておきます。
 * イベントは、アクセシビリティ コールバックが配置されているコルーチンから取得され、読み取り値は返されたコルーチンから取得されるため、ロックされています。
 */
class HandoverActivityCounter(val packageName: String) {
    private var count = 0
    private var scrollBurst: Burst? = null
    private var typingBurst: Burst? = null
    private var typingSource: Any? = null
    private var activity: String? = null

    val inputCount: Int @Synchronized get() = count
    val lastActivity: String? @Synchronized get() = activity

    @Synchronized
    fun onEvent(displayId: Int, eventPackage: CharSequence?, eventType: Int, className: CharSequence?, timeMs: Long, source: Any? = null) {
        if (displayId != Display.DEFAULT_DISPLAY || eventPackage?.toString() != packageName) return
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> count++
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // 入力ボックスが変更され、新しいものになりました
                if (typingSource != source) typingBurst = null
                typingSource = source
                typingBurst = advance(typingBurst, timeMs)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scrollBurst = advance(scrollBurst, timeMs)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }?.let { activity = it }
        }
    }

    private class Burst(val startAt: Long, var lastAt: Long)

    private fun advance(burst: Burst?, timeMs: Long): Burst {
        if (burst != null && timeMs - burst.lastAt < MERGE_MS && timeMs - burst.startAt < BURST_CAP_MS) {
            burst.lastAt = timeMs
            return burst
        }
        count++
        return Burst(timeMs, timeMs)
    }

    companion object {
        const val MERGE_MS = 1_000L
        const val BURST_CAP_MS = 5_000L
    }
}

/**
 * 「停止場所」: ウィンドウのタイトルは多くの場合、単なるアプリ名です。この場合、ページ (アクティビティ) の短縮名が使用されます。どちらも含まれていますが、統合される前は異なります。
 * タイトルが取得できない場合は、ページの略称を使用してください。
 */
fun stoppedAt(title: String?, appLabel: String?, activity: String?): String? {
    val t = title?.takeIf { it.isNotBlank() }
    if (t == null || t == appLabel) return activity ?: t
    return t
}
