package com.wangzan101.mika.cap.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.settings.NetProxyStore
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxProcess
import com.wangzan101.mika.core.cap.sandbox.PhoneCommandHandler
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.knowledge.ScriptEntry
import com.wangzan101.mika.core.knowledge.ScriptManifest
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val EXIT_TIMEOUT = 124
private const val EXIT_LAUNCHER_ERROR = 125
private const val EXIT_SIGKILL = 128 + 9
/** ランチャー自体には時間制限があります。長時間待っても終了しない場合、Kotlin 側がそれを強制終了します。 */
private const val FALLBACK_GRACE_MS = 10_000L
private const val DESTROY_GRACE_MS = 3_000L
private const val READER_GRACE_MS = 2_000L
private const val TIMEOUT_SLACK_MS = 500L
private const val SIZE_CACHE_MS = 60_000L
internal val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
private const val TAG = "AndroidSandbox"
internal const val SHARE_MAX_FILES = 20
internal const val SHARE_MAX_BYTES = 500L shl 20

/**
 * はタスク (AndroidSandbox) のエクスポート クォータで、share_file は `mika share` と共有されます。
 * は、コピー前のファイル サイズに基づいて予約され、コピー後の実際のバイト数に基づいて決定されます。エクスポートの失敗はクォータを考慮しません。
 */
internal class ShareQuota(private val maxFiles: Int = SHARE_MAX_FILES, private val maxBytes: Long = SHARE_MAX_BYTES) {
    private var files = 0
    private var bytes = 0L

    @Synchronized fun reserve(size: Long) {
        if (files + 1 > maxFiles || bytes + size > maxBytes) {
            throw SandboxError("share_limit", "One task can share at most $maxFiles files and ${maxBytes shr 20} MB in total.", recoverable = false)
        }
        files++
        bytes += size
    }

    @Synchronized fun settle(reserved: Long, actual: Long) { bytes += actual - reserved }

    @Synchronized fun release(reserved: Long) {
        files--
        bytes -= reserved
    }
}

/**
 * は、サンドボックス プロセスを開始し、プロセスが生きている間はアクティブなままになる専用のスレッドです。ランチャーは PR_SET_PDEATHSIG を設定します。
 * これは、フォークしたスレッドに従います。スレッド プールがアイドル状態のスレッドをリサイクルすると、そのスレッドによって発行されたコマンドは「Mika が死んだ」として強制終了されます。
 * コルーチン スケジューラは start() の例外を呼び出し元に返しますが、スレッド自体は例外により終了しません。
 */
private val launcherThread = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-sandbox-launcher").apply { isDaemon = true } }
    .asCoroutineDispatcher()

/** [SandboxPort] の Android 実装: ランチャー + PRoot はコマンドを実行し、ファイル操作はホスト ディレクトリを直接読み書きします。 */
class AndroidSandbox internal constructor(
    private val dirs: SandboxDirs,
    private val currentStatus: () -> SandboxStatus,
    private val dnsServers: () -> List<String>,
    /** は実行されるたびにブリッジ セッションを開きます。 JVM テストには Android ソケットがないため、false を渡します。 */
    private val bridges: Boolean = false,
    private val version: String = "",
    /** 「Download/Mika」に保存: (チェックポイント検証に合格したファイル、名前を保存) → エクスポートされたファイル;空の場合はエクスポートできません。 */
    private val exporter: (suspend (GatedFile, String) -> SharedFile)? = null,
    /** エクスポートが成功した後にタスク (ファイル カード) に通知します。 */
    private val onShared: (SharedFile) -> Unit = {},
    /** 各実行前に準備します (パッケージ化されたコピーにスキル カタログが含まれていることを確認します)。 */
    private val sync: suspend () -> Unit = {},
    /** は、アプリにパッケージ化されているスキル スクリプトです (スキル ディレクトリ → コンテンツの下の相対パス)。リストとスクリプトの両方がその対象となります。 */
    private val skillFiles: () -> Map<String, ByteArray> = { emptyMap() },
    /** は実行環境の準備を開始 (または進行中) します。準備は必要なく、null です。 */
    private val startPreparing: () -> Job? = { null },
    /** ユーザーのネットワーク設定。指定しない場合は、準備完了状態で を押します。 */
    private val allowNetwork: (() -> Boolean)? = null,
    /** ネットワーク プロキシ (設定 › ネットワーク プロキシ、[NetProxyStore] を参照);空の文字列は使用されません。ネットワークを許可するコマンドでのみ指定されます。 */
    private val proxy: () -> String = { "" },
    /** APK のブリッジ クライアント。既存の実行環境も APK で更新する必要があります。 */
    private val clientScript: () -> ByteArray? = { null },
) : SandboxPort {
    override val tree get() = dirs.tree

    constructor(context: Context, state: SandboxState, onShared: (SharedFile) -> Unit = {}) : this(
        SandboxDirs(context), { state.status.value }, { dns(context) }, bridges = true, version = versionOf(context),
        exporter = ShareExporter(context)::export, onShared = onShared, sync = { SkillsSync.sync(SandboxDirs(context)) { state.status.value is SandboxStatus.Ready } },
        skillFiles = { SkillsSync.bundled },
        startPreparing = { RuntimePreparer.ensure(context, retryFailed = true) },
        allowNetwork = { state.networkAllowed },
        proxy = { NetProxyStore.get(context).get() },
        clientScript = { context.assets.open("sandbox/mika").use { it.readBytes() } },
    )

    private val quota = ShareQuota()

    private class SizeEntry(val bytes: Long, val at: Long)
    private val sizes = ConcurrentHashMap<String, SizeEntry>()

    override fun status(): SandboxStatus = currentStatus()

    override fun networkAllowed(): Boolean = allowNetwork?.invoke() ?: super.networkAllowed()

    override suspend fun awaitReady(timeoutMs: Long): SandboxStatus {
        val s = status()
        if (s is SandboxStatus.Ready || s is SandboxStatus.Unsupported) return s
        val job = startPreparing() ?: return status()
        withTimeoutOrNull(timeoutMs) { job.join() }
        return status()
    }

    override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit): CommandResult = runWithPhone(workspace, argv, cwd, timeoutMs, onProgress, null)

    override suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit,
        phone: PhoneCommandHandler?): CommandResult {
        val network = (status() as? SandboxStatus.Ready)?.network == true
        // 呼び出し元によって指定された cwd を再度確認してください。正規化されており、まだ作業領域にある必要があります。
        val rel = cwd?.takeIf { it.isNotEmpty() }?.let { c ->
            val n = SandboxPaths.normalize(c)?.takeIf(SandboxPaths::isWritable)
                ?: throw SandboxError("path_outside_workspace", "cwd must be a directory inside /workspace.", recoverable = true)
            n.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        }.orEmpty()
        // スキル スクリプトは同期されておらず、コマンドをブロックしません。スクリプトを実行できない場合は、run_bundled_script がその旨を報告します。
        prepare()
        val timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(1, 3600).toInt()
        // ディレクトリの作成からコマンドの終了まで、権限が含まれます。コマンドはワークスペースにも書き込まれます。コマンドを削除する前に、実行が終了するまで待つ必要があります。
        val o = tree.shared {
            val ws = withContext(Dispatchers.IO) {
                dirs.workspace(workspace).also {
                    dirs.ensureBindSources(it)
                    syncClientScript()
                    // cwd がワークスペースに存在しない場合は、それをビルドします (リンクをたどらずにセクションごとに)。ビルドできない場合はビルドせず、PRoot 自身でエラーを報告させます。
                    rel.takeIf { c -> c.isNotEmpty() }?.let { c -> runCatching { files.mkdirs(it, c) } }
                    if (network) writeResolvConf()
                }
            }
            val cmd = ProcRunner.argv(dirs, ws, rel, timeoutSec, network, standIns(), argv)
            // コマンド終了後 (タイムアウト、強制終了、キャンセルを含む) ブリッジがシャットダウンされます。古い名前とトークンは使用できなくなります。
            val session = if (bridges) bridgeFor(workspace, network, onProgress, phone).open() else null
            try {
                launch(cmd, ProcRunner.env(dirs, session?.name, session?.token, workspace, if (network) proxy() else ""), timeoutSec * 1000L + FALLBACK_GRACE_MS)
            } finally {
                session?.close()
            }
        }
        sizes.remove(workspace)
        val text = String(o.output, Charsets.UTF_8)
        if (o.exitCode == EXIT_LAUNCHER_ERROR) {
            val reason = text.trimEnd().lineSequence().lastOrNull().orEmpty()
            if (reason.startsWith("mikarun: network isolation unavailable")) {
                throw SandboxError("sandbox_network_isolation_unavailable",
                    "The sandbox cannot cut off the network on this device, so commands are refused while network access is off. ($reason)")
            }
        }
        val timedOut = o.timedOut || launcherTimedOut(o.exitCode, o.durationMs, timeoutSec)
        return CommandResult(
            exitCode = if (o.timedOut) EXIT_TIMEOUT else o.exitCode, timedOut = timedOut,
            killedBySystem = !timedOut && o.exitCode == EXIT_SIGKILL, durationMs = o.durationMs,
            output = text, droppedBytes = o.droppedBytes, network = network,
        )
    }

    /**
     * は、サンドボックス内の常駐 stdio プロセスを開始します。ワンタイムコマンドと同様に、まず動作環境を準備し、ワークスペースを構築し、resolv.confを記述します。モバイルツールブリッジは提供されません
     * (stdio サーバーは独自の stdin/stdout のみを受け取ります)。
     *
     * プロセスは、存続期間中ツリー ([SandboxTree.lease]) のリースを保持します。これは通常の共有ではありません。排他的である必要があります (ルート ファイル システムの変更、リセット、ワークスペースのクリア)。
     * タスクで 1 時間開いているサーバーによってブロックされないように、プロセスを停止し、アクセス許可が取得されたらすぐに取り消します。後でプロセスを調整すると、接続が切断されたことが報告されます。
     * ディレクトリの作成から起動までを別の短い共有で取り上げます。専用のものは完了するまで削除されません。起動後に再度チェックして、再利用されているかどうかを確認します。
     */
    override suspend fun openProcess(workspace: String, argv: List<String>, cwd: String?): SandboxProcess {
        val network = (status() as? SandboxStatus.Ready)?.network == true
        val rel = cwd?.takeIf { it.isNotEmpty() }?.let { c ->
            val n = SandboxPaths.normalize(c)?.takeIf(SandboxPaths::isWritable)
                ?: throw SandboxError("path_outside_workspace", "cwd must be a directory inside /workspace.", recoverable = true)
            n.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        }.orEmpty()
        prepare()
        val started = java.util.concurrent.atomic.AtomicReference<Process?>(null)
        val lease = tree.lease(onRevoke = { started.get()?.let(::stopNow) })
        try {
            val process = tree.shared {
                val ws = withContext(Dispatchers.IO) {
                    dirs.workspace(workspace).also {
                        dirs.ensureBindSources(it)
                        syncClientScript()
                        rel.takeIf { c -> c.isNotEmpty() }?.let { c -> runCatching { files.mkdirs(it, c) } }
                        if (network) writeResolvConf()
                    }
                }
                val cmd = ProcRunner.argv(dirs, ws, rel, 3600, network, standIns(), argv)
                val env = ProcRunner.env(dirs, null, null, workspace, if (network) proxy() else "")
                val p = withContext(launcherThread + NonCancellable) {
                    ProcessBuilder(cmd).redirectErrorStream(false).apply {
                        environment().clear()
                        environment().putAll(env)
                    }.start()
                }
                started.set(p)
                // 取り消しがリースの取得とプロセスの登録の間にある場合、onRevoke はそれを認識しません。ここでそれを補います。
                if (lease.revoked) {
                    stopNow(p)
                    throw SandboxError("sandbox_busy", "The sandbox is being reset; try again later.", recoverable = true)
                }
                p
            }
            return SandboxProcessHandle(process, lease)
        } catch (e: Throwable) {
            started.get()?.let(::stopNow)
            lease.close()
            throw e
        }
    }

    override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File? {
        val p = SandboxPaths.normalize(sandboxPath) ?: return null
        return when {
            SandboxPaths.isWritable(p) -> SandboxPaths.resolveHost(dirs.workspace(workspace), p.removePrefix(SandboxPaths.WORKSPACE).trimStart('/'))
            forWrite -> null
            else -> SandboxPaths.resolveHost(dirs.skills, p.removePrefix(SandboxPaths.SKILLS).trimStart('/'))
        }
    }

    override val files: WorkspaceFileGate get() = dirs.gate

    // ワークスペース ディレクトリは書き込み用にのみ作成できます (呼び出し元は SandboxTree のアクセス許可を保持しています)。パスの読み取りとチェックにはアクセス許可が保持されていないため、作成はリセット削除と競合します。
    override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>? = when {
        SandboxPaths.isWritable(normalized) -> dirs.workspace(workspace).also { if (forWrite) it.mkdirs() } to normalized.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        forWrite -> null
        else -> dirs.skills to normalized.removePrefix(SandboxPaths.SKILLS).trimStart('/')
    }

    /** web_upload パス チェック: パスに合格したこのワークスペース内の通常のファイルのみを認識します。スキル ディレクトリとワークスペースの外側は範囲外とみなされます。 */
    fun uploadCheck(workspace: String, sandboxPath: String): WorkspacePathCheck {
        hostPath(workspace, sandboxPath, forWrite = true) ?: return WorkspacePathCheck.Outside
        val n = SandboxPaths.normalize(sandboxPath) ?: return WorkspacePathCheck.Outside
        val (root, rel) = hostRoot(workspace, n, forWrite = false) ?: return WorkspacePathCheck.Outside
        return try {
            files.openRead(root, rel).close()
            WorkspacePathCheck.Ok
        } catch (e: SandboxError) {
            if (e.code == WorkspaceFileGate.OUTSIDE) WorkspacePathCheck.Outside else WorkspacePathCheck.NotFound
        }
    }

    /**
     * [file] は、ワークスペースごとの ShareRules によってチェックされました。エクスポート時に、ゲートウェイ (ルート ディレクトリがエクスポート先のワークスペース) を介して一度開かれ、量はハンドルのサイズに基づいて計算されます。
     * レプリケーションもこのハンドルから読み取ります。予期せぬエラーはミカ側でのみ記録され、ミカのプライベートパスはなく、エラーコードと文章のみが渡されます。 ワークスペース ディレクトリ (work/ の下の最初のレベル) と
     */
    override suspend fun share(file: File, name: String): String {
        val export = exporter ?: throw SandboxError("share_unavailable", "Saving files to Downloads is not available in this version.")
        val (within, rel) = withContext(Dispatchers.IO) { workspaceOf(file) }
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared.", recoverable = true)
        val opened = withContext(Dispatchers.IO) { files.openRead(within, rel) }
        return opened.use { g ->
            val reserved = g.size
            quota.reserve(reserved)
            val shared = try {
                export(g, name)
            } catch (e: Throwable) {
                quota.release(reserved)
                throw mapExportError(e)
            }
            quota.settle(reserved, shared.size)
            onShared(shared)
            shared.savedAs
        }
    }

    private fun mapExportError(e: Throwable): Throwable = when (e) {
        is CancellationException, is SandboxError -> e
        is FileTooLarge -> SandboxError("file_too_large", "At most ${ShareRules.MAX_BYTES shr 20} MB can be shared.", recoverable = true)
        is Exception -> {
            Log.w(TAG, "export to Downloads failed", e)
            SandboxError("share_failed", "Saving to Downloads failed.", recoverable = true)
        }
        else -> e
    }

    /** ファイルが配置されているワークスペースの間の相対パス。どのワークスペースにもない場合は null です。 */
    private fun workspaceOf(file: File): Pair<File, String>? {
        val work = dirs.work.canonicalFile
        val rel = file.canonicalFile.toRelativeString(work).takeIf { !it.startsWith("..") && it.isNotEmpty() && !File(it).isAbsolute } ?: return null
        val first = rel.substringBefore(File.separatorChar)
        return if (first == rel) null else File(work, first) to rel.substringAfter(File.separatorChar).replace(File.separatorChar, '/')
    }

    override fun freeBytes(): Long = dirs.root.takeIf { it.exists() }?.usableSpace ?: dirs.root.parentFile?.usableSpace ?: 0L

    /** はリンクをたどらなくても蓄積されます。 1 分間キャッシュされた場合、このワークスペースでコマンドが実行されると無効になります。 */
    override fun workspaceBytes(workspace: String): Long {
        val now = System.currentTimeMillis()
        sizes[workspace]?.takeIf { now - it.at < SIZE_CACHE_MS }?.let { return it.bytes }
        val bytes = treeBytes(dirs.workspace(workspace).toPath())
        sizes[workspace] = SizeEntry(bytes, now)
        return bytes
    }

    /** はパスを通じて .mika/ に作成されます (既存のものは上書きされません)。何も書かずに .mika をリンクなどに置き換えるとスローされます。 */
    @Synchronized
    override fun saveOutput(workspace: String, text: String): String {
        val root = dirs.workspace(workspace).apply { mkdirs() }
        var next = (files.list(root, ".mika").mapNotNull { OUT_NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        repeat(100) {
            val rel = ".mika/out-$next.txt"
            try {
                files.createNew(root, rel, under = ".mika").use { it.write(text.toByteArray(Charsets.UTF_8)) }
                return "${SandboxPaths.WORKSPACE}/$rel"
            } catch (e: SandboxError) {
                if (e.code != WorkspaceFileGate.FILE_EXISTS) throw IOException("cannot save output: ${e.code}")
                next++
            }
        }
        throw IOException("cannot save output: too many files")
    }

    /** アプリに同梱されているリストを読んでください: サンドボックス内のコマンドは変更できるので、信じられないでしょう。 */
    override fun scriptManifest(skillPath: String): List<ScriptEntry> {
        val bytes = skillFiles()["$skillPath/scripts/manifest.json"] ?: return emptyList()
        return ScriptManifest.parse(bytes.toString(Charsets.UTF_8))
    }

    override suspend fun prepare() {
        try { sync() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "skill sync failed", e) }
    }

    private val bundledClient: ByteArray? by lazy(clientScript)

    /** インストールされているルート ファイル システムのバージョンは変更されていない可能性がありますが、APK 内のブリッジ クライアントは更新されました。 */
    private fun syncClientScript() {
        val expected = bundledClient ?: return
        val rel = "usr/local/bin/mika"
        val matches = runCatching {
            dirs.gate.openRead(dirs.rootfs, rel).use { it.size == expected.size.toLong() && it.read(0, expected.size).contentEquals(expected) }
        }.getOrDefault(false)
        if (!matches || !File(dirs.rootfs, rel).canExecute()) dirs.gate.replace(dirs.rootfs, rel, expected, mode = 493 /* 0755 */)
    }

    override suspend fun verifyScript(skillPath: String, file: String): Boolean {
        val rel = "$skillPath/$file"
        val expected = skillFiles()[rel] ?: return false
        if (withContext(Dispatchers.IO) { SkillsSync.matches(dirs, rel, expected) }) return true
        prepare()
        return withContext(Dispatchers.IO) { SkillsSync.matches(dirs, rel, expected) }
    }

    /** `mika share` と share_file は、同じパス規則セットに従います。 */
    private fun bridgeFor(workspace: String, network: Boolean, onProgress: (String) -> Unit, phone: PhoneCommandHandler?): SandboxBridge {
        return SandboxBridge(
        onProgress = onProgress,
        onShare = { path, name ->
            val (f, n) = withContext(Dispatchers.IO) { ShareRules.check(this@AndroidSandbox, workspace, path, name) }
            share(f, n)
        },
        info = {
            buildJsonObject { put("version", version); put("network", network); put("workspace", SandboxPaths.WORKSPACE) }
        },
        onPhone = phone?.let { handler -> { tool, args -> handler.call(tool, args) } },
        )
    }

    /**
     * ランチャーのタイムアウト終了コードは 124 ですが、コマンド自体も 124 で終了する可能性があり、ランチャーはそれ以外のマークを付けません。
     * したがって、制限時間を超えた ([TIMEOUT_SLACK_MS] マージンを残した) 124 だけがタイムアウトとみなされます。事前に 124 で終了したものは、コマンドの実際の終了コードとしてカウントされます。
     */
    internal fun launcherTimedOut(exitCode: Int, durationMs: Long, timeoutSec: Int): Boolean =
        exitCode == EXIT_TIMEOUT && durationMs >= timeoutSec * 1000L - TIMEOUT_SLACK_MS

    private fun standIns(): List<String> = dirs.procStandIns.list()?.sorted().orEmpty()

    /** インターネットに接続するときに、現在のネットワークの DNS をルート ファイル システムに書き込みます。ゲートウェイを介してファイル全体を置き換えます (ルート ファイル システム内のコマンドは変更できますが、元のリンクのみが置き換えられます)。 */
    private fun writeResolvConf() {
        val servers = runCatching(dnsServers).getOrNull().orEmpty().ifEmpty { FALLBACK_DNS }
        files.replace(dirs.rootfs, "etc/resolv.conf", servers.joinToString("") { "nameserver $it\n" }.toByteArray())
    }

    internal companion object {
        private val OUT_NAME = Regex("^out-(\\d+)\\.txt$")

        private fun versionOf(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

        internal fun dns(context: Context): List<String> {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val lp = cm.getLinkProperties(cm.activeNetwork ?: return emptyList()) ?: return emptyList()
            return lp.dnsServers.mapNotNull { it.hostAddress }
        }

        internal fun treeBytes(root: Path): Long {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0
            var total = 0L
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
            return total
        }

        /**
         * start()を専用スレッドで実行し、残りをIOスレッドで出力(上限1MB)を読み取って終了します。 [limitMs]がまだ終わっていない場合は、力づくで殺します。
         * コルーチンがキャンセルされると、最初に SIGTERM が発行され (ランチャーは受信後にプロセス ツリー全体をクリアします)、[DESTROY_GRACE_MS] の後に SIGKILL が続きます。
         */
        internal suspend fun launch(
            cmd: List<String>, env: Map<String, String>, limitMs: Long,
            start: (ProcessBuilder) -> Process = ProcessBuilder::start,
        ): ProcOutcome {
            val begin = System.nanoTime()
            // 起動自体をキャンセルできません。 withContext が結果を元のコルーチンに返す場合、キャンセルされると結果は失われます。
            // つまり、プロセスはブロックに記録されます。結果が失われても、誰を殺すべきかがわかります。
            var started: Process? = null
            try {
                withContext(launcherThread + NonCancellable) {
                    started = start(ProcessBuilder(cmd).redirectErrorStream(true).apply {
                        environment().clear()
                        environment().putAll(env)
                    })
                }
            } catch (e: CancellationException) {
                started?.let(::abandon)
                throw e
            }
            val p = started!!
            // は、通常のスレッドを使用して出力を読み取ります。ブロックされた読み取りはキャンセルできません。ブロックされた読み取りをコルーチンに配置すると、キャンセルが永久に待機することになります。
            val collector = OutputCollector()
            val reader = thread(isDaemon = true, name = "mika-sandbox-output") { runCatching { p.inputStream.use(collector::drain) } }
            try {
                // は起動中にキャンセルされました: ここにスローし、次のキャッチで新しく開始されたプロセスを強制終了します。
                currentCoroutineContext().ensureActive()
                p.outputStream.close()
                val finished = runInterruptible(Dispatchers.IO) { p.waitFor(limitMs, TimeUnit.MILLISECONDS) }
                if (!finished) p.destroyForcibly()
                val code = runInterruptible(Dispatchers.IO) { p.waitFor() }
                // 通常、ランチャーが終了する前にプロセス ツリー全体がクリアされ、パイプラインが閉じられます。パイプラインを占有している子孫がまだある場合、待機は無限にはなりません。
                runInterruptible(Dispatchers.IO) { reader.join(READER_GRACE_MS) }
                // パイプを占有している子孫がまだあります。終了をオフにすると、読み取りスレッドが終了します。
                if (reader.isAlive) runCatching { p.inputStream.close() }
                val (bytes, dropped) = collector.snapshot()
                return ProcOutcome(code, bytes, dropped, !finished, (System.nanoTime() - begin) / 1_000_000)
            } catch (e: CancellationException) {
                abandon(p)
                throw e
            }
        }

        /** 最初に SIGTERM (ランチャーは受信後にプロセス ツリー全体をクリアします)、[DESTROY_GRACE_MS]、次に SIGKILL。 */
        private fun abandon(p: Process) {
            p.destroy()
            thread(isDaemon = true, name = "mika-sandbox-reaper") { if (!p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        }
    }
}

/** は最初にランチャーを SIGTERM します (プロセス ツリー全体がクリアされます)。プロセス内で終了しない場合は [DESTROY_GRACE_MS] が SIGKILL します。プロセスが終了するまでブロックされます。 */
private fun stopNow(p: Process) {
    runCatching { p.outputStream.close() }
    p.destroy()
    if (!p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
        p.destroyForcibly()
        p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
    }
}

/** [AndroidSandbox.openProcess] の常駐プロセス: シャットダウンするときは、最初にランチャーを SIGTERM (プロセス ツリー全体をクリアします)、次に SIGKILL を実行して、リースを返します。 */
private class SandboxProcessHandle(
    private val process: Process,
    private val lease: com.wangzan101.mika.core.workspace.SandboxTree.Lease,
) : SandboxProcess {
    override val stdin: java.io.OutputStream get() = process.outputStream
    override val stdout: java.io.InputStream get() = process.inputStream
    override val stderr: java.io.InputStream get() = process.errorStream

    override fun close() {
        runCatching { process.outputStream.close() }
        runCatching { process.destroy() }
        // リースはプロセス ツリーがクリアされた後にのみ返されます。まだワークスペースに書き込みを行っているプロセスはリセットと衝突してはなりません (リセットは最初に取り戻され、プロセスはここで待機せずに自動的に停止されます)。
        thread(isDaemon = true, name = "mika-sandbox-stdio-reaper") {
            try {
                if (!process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } finally {
                lease.close()
            }
        }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
}
