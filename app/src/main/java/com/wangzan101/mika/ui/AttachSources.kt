package com.wangzan101.mika.ui

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** ダイアログ ページの添付ファイルについて読むべき事項: ファイルの名前とサイズ、カメラの配置ポイントを選択し、クリックして送信された添付ファイルを開きます。すべてのディスク読み取りはバックグラウンド スレッドで行われます。 */
object AttachFiles {
    /** 選択したファイルの名前とサイズ (コンテンツ URI 文字列)。名前が読み取れない場合はアドレスの最後のセグメントが使用され、名前が読み取れない場合はサイズは 0 になります。 */
    suspend fun describe(context: Context, uris: List<String>): List<PendingAttachment> = withContext(Dispatchers.IO) {
        uris.map { s ->
            val uri = Uri.parse(s)
            var name: String? = null
            var size = 0L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            PendingAttachment(s, name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "attachment", size)
        }
    }

    /** カメラで撮影した写真はどこに書き込まれますか: キャッシュ内のカメラの下の新しいファイル (1 日前の古い写真は都合よく削除されます) とシステム カメラのアドレス。 */
    fun newCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "attach/camera").apply { mkdirs() }
        val stale = System.currentTimeMillis() - 24 * 3600_000L
        dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        var f = File(dir, "IMG_$stamp.jpg")
        var n = 2
        while (f.exists()) f = File(dir, "IMG_${stamp}_${n++}.jpg")
        return FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), f)
    }

    /** 写真は失敗しました (キャンセルされました): 空のファイルを削除してください。 */
    fun discardCamera(context: Context, uri: Uri) {
        val name = uri.lastPathSegment ?: return
        File(context.cacheDir, "attach/camera/$name").takeIf { it.name == name }?.delete()
    }

    /** ワークスペース内のパス (`/workspace/inbox/…`) は、ワークスペースを基準とした相対パスに置き換えられます。ワークスペースにない場合は null になります。 */
    fun relative(path: String): String? =
        path.removePrefix(SandboxPaths.WORKSPACE + "/").takeIf { it != path && it.isNotEmpty() }

    /**
     * 送信された添付ファイルをクリックします。会話ワークスペースからコピーを (ファイル ゲート経由で) 表示/キャッシュにコピーします (最初に最後の添付ファイルをクリアします)。
     * はアプリ内の視聴ページに渡されます。表示できないタイプの場合は、システムで開くことができるアプリに渡されます。読み取れない場合は false を返します。
     */
    suspend fun openSent(context: Context, conversationId: String, path: String): Boolean {
        val rel = relative(path) ?: return false
        val name = path.substringAfterLast('/')
        val copy = withContext(Dispatchers.IO) {
            runCatching {
                val root = File(context.cacheDir, "attach/viewing")
                root.deleteRecursively()
                val f = File(root, UUID.randomUUID().toString().take(8) + "/" + name).apply { parentFile?.mkdirs() }
                val dirs = SandboxDirs(context)
                dirs.gate.openRead(dirs.workspace(conversationId), rel).use { g -> f.outputStream().use { g.input().copyTo(it) } }
                f
            }.getOrNull()
        } ?: return false
        FileViewer.open(context, FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), copy), name)
        return true
    }
}

/** 写真を直接読み取ることができますか: すべて、一部のみ (Android 14 以降)、与えられません。 */
enum class PhotoAccess { Full, Partial, None }

/** 添付パネルの「最近の写真」: メディア ライブラリ内の権限と写真。 */
object RecentPhotos {
    private const val PREFS = "attach"
    private const val ASKED = "photos_asked"

    /** 適用する権限: Android 14 以降と「一部の写真」、写真の読み取りには 13、外部ストレージの読み取りには 12 以前。 */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): PhotoAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.Full
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Partial
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.Full
            else -> PhotoAccess.None
        }
    }

    /** 初めてパネルを開けるときに一度質問してください。ユーザーが「写真へのアクセスを許可する」をクリックした場合にのみ、再度質問します。 */
    fun asked(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()

    /** 写真が最近メディア ライブラリに追加されました。新しい写真が最初です。読み取れない（権限がない）ものは空です。 */
    suspend fun query(context: Context, limit: Int = 120): List<PendingAttachment> = withContext(Dispatchers.IO) {
        val base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE)
        runCatching {
            context.contentResolver.query(base, projection, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(base, c.getLong(0)).toString()
                        add(PendingAttachment(uri, c.getString(1) ?: "photo.jpg", if (c.isNull(2)) 0L else c.getLong(2), photo = true))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }
}

/** 添付ファイル内の画像: まだ送信されていない (コンテンツ アドレス)、または送信済み (会話ワークスペース内のパス)。 */
sealed interface AttachImage {
    data class Picked(val uri: String) : AttachImage
    data class Sent(val conversationId: String, val path: String) : AttachImage
}

/** は、必要な辺の長さ (ピクセル) に応じて縮小画像を読み取ります。読み取れない場合は null になります。インターフェイス テストは偽のものに置き換えられます。 解決されている場合は、 */
fun interface AttachImages {
    suspend fun load(image: AttachImage, px: Int): ImageBitmap?

    /** が直接与えられます (リストが前後にスライドしてもプレースホルダーは点滅しません)。そうでない場合は null になります。 */
    fun cached(image: AttachImage, px: Int): ImageBitmap? = null
}

/** が与えられた場合は、それを使用して添付された画像を読み取ります (テスト)。そうでない場合は、携帯電話で画像を読むために使用します。 */
val LocalAttachImages = staticCompositionLocalOf<AttachImages?> { null }

/** 携帯電話に添付された画像: ズームアウトして解決します (画像全体を解決するわけではありません)。バックグラウンド スレッドで、解決された数値がバイト単位で記録されます。 */
private class StoredAttachImages(private val context: Context) : AttachImages {
    override fun cached(image: AttachImage, px: Int): ImageBitmap? = cache.get("$image@$px")

    override suspend fun load(image: AttachImage, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$image@$px"
        cache.get(key)?.let { return@withContext it }
        val bmp = runCatching {
            when (image) {
                is AttachImage.Picked -> context.contentResolver.loadThumbnail(Uri.parse(image.uri), Size(px, px), null).asImageBitmap()
                is AttachImage.Sent -> sentBytes(image)?.let { decodeSmall(it, px) }
            }
        }.getOrNull()
        bmp?.also { cache.put(key, it) }
    }

    private fun sentBytes(image: AttachImage.Sent): ByteArray? {
        val rel = AttachFiles.relative(image.path) ?: return null
        val dirs = SandboxDirs(context)
        return dirs.gate.openRead(dirs.workspace(image.conversationId), rel).use { f ->
            if (f.size > MAX_BYTES) null else f.read(0, f.size.toInt())
        }
    }

    companion object {
        /** 画像がこのサイズを超えるとわかりにくいです（プレースホルダーのみが表示されます）。 */
        const val MAX_BYTES = 40L shl 20
        val cache = object : LruCache<String, ImageBitmap>(24 shl 20) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
        }
    }
}

/** を、短辺が[px]（2の倍数）以上の縮小画像に分解し、EXIFに合わせて写真の向きを調整します。 */
private fun decodeSmall(bytes: ByteArray, px: Int): ImageBitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
        val short = minOf(info.size.width, info.size.height)
        var sample = 1
        while (short / (sample * 2) >= px) sample *= 2
        d.setTargetSampleSize(sample)
    }.asImageBitmap()

/** は添付された画像を (バックグラウンドで) 読み取ります。読み込まれていない場合、または読み取れない場合は null です。 */
@Composable
fun rememberAttachImage(image: AttachImage, px: Int): ImageBitmap? {
    val context = LocalContext.current
    val given = LocalAttachImages.current
    val images = remember(given, context) { given ?: StoredAttachImages(context.applicationContext) }
    val bmp by produceState(images.cached(image, px), image, px) {
        value = images.cached(image, px)
        if (value == null) value = runCatching { images.load(image, px) }.getOrNull()
    }
    return bmp
}
