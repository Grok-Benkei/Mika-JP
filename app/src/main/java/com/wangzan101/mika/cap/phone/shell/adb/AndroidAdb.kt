package com.wangzan101.mika.cap.phone.shell.adb

import android.content.Context
import android.net.ssl.SSLSockets
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.wangzan101.mika.core.adb.AdbIdentity
import com.wangzan101.mika.core.adb.AdbIdentitySource
import com.wangzan101.mika.core.adb.AdbKeyStore
import com.wangzan101.mika.core.adb.AdbTls
import com.wangzan101.mika.core.adb.KeyingMaterialExporter
import com.wangzan101.mika.core.adb.WrappedKeyFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException

/** Android 上の adb ID と TLS: キーはキーストア内の AES キーでラップされ、アプリのプライベート ディレクトリに配置されます。キーをエクスポートし、プラットフォーム TLS を使用します。 */
object AndroidAdb {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "mika_adb_key_wrap"
    private const val TAG = "MikaAdbKey"

    // デバイスはコンテキストなしでエクスポートします。 TLS 1.3 では、空のコンテキストはコンテキストなしの場合と同じ結果になります (RFC 8446 §7.5)
    val exporter = KeyingMaterialExporter { socket, label, length ->
        SSLSockets.exportKeyingMaterial(socket, label, ByteArray(0), length) ?: throw SSLException("keying material export failed")
    }

    @Volatile private var source: AdbIdentitySource? = null

    /** このプロセスにおける唯一の ID ソース: ペアリングと接続の両方がここから取得され、同じキーが取得されます。 */
    fun identities(context: Context): AdbIdentitySource = source ?: synchronized(this) {
        source ?: AdbIdentitySource(keyStore(context), { "mika@${Build.MODEL}" }) { msg, e -> Log.w(TAG, msg, e) }.also { source = it }
    }

    /** 接続に使用される ID。 [com.wangzan101.mika.core.adb.AdbKeyUnreadableException] は、保存されたキーを読み取れない場合にスローされます。 */
    fun identity(context: Context): AdbIdentity = identities(context).forConnect()

    /** ペアリングの ID: 保存されたキーを読み取れない場合は、新しいキーと置き換えます ([AdbIdentitySource.forPairing] を参照)。 */
    fun pairingIdentity(context: Context): AdbIdentity = identities(context).forPairing()

    fun tls(identity: AdbIdentity) = AdbTls(identity, exporter)

    /** このプロセスでキーとキャッシュを削除します (「ペアリング解除」のため)。デバイス上の認証レコードは、ユーザーがシステム設定で取り消す必要があります。 */
    fun forget(context: Context) = identities(context).forget()

    /** キー ファイル + それをラップするキーストア キー: クリアするときに両方を一緒に削除します。 */
    private fun keyStore(context: Context): AdbKeyStore {
        val file = WrappedKeyFile(File(context.applicationContext.noBackupFilesDir, "adb/adbkey.bin")) { wrappingKey() }
        return object : AdbKeyStore by file {
            override fun clear() {
                file.clear()
                runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS) }
            }
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
