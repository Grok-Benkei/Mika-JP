package com.wangzan101.mika.web

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * Web ページ ファイル選択ボックスで使用される content:// アドレスにワークスペース ファイルを渡します。読み取り専用: 書き込み、削除、挿入は許可されません。
 * はサンドボックスの work/ ディレクトリのみをカバーし (res/xml/web_files.xml を参照)、外部にはエクスポートされません。
 */
class WebFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
