package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.os.Build
import com.wangzan101.mika.core.cap.sandbox.RootfsExtractor
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.MessageDigest

/** インストール パッケージに付属する実行環境: アセット内の tar.xz、バージョンおよび SHA-256 は tools/sandbox/build-runtime.py によって生成されます。 */
class RuntimeBundle(
    val version: String,
    val sha256: String,
    /** デコードされた通常のファイルの総バイト数とエントリ数の上限 (不正なパケットがスペースを埋めるのを防ぐため)。 */
    val maxBytes: Long,
    val maxEntries: Int,
    val open: () -> InputStream,
) {
    companion object {
        fun bundled(context: Context): RuntimeBundle = RuntimeBundle(
            BundledRuntime.VERSION, BundledRuntime.SHA256,
            BundledRuntime.FILE_BYTES + BundledRuntime.FILE_BYTES / 4 + (64L shl 20), BundledRuntime.ENTRIES + 1000,
        ) { context.assets.open(BundledRuntime.ASSET) }
    }
}

/** [通知と設定] ページに表示されるステップ名。 */
internal val InstallStep.label: String get() = when (this) {
    InstallStep.Check -> tr(R.string.install_check)
    InstallStep.Extract -> tr(R.string.install_extract)
    InstallStep.Configure -> tr(R.string.install_configure)
    InstallStep.Skills -> tr(R.string.install_skills)
    InstallStep.Done -> tr(R.string.result_completed)
}

/**
 * 実行環境を準備します: チェック → 組み込みルート ファイル システムの抽出 → 構成 → 同期スキル → 完了。インターネットに接続されていません。
 * 各ステップは [SandboxState] として記録され、次回はそのステップの記録の次のステップから開始されます。失敗すると、ステータスは Broken (理由) となりスローされます。
 * 同梱物の版が変わったら（Mika のアップグレード）再展開する: 新しいディレクトリへ展開し、検証に通ったら差し替え、ワークスペースには触れない。
 *
 * [dirs]、[state]、[bundle] 以外のパラメータはテスト用の差し替え口で、既定値が実機の振る舞いそのもの。
 */
class RootfsInstaller(
    private val dirs: SandboxDirs,
    private val state: SandboxState,
    private val bundle: RuntimeBundle,
    private val abis: () -> List<String> = { Build.SUPPORTED_ABIS.toList() },
    private val freeBytes: () -> Long = { dirs.root.parentFile?.usableSpace ?: 0L },
    /** `libmikarun.so --self-test` を実行し、（終了コード, 出力）を返す。 */
    private val selfTest: suspend () -> Pair<Int, String> = { runSelfTest(dirs) },
    /** ホスト上でこの /proc ファイルが読めるかどうか; 読めないものは代替を生成する。 */
    private val procReadable: (String) -> Boolean = ::hostCanRead,
    private val prepareDev: (File) -> Unit = { ProotDevices.prepareRootfsDev(it, dirs.gate) },
    /** ブリッジ クライアント スクリプト (/usr/local/bin/mika にインストール); null = インストール パッケージに含まれていないため、インストールは失敗しました。 */
    private val clientScript: () -> ByteArray? = { null },
    private val dnsServers: () -> List<String> = { emptyList() },
    private val syncSkills: suspend () -> Unit = {},
) {
    private val fresh get() = File(dirs.root, "rootfs.new")
    private val old get() = File(dirs.root, "rootfs.old")

    /** とリセット、スキル同期、コマンドの実行、および `files/sandbox/` に書き込まれるその他の操作は、[SandboxTree] を介して相互に排他的です。 */
    suspend fun install(onProgress: (InstallStep, Int) -> Unit) = dirs.tree.exclusive {
        withContext(Dispatchers.IO) {
            val upToDate = state.installedVersion == bundle.version
            if (state.completedStep() == InstallStep.Done && state.status.value is SandboxStatus.Ready && upToDate) {
                onProgress(InstallStep.Done, 100)
                return@withContext
            }
            var step = state.completedStep()?.let { done -> STEPS.getOrNull(STEPS.indexOf(done) + 1) } ?: InstallStep.Check
            // は古いバージョンでインストールされています (Mika がアップグレードしました)。再度開梱し、新しいバージョンが開梱されるまで古いバージョンを保管しておきます。
            if (state.completedStep() == InstallStep.Done && !upToDate) step = InstallStep.Check
            try {
                for (s in STEPS.drop(STEPS.indexOf(step))) {
                    onProgress(s, PERCENT.getValue(s))
                    state.installing(s.label, PERCENT.getValue(s))
                    run(s)
                    state.markStep(s, bundle.version)
                }
            } catch (e: CancellationException) {
                state.markBroken(tr(R.string.install_interrupted))
                throw e
            } catch (e: Exception) {
                state.markBroken(e.message ?: e.javaClass.simpleName)
                throw e
            } finally {
                state.cleared()
            }
        }
    }

    /** `files/sandbox/` 全体を削除し (シンボリック リンクをたどらずに、最初に読み取り専用ディレクトリを書き込み可能に変更します)、アンインストールされた状態に戻ります。 */
    suspend fun reset() = dirs.exclusiveWrite {
        state.resetting()
        try {
            // これらのディレクトリのサンドボックスでは、ハンドルによる削除、および途中で変更されたリンクをたどらないコマンドが発生する可能性があります。
            deleteReachable(dirs.root)
            deleteReachable(dirs.tmp)
        } finally {
            state.cleared()
        }
    }

    /** すべての会話のワークスペースを削除します。 */
    suspend fun clearWorkspaces() = dirs.exclusiveWrite { deleteReachable(dirs.work) }

    private suspend fun run(s: InstallStep) {
        when (s) {
            InstallStep.Check -> check()
            InstallStep.Extract -> extractAndSwap()
            InstallStep.Configure -> configure()
            InstallStep.Skills -> syncSkills()
            InstallStep.Done -> deleteReachable(old)
        }
    }

    /**
     * は `rootfs.new` に解決され、ファイル全体の SHA-256 は解決中に計算されます。一致する場合は置き換えます (古いものは最初に名前が変更されてから削除されます)。一致しない場合は完全に削除します。
     * は途中で強制終了されました。次回は解凍からやり直して、最初に残っている `rootfs.new` を削除します。
     */
    private fun extractAndSwap() {
        RootfsExtractor.deleteTree(fresh)
        deleteReachable(old)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestInputStream(BufferedInputStream(bundle.open(), 1 shl 16), digest).use { raw ->
                // アンパッカーはストリームを読み取った後、ストリームをオフにします。ここでは検証を計算するために残りを読み取る必要があるため、生のままにしないでください。
                val keepOpen = object : FilterInputStream(raw) { override fun close() = Unit }
                RootfsExtractor.extractTar(XZInputStream(keepOpen), fresh, maxBytes = bundle.maxBytes, maxEntries = bundle.maxEntries)
                val buf = ByteArray(1 shl 16)
                while (raw.read(buf) >= 0) Unit // xz 最後に読み取れなかったバイトもチェック対象となります。
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != bundle.sha256) throw IOException(tr(R.string.install_checksum))
        } catch (e: Exception) {
            RootfsExtractor.deleteTree(fresh)
            throw IOException(tr(R.string.install_unpack_failed, e.message), e)
        }
        if (Files.exists(dirs.rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.move(dirs.rootfs.toPath(), old.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(fresh.toPath(), dirs.rootfs.toPath(), StandardCopyOption.ATOMIC_MOVE)
        deleteReachable(old)
    }

    private suspend fun check() {
        if ("arm64-v8a" !in abis()) throw IOException(tr(R.string.install_unsupported))
        val free = freeBytes()
        // アップグレードすると、古いコピーと新しいコピーの両方が同時にディスク上に存在します。
        val need = 2 * bundle.maxBytes
        if (free < need) throw IOException(tr(R.string.install_no_space, need shr 20, free / (1 shl 20)))
        val (code, out) = selfTest()
        val lines = out.lines().map { it.trim() }
        if (code != 0 || lines.firstOrNull() != "mikarun ok") {
            throw IOException(tr(R.string.install_cannot_start, lines.lastOrNull { it.isNotEmpty() } ?: tr(R.string.install_exit_code, code)))
        }
        // ネットワーク切断フィルターは使用できないため、インストールできません。ネットワークがオフになるとコマンドは拒否されます。ここでは、設定ページの理由のみを記録します。
        state.recordIsolation(lines.firstOrNull { it.startsWith("seccomp unavailable") }?.removePrefix("seccomp unavailable:")?.trim())
    }

    private fun configure() {
        writeInRootfs("etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        writeInRootfs("etc/resolv.conf", dnsServers().ifEmpty { FALLBACK_DNS }.joinToString("") { "nameserver $it\n" })
        val client = clientScript() ?: throw IOException(tr(R.string.install_no_client))
        writeInRootfs("usr/local/bin/mika", client, executable = true)
        prepareDev(dirs.rootfs)
        writeProcStandIns()
    }

    /** ルート ファイル システム内のファイル: ゲートウェイ経由で同じディレクトリに新しいファイルを書き込み、完全に置き換えます。リンクである元のアイテム (空のままでも) とハード リンクは単純に置き換えられます。 */
    private fun writeInRootfs(rel: String, text: String) = writeInRootfs(rel, text.toByteArray(), executable = false)

    private fun writeInRootfs(rel: String, bytes: ByteArray, executable: Boolean) {
        try {
            dirs.gate.replace(dirs.rootfs, rel, bytes, mode = if (executable) 493 /* 0755 */ else 420 /* 0644 */)
        } catch (e: com.wangzan101.mika.core.workspace.SandboxError) {
            throw IOException("$rel cannot be written in the rootfs (${e.code})")
        }
    }

    /** は、[dir] のツリー全体を削除します (その上位レベルは、Mika 自身のディレクトリです)。ハンドルに従ってチェックポイントを通過し、レイヤーごとに進みます。 */
    private fun deleteReachable(dir: File) {
        val parent = dir.parentFile ?: return
        dirs.gate.deleteTree(parent, dir.name)
    }

    /** Android では、通常のアプリが /proc ファイルを読み取ることを許可せず、正しい形式で静的アバターを生成し、実行時にバインドします。 */
    private fun writeProcStandIns() {
        RootfsExtractor.deleteTree(dirs.procStandIns)
        dirs.procStandIns.mkdirs()
        for ((name, content) in procStandInContents()) {
            if (!procReadable("/proc/$name")) File(dirs.procStandIns, name).writeText(content)
        }
    }

    companion object {
        private val STEPS = InstallStep.entries.toList()
        private val PERCENT = mapOf(
            InstallStep.Check to 0, InstallStep.Extract to 5, InstallStep.Configure to 90, InstallStep.Skills to 95, InstallStep.Done to 100,
        )

        /** 実機のインストーラー: 組み込みのオペレーティング環境、資産内のブリッジ クライアント、および現在のネットワークの DNS。 */
        fun create(context: Context): RootfsInstaller {
            val app = context.applicationContext
            val dirs = SandboxDirs(app)
            return RootfsInstaller(
                dirs, SandboxState.get(app), RuntimeBundle.bundled(app),
                clientScript = { runCatching { app.assets.open("sandbox/mika").use { it.readBytes() } }.getOrNull() },
                dnsServers = { AndroidSandbox.dns(app) },
                syncSkills = { SkillsSync.sync(dirs) },
            )
        }

        private fun hostCanRead(path: String): Boolean = runCatching { File(path).inputStream().use { it.read() }; true }.getOrDefault(false)

        private suspend fun runSelfTest(dirs: SandboxDirs): Pair<Int, String> {
            val o = AndroidSandbox.launch(listOf(dirs.lib("libmikarun.so").path, "--self-test"), emptyMap(), 15_000)
            return o.exitCode to String(o.output, Charsets.UTF_8)
        }

        private fun procStandInContents(): Map<String, String> {
            val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val bootSec = System.currentTimeMillis() / 1000
            return linkedMapOf(
                "stat" to buildString {
                    append("cpu  0 0 0 0 0 0 0 0 0 0\n")
                    for (i in 0 until cpus) append("cpu$i 0 0 0 0 0 0 0 0 0 0\n")
                    append("intr 0\nctxt 0\nbtime $bootSec\nprocesses 1\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
                },
                "loadavg" to "0.00 0.00 0.00 1/1 1\n",
                "uptime" to "1.00 1.00\n",
                "version" to "Linux version ${System.getProperty("os.version") ?: "0"} (mika@sandbox) #1 SMP PREEMPT\n",
                "vmstat" to "nr_free_pages 0\nnr_inactive_anon 0\nnr_active_anon 0\nnr_inactive_file 0\nnr_active_file 0\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
            )
        }
    }
}
