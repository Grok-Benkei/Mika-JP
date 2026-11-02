package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewTest {
    // は、バックグラウンドで一時タグ内の Web ページのみを読み取ります。Web ページの表示にはタグがなく、Web ページに含まれておらず、小さなウィンドウも表示されません (以前は白い背景でした)。
    @Test fun backgroundReadsAloneDoNotOpenThePreview() {
        val p = WebPreview.of(onWeb = true, hasTab = false, handedOver = false)
        assertEquals(WebPreview.NoPage, p)
        assertFalse(p.open)
    }

    // が表示されているページを開きました: 画面を再生します
    @Test fun aVisibleTabShowsItsFrames() {
        val p = WebPreview.of(onWeb = true, hasTab = true, handedOver = false)
        assertEquals(WebPreview.Frames, p)
        assertTrue(p.open)
    }

    // ページはユーザーの手中にあります。ページはまだ Web ページ上にあり、画面は表示されません。
    @Test fun handedOverPageStaysOpenWithoutFrames() {
        val p = WebPreview.of(onWeb = true, hasTab = true, handedOver = true)
        assertEquals(WebPreview.Hidden, p)
        assertTrue(p.open)
    }

    @Test fun notOnTheWebIsOff() {
        assertEquals(WebPreview.Off, WebPreview.of(onWeb = false, hasTab = true, handedOver = false))
        assertEquals(WebPreview.Off, WebPreview.of(onWeb = false, hasTab = false, handedOver = true))
    }
}
