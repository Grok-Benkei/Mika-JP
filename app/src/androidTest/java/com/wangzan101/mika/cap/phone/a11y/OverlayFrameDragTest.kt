package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** フローティング レイヤー ウィンドウのルート上でドラッグ: ハンドルの内側を押しているときのみドラッグします。ドラッグすると、内側のボタンはキャンセルされ、カウントされません。ハンドルの外側をスライドすると、内側のコンテンツに渡されます。 */
@RunWith(AndroidJUnit4::class)
class OverlayFrameDragTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = mutableListOf<String>()
    private val drag = object : OverlayDrag {
        override fun start() { calls += "start" }
        override fun move(dx: Float, dy: Float) = Unit
        override fun end(dx: Float, dy: Float) { calls += "end" }
    }

    /** 内のコンテンツ (カードの折りたたみボタンや前後にロールする機能など): 押下、持ち上げ、およびキャンセルを受信したことに注意してください。 */
    private inner class Content : View(context) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> calls += "down"
                MotionEvent.ACTION_UP -> calls += "up"
                MotionEvent.ACTION_CANCEL -> calls += "cancel"
            }
            return true
        }
    }

    /** 400×800 ウィンドウにはコンテンツが満載です。 */
    private fun frame(handle: Rect?): OverlayFrame = OverlayFrame(context).apply {
        addView(Content(), FrameLayout.LayoutParams(400, 800))
        measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        layout(0, 0, 400, 800)
        drag = this@OverlayFrameDragTest.drag
        this.handle = handle
    }

    private fun OverlayFrame.gesture(x: Float, y: Float, dy: Float) {
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, yy: Float) = MotionEvent.obtain(t, t, action, x, yy, 0).also { dispatchTouchEvent(it); it.recycle() }
        ev(MotionEvent.ACTION_DOWN, y)
        ev(MotionEvent.ACTION_MOVE, y + dy / 2)
        ev(MotionEvent.ACTION_MOVE, y + dy)
        ev(MotionEvent.ACTION_UP, y + dy)
    }

    private val far get() = ViewConfiguration.get(context).scaledTouchSlop * 4f

    @Test fun dragInTheHandleMovesAndNeverClicks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 30f, far)
        }
        // 閾値を超えた場合：ドラッグ開始、キャンセル受付（クリックとみなしません）、ドラッグ終了までリフトのみ
        assertEquals(listOf("down", "start", "cancel", "end"), calls)
    }

    @Test fun swipeOutsideTheHandleIsLeftToTheContent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 400f, far)
        }
        // ドラッグはありません。一連のタッチ全体が内部のコンテンツに与えられます (スクロールなど)
        assertEquals(listOf("down", "up"), calls)
    }

    @Test fun tapInTheHandleStillClicks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 30f, 0f)
        }
        assertEquals(listOf("down", "up"), calls)
    }

    @Test fun emptyHandleMeansNoDrag() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect()).gesture(200f, 30f, far)
        }
        assertEquals(listOf("down", "up"), calls)
    }
}
