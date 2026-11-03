package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.cap.web.WebProxy
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyAddress
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 設定 › ネットワーク プロキシ、`http://[ユーザー名:パスワード@]ホスト:ポート` の形式。空の文字列は使用せず、直接接続することを意味します。
 * アドレスにはアカウントのパスワードが含まれる場合があるため、[KeyCipher] によって暗号化され、サービス プロバイダーのキーと同様に別のファイルに保存されます。クリア テキストの基本設定は入力されず、ログにも書き込まれません。
 *
 * 使用者: ミカ自身の接続 (モデル調整、列モデル、トークン交換のためのアカウント ログイン、ダウンロード更新、内蔵ブラウザ) は、[NetProxy] 経由でローカル エリア ネットワーク アドレスに直接接続されます。 ネットワークが許可されている場合、コンピューティング環境内の
 * ファイルとコマンドは、環境変数を通じてそれを取得できます (`AndroidSandbox` を参照)。
 *
 * は以前はコンテナーにのみ使用されていましたが、`container-proxy.bin` (別のキー) があります。初めて読み取るときに [legacyCipher] を使用して抽出し、新しいファイルとして保存し、古いファイルを削除します。
 */
class NetProxyStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val legacyCipher: KeyCipher? = null,
    /** 新しい値を保存した後に調整します (確立された接続を新しい設定に従って再接続します)。 */
    private val onChange: () -> Unit = {},
) {
    private val file = File(dir, FILE)
    private val legacy = File(dir, LEGACY_FILE)
    @Volatile private var cached: String? = null

    /** 既存のエージェント。存在しない場合、またはロックを解除できない場合 (マシンが変更されたか、キーが紛失した場合)、空の文字列になります。 */
    @Synchronized fun get(): String {
        cached?.let { return it }
        migrate()
        val stored = runCatching { if (file.isFile) String(cipher.decrypt(file.readBytes()), Charsets.UTF_8) else "" }.getOrDefault("")
        return stored.also { cached = it }
    }

    /** [proxy] を保存 (両端の空白を削除);ファイルが空の場合は削除します。発信者は最初に [valid] を確認しました。 */
    fun set(proxy: String) {
        synchronized(this) {
            val v = proxy.trim()
            require(valid(v)) { "not a proxy address" }
            if (v.isEmpty()) file.delete() else write(v)
            legacy.delete()
            cached = v
        }
        onChange()
    }

    /** 古いバージョンでは、コンテナーの使用時にのみコピーが保存されます。ロックを解除できる場合は、新しいものとして保存します。新しい値がすでに存在する場合、またはロックを解除できない場合は、移動せず、新しい値を保存するときに削除してください。 */
    private fun migrate() {
        val old = legacyCipher ?: return
        if (file.isFile || !legacy.isFile) return
        val v = runCatching { String(old.decrypt(legacy.readBytes()), Charsets.UTF_8) }.getOrNull() ?: return
        runCatching {
            if (v.isNotBlank()) write(v.trim())
            legacy.delete()
        }
    }

    private fun write(v: String) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeBytes(cipher.encrypt(v.toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val FILE = "net-proxy.bin"
        private const val LEGACY_FILE = "container-proxy.bin"
        private const val KEY_ALIAS = "mika-net-proxy"
        private const val LEGACY_KEY_ALIAS = "mika-container-proxy"

        /** 空の文字列 (未使用)、またはプロトコル、ホスト、ポート (http、https、socks5、socks5h) を含むプロキシ アドレス。背後にパスはありません。 */
        fun valid(proxy: String): Boolean = ProxyAddress.valid(proxy)

        /** 人々が見ることができるように書かれています: アカウントとパスワードなし (その行は設定ページでいつでも見ることができます)。 */
        fun display(proxy: String): String = ProxyAddress.parse(proxy)?.display.orEmpty()

        @Volatile private var instance: NetProxyStore? = null

        /** は製品に含まれています。初めて手に取るときに [NetProxy.shared] を接続してください。 */
        fun get(context: Context): NetProxyStore = instance ?: synchronized(this) {
            instance ?: NetProxyStore(
                File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(KEY_ALIAS), KeystoreKeyCipher(LEGACY_KEY_ALIAS),
                onChange = { NetProxy.shared.changed(); WebProxy.apply(context.applicationContext) },
            ).also { store ->
                instance = store
                NetProxy.sharedSource = store::get
            }
        }
    }
}
