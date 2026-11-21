package com.wangzan101.mika.harness

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import com.github.luben.zstd.ZstdInputStream
import com.wangzan101.mika.harness.tar.TarExtractor
import com.wangzan101.mika.harness.tar.FsTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Independent, opt-in Debian guest. Mika's Alpine sandbox remains untouched.
 * DSH's own Web UI supplies its plugin marketplace, MCP and session management.
 * The immutable release archives are pinned to SHA-256 digests.
 */
class HarnessRuntime private constructor(context: Context) {
    enum class Phase { NOT_INSTALLED, DOWNLOADING, UNPACKING, INSTALLED, STARTING, RUNNING, FAILED }
    data class Status(val phase: Phase, val description: String, val launchUrl: String? = null)

    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "deepseek-harness")
    private val downloads = File(app.cacheDir, "deepseek-harness")
    private val base = File(root, "base")
    private val harness = File(root, "harness")
    private val ready = File(root, "installed-" + RELEASE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private val _state = MutableStateFlow(
        if (installed()) Status(Phase.INSTALLED, "インストール済み")
        else Status(Phase.NOT_INSTALLED, "未インストール")
    )
    val state = _state.asStateFlow()
    @Volatile private var process: Process? = null

    fun installed(): Boolean = ready.isFile && base.isDirectory &&
        File(harness, "opt/dsh").isDirectory && File(harness, "usr/local/bin/dsh").isFile

    fun install() {
        if (_state.value.phase in setOf(Phase.DOWNLOADING, Phase.UNPACKING, Phase.STARTING, Phase.RUNNING)) return
        if (installed()) {
            _state.value = Status(Phase.INSTALLED, "インストール済み")
            return
        }
        if (!Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) {
            _state.value = Status(Phase.FAILED, "arm64-v8a端末のみサポート")
            return
        }
        keepAlive()
        _state.value = Status(Phase.DOWNLOADING, "ランタイムを準備しています")
        scope.launch {
            lock.withLock {
                if (installed()) {
                    _state.value = Status(Phase.INSTALLED, "インストール済み")
                    return@withLock
                }
                try {
                    installNow()
                    _state.value = Status(Phase.INSTALLED, "インストール完了。起動できます")
                } catch (e: Exception) {
                    _state.value = Status(Phase.FAILED, "導入失敗: " + (e.message ?: e.javaClass.simpleName))
                }
            }
            app.stopService(Intent(app, HarnessService::class.java))
        }
    }

    fun start() {
        if (_state.value.phase in setOf(Phase.STARTING, Phase.RUNNING, Phase.DOWNLOADING, Phase.UNPACKING)) return
        if (!installed()) {
            _state.value = Status(Phase.NOT_INSTALLED, "先にインストールしてください")
            return
        }
        keepAlive()
        _state.value = Status(Phase.STARTING, "DeepSeek Harnessを起動中")
        scope.launch {
            lock.withLock {
                if (process?.isAlive == true) return@withLock
                try {
                    val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                    prepareRun()
                    val proc = ProcessBuilder(launchCommand(port)).redirectErrorStream(true).apply {
                        environment().clear()
                        environment()["PROOT_LOADER"] = File(app.applicationInfo.nativeLibraryDir, "libproot_loader.so").path
                        environment()["PROOT_TMP_DIR"] = File(app.cacheDir, "deepseek-proot-tmp").apply { mkdirs() }.path
                    }.start()
                    process = proc
                    scope.launch { watchOutput(proc, port) }
                    scope.launch {
                        val code = proc.waitFor()
                        if (process === proc) {
                            process = null
                            _state.value = Status(Phase.FAILED, "Harnessが終了しました (code $code)")
                            app.stopService(Intent(app, HarnessService::class.java))
                        }
                    }
                } catch (e: Exception) {
                    _state.value = Status(Phase.FAILED, "起動失敗: " + (e.message ?: e.javaClass.simpleName))
                    app.stopService(Intent(app, HarnessService::class.java))
                }
            }
        }
    }

    /** Never persist or log the one-time URL bearing the browser launch token. */
    private fun watchOutput(proc: Process, expectedPort: Int) {
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val raw = line.substringAfter("dsh web: ", "").trim()
                if (raw.isEmpty()) return@forEach
                val url = runCatching { Uri.parse(raw) }.getOrNull() ?: return@forEach
                if (url.scheme == "http" && url.host in listOf("127.0.0.1", "localhost") &&
                    url.port == expectedPort && !url.getQueryParameter("token").isNullOrEmpty() &&
                    process === proc) {
                    _state.value = Status(Phase.RUNNING, "Harness起動中・プラグイン管理が利用できます", raw)
                }
            }
        }
    }

    fun stop() {
        stopProcess()
        app.stopService(Intent(app, HarnessService::class.java))
    }

    internal fun stopProcess() {
        val p = process
        process = null
        p?.destroy()
        if (p != null) scope.launch {
            delay(1_500)
            if (p.isAlive) p.destroyForcibly()
        }
        if (_state.value.phase in setOf(Phase.RUNNING, Phase.STARTING))
            _state.value = Status(Phase.INSTALLED, "停止しました")
    }

    private fun keepAlive() {
        app.startForegroundService(Intent(app, HarnessService::class.java))
    }

    private data class Layer(val name: String, val size: Long, val sha256: String)
    private val layers = listOf(
        Layer("dsh-runtime-base-arm64-v8a.tar.zst", 74_011_586L, "10c4611091c435cbf4f85850e5e67ff896adf6210b7d8931a6f98f0123d4f18a"),
        Layer("dsh-runtime-harness-arm64-v8a.tar.zst", 86_054_370L, "6866a922c6a7abf39f97aa106d3dc490ac735f4e350146d686c08533bad67252"),
    )

    private fun installNow() {
        root.mkdirs()
        downloads.mkdirs()
        if (StatFs(root.path).availableBytes < 1_400_000_000L)
            error("1.4GB以上の空き容量が必要です")
        val archives = layers.map { downloadVerified(it) }
        val stagedBase = File(root, ".base-stage")
        val stagedHarness = File(root, ".harness-stage")
        try {
            for ((index, dest) in listOf(stagedBase, stagedHarness).withIndex()) {
                FsTree.delete(AndroidFsOps, dest)
                check(dest.mkdirs()) { "展開ディレクトリを作成できません" }
                _state.value = Status(Phase.UNPACKING, "ランタイムを展開中 (" + (index + 1) + "/2)")
                ZstdInputStream(BufferedInputStream(FileInputStream(archives[index]))).use { stream ->
                    TarExtractor(AndroidFsOps).extract(stream, dest)
                }
            }
            check(File(stagedHarness, "opt/dsh").isDirectory)
            check(File(stagedHarness, "usr/local/bin/dsh").isFile)
            for (path in listOf("opt/dsh", "usr/local/bin")) {
                val place = File(stagedBase, path)
                check(place.isDirectory || place.mkdirs()) { "展開先を用意できません: $path" }
            }
            // A missing READY file means any earlier incomplete install is disposable.
            ready.delete()
            FsTree.delete(AndroidFsOps, base)
            FsTree.delete(AndroidFsOps, harness)
            check(stagedBase.renameTo(base)) { "baseの切替に失敗" }
            check(stagedHarness.renameTo(harness)) { "Harnessの切替に失敗" }
            File(root, "user-home").mkdirs()
            File(root, "workspaces").mkdirs()
            ready.writeText("DSH $RELEASE\nSHA-256 pinned layers\n")
        } finally {
            FsTree.delete(AndroidFsOps, stagedBase)
            FsTree.delete(AndroidFsOps, stagedHarness)
        }
    }

    private fun downloadVerified(layer: Layer): File {
        val dest = File(downloads, layer.name)
        if (dest.isFile && dest.length() == layer.size && digest(dest) == layer.sha256) return dest
        val part = File(downloads, layer.name + ".partial")
        part.delete()
        val url = "https://github.com/sorsama/dsh-mobile-runtime/releases/download/" + RELEASE + "/" + layer.name
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "ダウンロードHTTP " + response.code }
                val input = response.body?.byteStream() ?: error("空のダウンロード")
                val sha = MessageDigest.getInstance("SHA-256")
                FileOutputStream(part).use { output ->
                    input.use {
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        while (true) {
                            val n = it.read(buffer)
                            if (n < 0) break
                            bytes += n
                            check(bytes <= layer.size) { "ダウンロードが想定サイズを超えました" }
                            output.write(buffer, 0, n)
                            sha.update(buffer, 0, n)
                            _state.value = Status(Phase.DOWNLOADING, layer.name.substringBefore(".tar") + " " + (bytes * 100 / layer.size) + "%")
                        }
                        check(bytes == layer.size) { "ダウンロードが不完全です" }
                    }
                }
                check(hex(sha.digest()) == layer.sha256) { "SHA-256検証に失敗しました" }
            }
            check(part.renameTo(dest)) { "検証済みファイルの移動に失敗" }
        } finally {
            part.delete()
        }
        return dest
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        return hex(sha.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun prepareRun() {
        val run = File(root, "run").apply { mkdirs() }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val dns = cm?.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }.filter { it.matches(Regex("[0-9a-fA-F:.]+")) }.take(3)
        File(run, "resolv.conf").writeText(
            (if (dns.isEmpty()) listOf("1.1.1.1", "8.8.8.8") else dns).joinToString("") { "nameserver $it\n" }
        )
        File(root, "user-home").mkdirs()
        File(root, "workspaces").mkdirs()
        File(base, "tmp").mkdirs()
    }

    private fun launchCommand(port: Int): List<String> {
        val lib = File(app.applicationInfo.nativeLibraryDir, "libproot.so")
        check(lib.isFile) { "MikaのPRootが見つかりません" }
        val binds = listOf(
            "/dev", "/proc", "/sys", "/proc/self/fd:/dev/fd",
            File(base, "tmp").path + ":/dev/shm",
            File(base, "opt/dsh-runtime/proc/stat").path + ":/proc/stat",
            File(base, "opt/dsh-runtime/proc/loadavg").path + ":/proc/loadavg",
            File(base, "opt/dsh-runtime/proc/uptime").path + ":/proc/uptime",
            File(root, "run/resolv.conf").path + ":/etc/resolv.conf",
            File(root, "user-home").path + ":/root",
            File(root, "workspaces").path + ":/workspaces",
            File(harness, "opt/dsh").path + ":/opt/dsh",
            File(harness, "usr/local/bin/dsh").path + ":/usr/local/bin/dsh",
        )
        return listOf(lib.path, "--kill-on-exit", "--link2symlink", "-0", "-r", base.path) +
            binds.flatMap { listOf("-b", it) } +
            listOf("-w", "/root", "/usr/bin/env", "-i",
                "HOME=/root", "USER=root", "LOGNAME=root", "LANG=C.UTF-8", "TERM=dumb",
                "PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "DSH_RUNTIME_DEFAULT_PERMISSION=ask",
                "/bin/bash", "-c",
                "umask 022; mkdir -p /root/work && cd /root/work && exec /usr/local/bin/dsh web --no-open --port $port")
    }

    companion object {
        private const val RELEASE = "2026.11.15-1"
        @Volatile private var singleton: HarnessRuntime? = null
        fun get(context: Context): HarnessRuntime = singleton ?: synchronized(this) {
            singleton ?: HarnessRuntime(context).also { singleton = it }
        }
    }
}
