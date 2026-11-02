package com.wangzan101.mika.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.vision.hamming
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** 実機での画像処理の動作 Bitmap;ハッシュ 2 つの項目は、エージェント仮想画面上のシステム設定を読み取り専用で参照します (組み込みのアシスタントとアクセシビリティ サービスが必要です)。 */
@RunWith(AndroidJUnit4::class)
class BitmapImageOpsTest {
    private val ops = BitmapImageOps
    private var agent: DeviceSupport.Agent? = null

    @After
    fun tearDown() {
        agent?.release()
    }

    private fun newAgent() = DeviceSupport.newAgent().also { agent = it }

    private fun png(w: Int, h: Int, color: (x: Int, y: Int) -> Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, color(x, y))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bmp.recycle() }
    }

    private fun DeviceSupport.Agent.snapUntil(test: (ScreenSnapshot) -> Boolean): ScreenSnapshot = runBlocking {
        val end = System.currentTimeMillis() + 5_000
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.elements.map { it.name }}", test(s))
        s
    }

    /** 画像が安定した後 (トランジションアニメーションが終了した後) 1 フレームを撮影します。 */
    private fun DeviceSupport.Agent.settledFrame(): ByteArray {
        Thread.sleep(1_000)
        val png = runBlocking { screen.screenshotPng() }
        assertNotNull("no frame from the agent display", png)
        return png!!
    }

    private fun DeviceSupport.Agent.openSettingsHome(): ScreenSnapshot {
        val r = runBlocking { screen.openApp(SETTINGS) }
        assertTrue("openApp: $r", r is ActionResult.Ok)
        return snapUntil { s -> s.elements.any { it.name.startsWith("WLAN") && it.clickable } }
    }

    @Test
    fun aHashChangesAfterNavigation() {
        val a = newAgent()
        val home = a.openSettingsHome()
        val homeHash = ops.aHash(a.settledFrame())
        val wlanEntry = home.elements.first { it.clickable && it.name.startsWith("WLAN") }
        val r = runBlocking { a.screen.tapRef(wlanEntry.ref, home.snapshotId) }
        assertTrue("tap: $r", r is ActionResult.Ok)
        a.snapUntil { s -> s.elements.any { it.role == "Switch" } }
        val d = hamming(homeHash, ops.aHash(a.settledFrame()))
        assertTrue("home vs WLAN distance $d", d > 8)
    }

    @Test
    fun drawFrameDrawsRed() {
        val img = png(200, 100) { _, _ -> Color.WHITE }
        val out = ops.drawFrame(img, intArrayOf(250, 250, 749, 749))
        val bmp = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertEquals(200 to 100, bmp.width to bmp.height)
        // フレーム [50,25] ～ [149,74] は 3 ピクセル拡張され、4 辺すべてが 4 ピクセル幅で、拡張されたフレーム内に描画されます: x 47..50 / 149..152、y 22..25 / 74..77
        assertEquals(Color.RED, bmp.getPixel(48, 50))
        assertEquals(Color.RED, bmp.getPixel(100, 23))
        assertEquals(Color.RED, bmp.getPixel(47, 50))
        assertEquals(Color.WHITE, bmp.getPixel(46, 50))
        assertEquals(Color.RED, bmp.getPixel(149, 50))
        assertEquals(Color.RED, bmp.getPixel(152, 50))
        assertEquals(Color.WHITE, bmp.getPixel(153, 50))
        assertEquals(Color.WHITE, bmp.getPixel(148, 50))
        assertEquals(Color.RED, bmp.getPixel(100, 74))
        assertEquals(Color.RED, bmp.getPixel(100, 77))
        assertEquals(Color.WHITE, bmp.getPixel(100, 78))
        assertEquals(Color.WHITE, bmp.getPixel(100, 73))
        assertEquals(Color.WHITE, bmp.getPixel(100, 50))
        assertEquals(Color.WHITE, bmp.getPixel(10, 10))
        bmp.recycle()
    }

    @Test
    fun toJpegShrinks() {
        // 写真のような画像: 滑らかな明暗とノイズ。通常のグラデーション PNG は JPEG よりも小さく圧縮されるため、JPEG と比較して縮小することはできません。
        val noise = java.util.Random(7)
        val img = png(1440, 3200) { x, y ->
            val n = noise.nextInt(48) - 24
            Color.rgb((x * 160 / 1440 + n + 40).coerceIn(0, 255), (y * 160 / 3200 + n + 60).coerceIn(0, 255), (120 + n).coerceIn(0, 255))
        }
        val jpeg = ops.toJpeg(img)
        val (w, h) = ops.size(jpeg)
        assertEquals(1280, h)
        assertEquals(576, w)
        assertTrue("jpeg ${jpeg.size} >= png ${img.size}", jpeg.size < img.size)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        assertEquals("image/jpeg", o.outMimeType)
    }
}
