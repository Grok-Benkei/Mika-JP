package com.wangzan101.mika.core.cap.web.cdp

import com.wangzan101.mika.core.uistate.DOUBLE_TAP_GAP_MS
import kotlinx.coroutines.delay

/**
 * ダブルクリック: 同じ点を 2 回押して持ち上げます。2 回目の clickCount は 2 で、ページはクリック、クリック、および dblclick (実際のダブルクリックと同じ) を受け取ります。
 * は、ブラウザがダブルクリックを認識する制限時間内に、[gapMs] で区切られています。
 */
suspend fun InputTrack.mouseDoubleClick(x: Double, y: Double, gapMs: Long = DOUBLE_TAP_GAP_MS) {
    mouse("mouseMoved", x, y, "none", 0, 0)
    mouse("mousePressed", x, y, "left", 1, 1)
    mouse("mouseReleased", x, y, "left", 0, 1)
    delay(gapMs)
    mouse("mousePressed", x, y, "left", 1, 2)
    mouse("mouseReleased", x, y, "left", 0, 2)
}
