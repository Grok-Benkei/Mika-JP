package com.wangzan101.mika.chat

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * ダイアログ ページの添付ファイルに使用される content:// アドレス (res/xml/attach_files.xml を参照) は外部にはエクスポートされず、使用時に一時的にのみ許可されます。
 * - カメラ/: システム カメラで撮影された写真がここに書き込まれ (写真撮影時にカメラ アプリにこのファイルの書き込みを許可します)、送信時に会話ワークスペースにコピーします。
 * - 表示/: 送信された写真またはファイルをクリックすると、コピーがワークスペースからコピーされ、アプリ (またはユーザーが選択した他のアプリ) の表示ページに渡されて、読み取り専用で開きます。
 * ワークスペース自体が開いていません。 Camera/ を除き、これは読み取り専用です。削除や挿入は許可されません。
 */
class AttachFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" && uri.pathSegments.firstOrNull() != CAMERA) throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** は、attach_files.xml 内の名前 (アドレスの最初のセグメント) と一致します。 */
        const val CAMERA = "camera"

        fun authority(packageName: String) = "$packageName.attachfiles"
    }
}
