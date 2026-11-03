package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.normalize
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenText
import com.wangzan101.mika.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * ユーザーがホーム画面で現在見ている画面と、フロントエンド アプリおよび画面上の単語のスクリーンショットを撮ります ([UserScreenText] を参照: 最初にアクセシビリティ ツリーを読み、十分でない場合は画面上の単語を認識します)。
 * の画面にスクリーンショット防止ウィンドウがある場合 (スクリーンショットでエラーが報告されるか、スクリーンショットがほぼ真っ黒であるが画面上にアプリ ウィンドウがある)、画面は保持されませんが、画面は記録され、スクリーンショットは取得されません。
 * ミカ自身のフローティング レイヤーを最初に非表示にするかどうかは呼び出し元が決定します ([OverlayWindow.hiddenWhile] を参照)。フローティング レイヤーと入力メソッドは、ツリーを読み取るときにカウントされません。
 */
internal object MainScreenCapture {
    private const val TAG = "MikaScreenCapture"
    private const val JPEG_QUALITY = 88
    /** 文字を学習するには、この時間しか待つことができません。タイムアウトになった場合は、ツリー内の文字を使用してください。 */
    private const val OCR_WAIT_MS = 4_000L

    sealed interface Result {
        class Captured(val shot: CapturedScreen) : Result
        /** フロントデスクはミカ本人です: いいえ。 */
        data object Self : Result
        data object Unavailable : Result
    }

    /** はウィンドウ ルートの所有権をチェックするだけで、テキストはスキャンしません。所有者が不明な場合は共有を拒否します。 */
    fun sourcePackages(service: ScreenAccessibility): Set<String>? {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (windows.isEmpty()) return null
        val packages = windows.map { it.root?.packageName?.toString() }
        if (packages.any { it.isNullOrBlank() }) return null
        return packages.filterNotNull().toSet()
    }

    /** のスクリーンショット (呼び出し元がフローティング レイヤーを非表示にしました)。ツリーはスクリーンショットと同時に読み取られる必要があります。スクリーンショットを撮った直後に読み取ってください。 */
    suspend fun shoot(service: ScreenAccessibility): Pair<ScreenAccessibility.MainShot, ScreenRead?> {
        val shot = service.takeMainScreenshot()
        val read = runCatching { readTree(service) }.getOrNull()
        return shot to read
    }

    /** は、写真と木を切り取って 1 つの画面にまとめます (バックグラウンドで: エンコード、文字認識)。 [topInset]/[bottomInset] は、ステータス バーとナビゲーション バーが占めるピクセルです。 */
    suspend fun build(
        service: ScreenAccessibility, shot: ScreenAccessibility.MainShot, read: ScreenRead?,
        topInset: Int, bottomInset: Int, appLabel: (String) -> String,
    ): Result = withContext(Dispatchers.Default) {
        val pkg = read?.topPackage
        if (pkg == service.packageName) {
            (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
            return@withContext Result.Self
        }
        val now = System.currentTimeMillis()
        val label = pkg?.let(appLabel)
        when (shot) {
            is ScreenAccessibility.MainShot.Failed -> {
                Log.w(TAG, "screenshot failed: ${shot.code}")
                Result.Unavailable
            }
            ScreenAccessibility.MainShot.Secure -> Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
            is ScreenAccessibility.MainShot.Ok -> {
                val bmp = shot.bitmap
                try {
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                    // Android 12 より前では、スクリーンショット防止ウィンドウのスクリーンショットは黒でした。画面上にアプリ ウィンドウがあり、画面がほぼ完全に黒い場合、キャプチャできないとみなされ、黒い画像は送信されません。
                    if (read?.anyAppWindow != false && BitmapImageOps.isMostlyBlack(jpeg)) {
                        return@withContext Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
                    }
                    val snap = read?.let { snapshotOf(it, bmp.width, bmp.height) }
                    val ocr = if (snap != null && UserScreenText.needsOcr(snap)) recognize(bmp) else null
                    val (lines, fromOcr) = snap?.let {
                        UserScreenText.lines(it, ocr, top = normalize(topInset, bmp.height), bottom = normalize(bmp.height - bottomInset, bmp.height))
                    } ?: (emptyList<com.wangzan101.mika.core.userscreen.ScreenLine>() to false)
                    Result.Captured(CapturedScreen(jpeg, UserScreen("", now, pkg, label, lines = lines, fromOcr = fromOcr)))
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /** ホーム画面上のアプリに属する​​ウィンドウ（ステータスバー、ナビゲーションバー、インプットメソッド、フローティングレイヤーはカウントされません）が要素として読み込まれます。 */
    private fun readTree(service: ScreenAccessibility): ScreenRead {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val m = service.resources.displayMetrics
        val size = runCatching { service.overlays().area() }.getOrNull()
        return ScreenReader.read(windows, size?.width ?: m.widthPixels, size?.height ?: m.heightPixels)
    }

    private fun snapshotOf(r: ScreenRead, width: Int, height: Int) = ScreenSnapshot(
        snapshotId = "user-screen", packageName = r.topPackage, elements = r.entries.map { it.element },
        width = width, height = height, hasWindow = r.anyAppWindow, windows = r.windows,
    )

    private suspend fun recognize(bmp: Bitmap) = try {
        withTimeoutOrNull(OCR_WAIT_MS) { PhoneOcr.read(bmp.copy(Bitmap.Config.ARGB_8888, false)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "text recognition failed", e)
        null
    }
}
