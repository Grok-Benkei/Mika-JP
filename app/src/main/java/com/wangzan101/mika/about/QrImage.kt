package com.wangzan101.mika.about

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.wangzan101.mika.R

/** 作成者の WeChat QR コード (アプリに同梱されており、コードを変更する場合は res/drawable-nodpi/wechat_qr.jpg のみを変更します)。 */
object QrImage {
    val RES = R.drawable.wechat_qr
    private const val FILE_NAME = "Mika-author-wechat.jpg"

    /**
     * 写真/ミカをアルバムに保存しました。 MediaStore を通じて独自に新しく作成した画像を書き込むには、ストレージのアクセス許可は必要ありません (これは Android 10 以降に当てはまります。Mika は少なくとも Android 11 をサポートしています)。
     * は、書き込み前に保留中としてマークされます。他のアプリでは画像の半分を見ることができません。失敗した場合は削除してください。
     */
    fun saveToPictures(context: Context): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Mika")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
        return runCatching {
            resolver.openOutputStream(uri)!!.use { out -> context.resources.openRawResource(RES).use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
