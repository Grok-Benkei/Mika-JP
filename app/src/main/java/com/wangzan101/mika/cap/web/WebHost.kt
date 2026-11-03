package com.wangzan101.mika.cap.web

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.wangzan101.mika.cap.web.cdp.DesktopProfile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer

/** Web ページ タグを掛ける場所: プライベート ディスプレイ上のプレゼンテーション、ディスプレイに直接追加されたウィンドウ、またはウィンドウなし (ページが完全にレンダリングされない可能性があります)。 */
enum class HostMode { Presentation, WindowManager, Detached }

/**
 * Web ページ タグのバックグラウンド ホスト: アプリによって構築されたプライベート仮想ディスプレイ (PUBLIC フラグなし、シェル ID アシスタントなし)、
 * 現在のラベルをホストしている WebView の上にウィンドウを配置します。したがって、ページはそれ自体が表示されていると見なされ、タイマー、アニメーション フレーム、遅延読み込みが通常どおり実行されます。
 * によって表示される画面は ImageReader に入り、スクリーンショットと視覚的な位置決めにのみ使用されます。
 *
 * 2 つのディスプレイ: モバイル バージョンのラベルは、メイン画面と同じサイズのディスプレイに掛けられます。デスクトップ バージョンのラベルは、デスクトップ ビューポートのサイズに応じて付けられます。各ブロックは使用するタグがある場合にのみ作成されます
 * (デスクトップ バージョンがデフォルトなので、ホストを構築するときに最初に構築します。構築できない場合は、携帯電話バージョンを使用してください)、ディスプレイを無駄に構築または解体しないでください。
 * ページは 1 CSS ピクセル対 1 ピクセルに従って描画され、スクリーンショットは鮮明で、座標は圧縮されていません。同時にハングできるのは 1 つのビュー ([width]、[height]) のみであり、スクリーンショットはすべて、それらが配置されているブロックをクリックします。
 *
 * の構築、アタッチ/デタッチ、クローズはすべてメイン スレッドで呼び出されます。
 */
class WebHost(private val context: Context, widthPx: Int, heightPx: Int, densityDpi: Int) : Closeable {
    /** プライベート ディスプレイとそのウィンドウとフレーム。 */
    private inner class Layer(val width: Int, val height: Int, dpi: Int, name: String) {
        val reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val virtualDisplay: VirtualDisplay
        val container: FrameLayout
        val mode: HostMode
        var presentation: Presentation? = null
        var windowManager: WindowManager? = null

        // pendingImage/copyScheduled/lastCopyAt/scratch はフレーム スレッド上でのみ移動します。フレームはframeLockで保護されており、スクリーンショットは任意のスレッドから読み取られます。
        val frameLock = Any()
        var frame: Bitmap? = null
        var pendingImage: Image? = null
        var copyScheduled = false
        var lastCopyAt = 0L
        var scratch: ByteBuffer? = null

        init {
            var vd: VirtualDisplay? = null
            var addedWindow: View? = null
            try {
                reader.setOnImageAvailableListener({ r -> onFrame(r) }, frameHandler)
                val dm = context.getSystemService(DisplayManager::class.java)
                // flags=0: プライベート表示、このアプリのみがコンテンツをアップロードできます
                vd = dm.createVirtualDisplay(name, width, height, dpi, reader.surface, 0)
                    ?: error("createVirtualDisplay returned null")
                val display = vd.display
                var chosen = HostMode.Detached
                var box = showPresentation(display)?.also { chosen = HostMode.Presentation }
                if (box == null) box = addWindow(display)?.also {
                    chosen = HostMode.WindowManager
                    addedWindow = it
                }
                if (box == null) {
                    Log.w(TAG, "web tabs run detached: pages may not fully render")
                    box = FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams(width, height) }
                }
                virtualDisplay = vd
                container = box
                mode = chosen
                // ビューがどのホストにハングアップしているかは、その親コンテナから取得できます (バージョンを変更すると、それに応じて表示が変更されます)。
                box.tag = this@WebHost
            } catch (e: Throwable) {
                reader.setOnImageAvailableListener(null, null)
                presentation?.let { p -> runCatching { p.dismiss() } }
                addedWindow?.let { w -> runCatching { windowManager?.removeViewImmediate(w) } }
                vd?.release()
                reader.close()
                throw e
            }
            Log.i(TAG, "web host $name on display ${virtualDisplay.display.displayId} mode=$mode ${width}x$height@$dpi")
        }

        val displayId: Int get() = virtualDisplay.display.displayId

        private fun showPresentation(display: Display): FrameLayout? {
            var p: Presentation? = null
            return try {
                p = Presentation(context, display)
                val c = FrameLayout(p.context)
                p.setContentView(c)
                p.show()
                presentation = p
                c
            } catch (e: Exception) {
                Log.w(TAG, "Presentation on private display failed, trying WindowManager", e)
                runCatching { p?.dismiss() }
                null
            }
        }

        private fun addWindow(display: Display): FrameLayout? = try {
            val wctx = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION, null)
            val wm = wctx.getSystemService(WindowManager::class.java)
            val c = FrameLayout(wctx)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION,
                0,
                PixelFormat.OPAQUE,
            )
            wm.addView(c, lp)
            windowManager = wm
            c
        } catch (e: Exception) {
            Log.w(TAG, "WindowManager on private display failed, falling back to detached WebView", e)
            null
        }

        fun copy(): Bitmap? = synchronized(frameLock) { frame?.let { Bitmap.createBitmap(it, 0, 0, width, height) } }

        /** はフレームスレッドが停止した後に調整されます。 */
        fun release() {
            reader.setOnImageAvailableListener(null, null)
            pendingImage?.close()
            pendingImage = null
            presentation?.let { p -> runCatching { p.dismiss() } }
            container.removeAllViews()
            windowManager?.let { wm -> runCatching { wm.removeViewImmediate(container) } }
            virtualDisplay.release()
            reader.close()
            synchronized(frameLock) {
                frame?.recycle()
                frame = null
            }
        }

        // フレームごとに最新のフレームのみが保持され、最大 100 ミリ秒ごとにコピーされます。静的ページでは新しいフレームが生成されなくなり、最後に押されたフレームもコピーされます。
        private fun onFrame(r: ImageReader) {
            try {
                val image = r.acquireLatestImage() ?: return
                pendingImage?.close()
                pendingImage = image
                if (!copyScheduled) {
                    copyScheduled = true
                    val wait = (FRAME_INTERVAL_MS - (SystemClock.uptimeMillis() - lastCopyAt)).coerceAtLeast(0)
                    frameHandler.postDelayed(::copyPending, wait)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "frame acquire failed", e)
            }
        }

        private fun copyPending() {
            copyScheduled = false
            val image = pendingImage ?: return
            pendingImage = null
            try {
                val plane = image.planes[0]
                // 行末に位置合わせパディングがある場合があります。ビットマップは行幅に応じて一度割り当てられ、繰り返し使用され、スクリーンショットを撮るときに表示幅に合わせてトリミングされます。
                val stridePx = plane.rowStride / plane.pixelStride
                synchronized(frameLock) {
                    val cur = frame
                    val target = if (cur != null && cur.width == stridePx && cur.height == image.height) cur
                    else Bitmap.createBitmap(stridePx, image.height, Bitmap.Config.ARGB_8888).also { cur?.recycle() }
                    val src = plane.buffer.apply { rewind() }
                    val needed = target.byteCount
                    if (src.remaining() >= needed) target.copyPixelsFromBuffer(src)
                    else {
                        // 最後の行にはパディングがない可能性があり、バッファーはビットマップ全体よりも短いです。最初に同じ長さのバッファーをパディングします。
                        val s = scratch?.takeIf { it.capacity() == needed } ?: ByteBuffer.allocateDirect(needed).also { scratch = it }
                        s.clear()
                        s.put(src)
                        s.rewind()
                        target.copyPixelsFromBuffer(s)
                    }
                    frame = target
                }
                lastCopyAt = SystemClock.uptimeMillis()
            } catch (e: Throwable) {
                Log.w(TAG, "frame copy failed", e)
            } finally {
                image.close()
            }
        }
    }

    private val phoneWidth = widthPx
    private val phoneHeight = heightPx
    private val phoneDpi = densityDpi
    private val frameThread: HandlerThread
    private val frameHandler: Handler
    private var phone: Layer? = null
    private var desktop: Layer? = null
    private var phoneFailed = false
    private var desktopFailed = false
    @Volatile private var active: Layer

    /** 現在表示されているディスプレイ (つまり、Web ページのビューポート) のピクセル サイズ。 */
    val width: Int get() = active.width
    val height: Int get() = active.height
    /** 現在表示されている作品のIDと吊り下げ方法。 */
    val displayId: Int get() = active.displayId
    val mode: HostMode get() = active.mode

    /** [close] 以降は、ビューをマウントできなくなります。 */
    @Volatile var closed: Boolean = false
        private set

    init {
        checkMain()
        frameThread = HandlerThread("mika-web-frames").apply { start() }
        frameHandler = Handler(frameThread.looper)
        // 新しいタブのデフォルトはデスクトップ バージョンです。最初にデスクトップ部分を構築します。構築できない場合は、モバイル部分を構築します。両方の部分が失敗すると、スローされます (呼び出し元はモバイル インターフェイスのみを提供します)。
        active = try {
            desktopLayer() ?: Layer(widthPx, heightPx, densityDpi, PHONE_NAME).also { phone = it }
        } catch (e: Throwable) {
            frameThread.quitSafely()
            frameThread.join()
            throw e
        }
    }

    /**
     * ラベルのビューをホスト ウィンドウに配置し、ハングしたビューを置き換えます。 [desktop] が true の場合、デスクトップ ビューポート ディスプレイにハングします (構築できない場合は、携帯電話にハングします)。
     * のデフォルトは、このラベルの現在のバージョンです。
     */
    fun attach(tab: WebTab, desktop: Boolean = tab.devtools.wantsDesktopHost(tab.view)) {
        val view = tab.view
        checkMain()
        if (closed) return
        val target = (if (desktop) desktopLayer() ?: phoneLayer() else phoneLayer() ?: desktopLayer()) ?: active
        if (view.parent === target.container) {
            active = target
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        for (l in listOfNotNull(phone, this.desktop)) if (l !== target) l.container.removeAllViews()
        target.container.removeAllViews()
        target.container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        active = target
        if (target.mode == HostMode.Detached) layoutDetached(target, view)
    }

    fun detach(view: WebView) {
        checkMain()
        for (l in listOfNotNull(phone, desktop)) if (view.parent === l.container) l.container.removeView(view)
    }

    private fun phoneLayer(): Layer? {
        phone?.let { return it }
        if (phoneFailed) return null
        return try {
            Layer(phoneWidth, phoneHeight, phoneDpi, PHONE_NAME).also { phone = it }
        } catch (e: Throwable) {
            Log.w(TAG, "phone-size web display failed; mobile pages use the desktop-size display", e)
            phoneFailed = true
            null
        }
    }

    private fun desktopLayer(): Layer? {
        desktop?.let { return it }
        if (desktopFailed) return null
        return try {
            Layer(DesktopProfile.HOST_WIDTH, DesktopProfile.HOST_HEIGHT, DesktopProfile.HOST_DPI, "mika-web-desktop").also { desktop = it }
        } catch (e: Throwable) {
            Log.w(TAG, "desktop-size web display failed; desktop pages use the phone-size display", e)
            desktopFailed = true
            null
        }
    }

    /** 小さなピクチャー・イン・ピクチャー・ウィンドウに使用される、ディスプレイ上の最新のフレーム (ディスプレイの幅に合わせて切り取られたコピー)。画像がまだない場合は null。 */
    fun latestFrame(): Bitmap? = active.copy()

    /**
     * ディスプレイ上の最新のフレーム (最大約 100 ミリ秒後)。フレームがまだ描画されていない場合は null。
     * PNG エンコードには数十ミリ秒かかるため、メインスレッドで調整しないでください。
     */
    fun latestFramePng(): ByteArray? {
        val bmp = active.copy() ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        // まずフレーム スレッドを停止すると、画像とリーダーを手に持って安全に閉じることができます
        phone?.reader?.setOnImageAvailableListener(null, null)
        desktop?.reader?.setOnImageAvailableListener(null, null)
        frameThread.quitSafely()
        frameThread.join()
        phone?.release()
        desktop?.release()
    }

    // ウィンドウがハングしていない場合、ビューをレイアウトする人はいません。手動で表示サイズを1回押します。
    private fun layoutDetached(l: Layer, view: View) {
        val w = View.MeasureSpec.makeMeasureSpec(l.width, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(l.height, View.MeasureSpec.EXACTLY)
        l.container.measure(w, h)
        l.container.layout(0, 0, l.width, l.height)
        view.requestLayout()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebHost must be used on the main thread" }

    companion object {
        private const val TAG = "MikaWebHost"
        private const val FRAME_INTERVAL_MS = 100L
        private const val PHONE_NAME = "mika-web"

        /**
         * ビューのラベルがモバイル版/デスクトップ版に変更されました。特定のホスト上でハングアップしている場合、対応するサイズの表示に変更されます。最初に誰がそれを立てたかは関係ありません。
         * がホスト上にない場合 (ユーザーの手にあり、テイクオーバー中にバックグラウンドにある場合)、移動しません。次回ハングアップするときは、バージョンに応じてハングアップします。メインスレッドによって呼び出されます。
         */
        fun followMode(tab: WebTab) {
            val host = (tab.view.parent as? View)?.tag as? WebHost ?: return
            host.attach(tab)
        }
    }
}
