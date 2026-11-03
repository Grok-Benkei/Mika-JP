package com.wangzan101.mika.about

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * ネットワーク プロキシを使用する場合、Mika 自身がダウンロードしたインストール パッケージは、システム インストーラーが使用する content:// アドレスに付与されます (システム ダウンロード サービスのアドレスは独自に付与されます)。
 * は更新パッケージ ディレクトリ (res/xml/update_files.xml を参照) のみをカバーしており、読み取り専用でエクスポートされません。インストール中に一時的に許可されます。
 */
class UpdateFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
