package com.wangzan101.mika.about

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest

/** 更新パッケージが検証に失敗すると拒否されるのはなぜですか?インターフェイスはこれに基づいてさまざまな指示を与えます。 */
class ApkRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** サイズと SHA-256 が一致していないか、インストール パッケージをまったく読み取ることができません。 */
        Corrupt,
        /** はMikaではないか、バージョンがインストールされているものより新しくありません。 */
        NotNewer,
        /** 署名証明書は、インストールされているミカとは異なります。 */
        Signer,
    }
}

/**
 * ダウンロード アドレスは、公式ウェアハウスからの HTTPS リリース添付ファイルのみを受け入れます。それ以降の (GitHub の添付ファイル ストレージへの) ジャンプはシステム ダウンロード サービスによって処理され、当社の制御の範囲を超えています。
 * したがって、ダウンロードされたすべてのファイルは、サイズと SHA-256 (検証のためにリリース メタデータが提供されます)、パッケージ名、バージョン番号、および署名証明書を再検証する必要があります。
 */
internal object ApkIntegrity {
    private val official = "${MikaLinks.RELEASES}/download/".toHttpUrl()

    fun requireOfficialUrl(url: String) {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.host == official.host && parsed.port == 443)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        require(parsed.encodedPath.startsWith(official.encodedPath))
        require(parsed.encodedPath.endsWith(".apk", ignoreCase = true))
    }

    /** 要約が指定されている場合、それは sha256 内に存在し、一貫性がなければなりません。他の書き方は一貫性がないとみなされるため、これを手放しません。 */
    fun verify(file: File, size: Long?, digest: String?) {
        corruptUnless(file.isFile && file.length() > 0)
        if (size != null) corruptUnless(file.length() == size)
        if (digest != null) {
            corruptUnless(digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
            corruptUnless(sha256(file).equals(digest.substringAfter(':'), ignoreCase = true))
        }
    }

    /**
     * 新しいパッケージの署名証明書 (各証明書 SHA-256) は、インストールされているものと一致している必要があります。新しいパッケージが署名のためにローテーションされるとき、インストールされた証明書がその署名履歴に存在する必要があります。
     * システム インストーラーもブロックされます。事前にブロックする目的は、明確なヒントを提供することです。
     */
    fun requireSameSigner(installed: Set<String>, archive: Set<String>, archiveHistory: Set<String>) {
        if (installed.isEmpty() || archive.isEmpty()) throw ApkRejected(ApkRejected.Reason.Signer)
        if (archive != installed && !archiveHistory.containsAll(installed)) throw ApkRejected(ApkRejected.Reason.Signer)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun corruptUnless(ok: Boolean) {
        if (!ok) throw ApkRejected(ApkRejected.Reason.Corrupt)
    }
}
