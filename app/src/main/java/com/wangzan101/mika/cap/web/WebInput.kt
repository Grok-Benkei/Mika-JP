package com.wangzan101.mika.cap.web

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Web ページタグの実際の入力:dispatchTouchEvent 経由のタッチ、入力接続 commitText 経由のテキスト、dispatchKeyEvent 経由のキー、
 * ページは、手動操作と同様に、すべての信頼できるイベント (isTrusted) を受け取ります。代わりに、スクリプトのクリックやスクリプトの割り当てを使用しないでください。
 * は、システム入力メソッドを経由せずに、入力接続に WebView を直接使用します。キーボードはメイン画面で再生されません。座標は WebView ビューのピクセルです。
 */
class WebInput(private val tab: WebTab) {
    private val view get() = tab.view

    suspend fun tap(xPx: Int, yPx: Int) {
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, xPx.toFloat(), yPx.toFloat())
        delay(TAP_HOLD_MS)
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xPx.toFloat(), yPx.toFloat())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300) {
        val steps = max(2, (durationMs / MOVE_INTERVAL_MS).toInt())
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())
        for (i in 1..steps) {
            delay(durationMs / steps)
            val f = i.toFloat() / steps
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat())
    }

    /** 現在フォーカスされている Web ページの入力ボックスにテキスト全体を入力します。入力リンクを取得できない場合 (編集可能なフォーカスがない場合)、false が返されます。 */
    suspend fun commitText(text: String): Boolean = withConnection { it.commitText(text, 1) }

    suspend fun key(keyCode: Int) = key(keyCode, 0)

    /** すべて選択 (Ctrl+A) してから削除します。一部のエディターはキーの組み合わせを認識しないため、入力接続を使用してすべてを選択し、クリアします。 */
    suspend fun selectAllAndDelete() {
        key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key(KeyEvent.KEYCODE_DEL, 0)
    }

    /** 入力接続内のすべてを選択し、空の文字列を書き込みます: キーの組み合わせがクリアされない場合の退避。 */
    suspend fun selectAllAndCommitEmpty(): Boolean = withConnection {
        it.performContextMenuAction(android.R.id.selectAll)
        it.commitText("", 1)
    }

    /** [meta] は、キーの組み合わせに使用される KeyEvent (META_CTRL_ON タイプ) の変更された状態です。 */
    suspend fun key(keyCode: Int, meta: Int) {
        val down = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) {
            view.dispatchKeyEvent(keyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, meta))
            view.dispatchKeyEvent(keyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, meta))
        }
    }

    private fun keyEvent(down: Long, at: Long, action: Int, code: Int, meta: Int) =
        KeyEvent(down, at, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

    // は指を示します。ツール タイプが設定されていない場合、ページに表示される pointerType は空の文字列であり、実際の指はタッチです
    private suspend fun touch(down: Long, at: Long, action: Int, x: Float, y: Float) = withContext(Dispatchers.Main) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val e = MotionEvent.obtain(down, at, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            view.dispatchTouchEvent(e)
        } finally {
            e.recycle()
        }
    }

    // ブラウザ カーネルの入力接続には独自のスレッドがあります。ハンドラーがある場合はそのスレッドで調整され、そうでない場合はメイン スレッドで調整されます。
    private suspend fun withConnection(block: (InputConnection) -> Boolean): Boolean {
        val ic = withContext(Dispatchers.Main) {
            if (!view.hasFocus()) view.requestFocus()
            view.onCreateInputConnection(EditorInfo())
        } ?: return false
        val handler = ic.handler
        if (handler == null || handler.looper == Looper.getMainLooper()) return withContext(Dispatchers.Main) { block(ic) }
        return suspendCancellableCoroutine { c -> handler.post { c.resume(runCatching { block(ic) }.getOrDefault(false)) } }
    }

    private companion object {
        const val TAP_HOLD_MS = 50L
        const val MOVE_INTERVAL_MS = 16L
    }
}
