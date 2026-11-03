package com.wangzan101.mika.about

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.wangzan101.mika.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DownloadPhase { Idle, Downloading, Paused, Ready, Failed }

data class ApkDownloadState(
    val phase: DownloadPhase = DownloadPhase.Idle,
    val bytes: Long = 0,
    val total: Long = -1,
) {
    val percent: Int? get() = if (total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
}

/**
 * システムはバックグラウンド ダウンロードを担当します。タスク番号のみが保存され、プロセスの再起動後もタスク番号の照会、キャンセル、インストールを行うことができます。このメソッドは IO スレッドで呼び出されます。
 * ファイルは、このアプリケーションの外部専用ディレクトリに配置されます (他のアプリケーションから読み書きすることはできません)。一度に残るファイルは 1 つだけです。バージョンを変更、削除、またはインストールするとクリアされます。
 * ネットワーク プロキシが設定されている場合 (設定 › ネットワーク プロキシ)、システム ダウンロード サービスはそれを使用しません。代わりに、Mika はプロセス内でプロキシ経由でファイルをダウンロードします ([ProxiedApkDownload])。
 */
class ApkUpdater(private val context: Context, private val net: NetProxy = NetProxy.shared) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
    private val directory: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("external files unavailable"), "updates")
    private val file: File? get() = prefs.getString("file", null)?.let { File(directory, it) }
    /** これは、Mika 自身によって配布されています (システム ダウンロード サービスではありません)。 */
    private val viaApp: Boolean get() = prefs.getBoolean("viaApp", false)

    fun state(release: ReleaseInfo): ApkDownloadState =
        if (prefs.getString("url", null) != release.apkUrl) ApkDownloadState() else recorded()

    /** 書き留められたダウンロード タスクの現在のステータス。タスクがシステム内で見つからない場合、失敗とみなされます。 */
    private fun recorded(): ApkDownloadState {
        if (viaApp) return file?.let(proxied::state) ?: ApkDownloadState(DownloadPhase.Failed)
        val id = prefs.getLong("id", -1)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val phase = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> DownloadPhase.Downloading
                    DownloadManager.STATUS_PAUSED -> DownloadPhase.Paused
                    DownloadManager.STATUS_SUCCESSFUL -> if (file?.isFile == true) DownloadPhase.Ready else DownloadPhase.Failed
                    else -> DownloadPhase.Failed
                }
                return ApkDownloadState(phase, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            }
        }
        return ApkDownloadState(DownloadPhase.Failed)
    }

    fun download(release: ReleaseInfo) {
        val url = requireNotNull(release.apkUrl)
        ApkIntegrity.requireOfficialUrl(url)
        if (prefs.getString("url", null) == url && state(release).phase in
            listOf(DownloadPhase.Downloading, DownloadPhase.Paused, DownloadPhase.Ready)) return
        cancel()
        val folder = directory
        check(folder.mkdirs() || folder.isDirectory)
        // プロセスが強制終了され、レコードが失われたときに残された古いファイルもクリアされ、今回のみディレクトリに残ります。
        folder.listFiles()?.forEach { it.delete() }
        // 削除のバックグラウンド クリーンアップは次のダウンロードよりも遅れる可能性があり、古いタスクのファイル名は再利用できません。
        val target = File(folder, "update-${UUID.randomUUID()}.apk")
        if (net.proxyFor(Uri.parse(url).host.orEmpty()) != null) {
            check(prefs.edit().putBoolean("viaApp", true).putString("url", url).putString("file", target.name).commit()) { "could not save download" }
            proxied.start(url, target)
            return
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Mika ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            // はダウンロード中にのみ通知を表示します。完了後の通知は次の検証をバイパスし、インストール パッケージを直接開くため、そのまま放置しないでください。
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
        val id = manager.enqueue(request)
        if (!prefs.edit().putLong("id", id).putString("url", url).putString("file", target.name).commit()) {
            manager.remove(id)
            error("could not save download")
        }
    }

    fun cancel() {
        if (viaApp) proxied.cancel()
        val id = prefs.getLong("id", -1)
        if (id != -1L) manager.remove(id)
        val target = file
        check(target == null || !target.exists() || target.delete())
        check(prefs.edit().clear().commit())
    }

    /** がインストールされた後 (または新しいバージョンがインストールされた後)、ダウンロードしたインストール パッケージは役に立ちません。起動時にクリアしてください。ダウンロード、一時停止、または失敗したダウンロードは、[バージョン情報] ページで処理されます。 */
    fun dropInstalled() {
        val target = file ?: return
        if (recorded().phase != DownloadPhase.Ready) return
        val archive = archiveInfo(target, 0)
        if (archive != null && archive.longVersionCode > installedInfo(0).longVersionCode) return
        cancel()
    }

    /**
     * は、このアプリケーション、より上位のバージョン、および同じ署名の完全な添付ファイルのみを受け入れます。失敗した場合は、ファイルを削除して [ApkRejected] を捨ててください。
     * システム インストーラーは署名を再検証し、ユーザーに確認を求めます。
     */
    fun installIntent(release: ReleaseInfo): Intent {
        check(state(release).phase == DownloadPhase.Ready)
        ApkIntegrity.requireOfficialUrl(requireNotNull(release.apkUrl))
        val target = requireNotNull(file)
        try {
            ApkIntegrity.verify(target, release.apkSize, release.apkDigest)
            val archive = archiveInfo(target, SIGNING_FLAGS) ?: throw ApkRejected(ApkRejected.Reason.Corrupt)
            val installed = installedInfo(SIGNING_FLAGS)
            if (archive.packageName != context.packageName || archive.longVersionCode <= installed.longVersionCode) {
                throw ApkRejected(ApkRejected.Reason.NotNewer)
            }
            ApkIntegrity.requireSameSigner(installed.signers(), archive.signers(), archive.signerHistory())
        } catch (e: ApkRejected) {
            cancel()
            throw e
        }
        val uri = if (viaApp) FileProvider.getUriForFile(context, "${context.packageName}.updatefiles", target)
            else manager.getUriForDownloadedFile(prefs.getLong("id", -1)) ?: error("missing APK")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(target: File, flags: Int): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(target.path, flags)

    @Suppress("DEPRECATION")
    private fun installedInfo(flags: Int): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    /** 現在の署名証明書。一部のシステムでは、インストール パッケージを読み取るときに signedInfo が提供されず、古いフィールド署名が返されます。 */
    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Set<String> =
        (signingInfo?.apkContentsSigners ?: signatures).orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()

    /** ローテーションされた署名付きパッケージには履歴証明書が含まれます。署名者が複数いる場合、履歴はありません。 エージェントを通じてプロセスに配置される */
    private fun PackageInfo.signerHistory(): Set<String> {
        val info = signingInfo ?: return emptySet()
        if (info.hasMultipleSigners()) return emptySet()
        return info.signingCertificateHistory.orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()
    }

    private companion object {
        /** のコピーは 1 つだけです。 */
        val proxied by lazy {
            ProxiedApkDownload(NetProxy.shared.client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)))
        }

        @Suppress("DEPRECATION")
        const val SIGNING_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}
