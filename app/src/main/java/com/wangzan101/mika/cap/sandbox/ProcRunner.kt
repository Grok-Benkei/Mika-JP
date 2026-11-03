package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.workspace.SandboxPaths
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/** は、プロセス実行の元の結果です。 [timedOut] は、Kotlin 側の制限時間が経過したことを意味するだけです。 */
internal class ProcOutcome(val exitCode: Int, val output: ByteArray, val droppedBytes: Long, val timedOut: Boolean, val durationMs: Long)

/** ランチャー + PRoot コマンド ラインおよび環境変数。 */
object ProcRunner {
    const val OUTPUT_LIMIT = 1 shl 20
    const val READ_CHUNK = 64 * 1024
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    /** プロキシを使用する場合、ローカル エリア ネットワーク アドレスは引き続き直接接続されます (Mika 自身の接続と同じです。[LocalHosts] を参照)。 */
    const val NO_PROXY = LocalHosts.NO_PROXY

    /**
     * [cwd]: `/workspace` の下の相対パス、空の文字列は `/workspace` です。 [standIns]: `files/sandbox/proc/` のアバター ファイル名、
     * はそれぞれ /proc/<name> にバインドされています。 /dev は、[ProotDevices] でリストされたデバイスのみをバインドします (/dev 全体をバインドするわけではありません。そこにある手順を参照してください)。
     */
    fun argv(
        dirs: SandboxDirs, workspace: File, cwd: String, timeoutSec: Int, network: Boolean, standIns: List<String>, command: List<String>,
        devices: List<String> = ProotDevices.bindArgs(),
    ): List<String> = listOf(
        dirs.lib("libmikarun.so").path, "--timeout", timeoutSec.toString(), "--net", if (network) "on" else "off", "--",
        dirs.lib("libproot.so").path, "--kill-on-exit", "--link2symlink", "-0", "-r", dirs.rootfs.path,
    ) + devices + listOf("-b", "/proc", "-b", "/sys") +
        standIns.flatMap { listOf("-b", "${File(dirs.procStandIns, it).path}:/proc/$it") } +
        listOf(
            "-b", "${workspace.path}:${SandboxPaths.WORKSPACE}",
            "-b", "${dirs.skills.path}:${SandboxPaths.SKILLS}",
            "-w", if (cwd.isEmpty()) SandboxPaths.WORKSPACE else "${SandboxPaths.WORKSPACE}/$cwd",
        ) + command

    /** 環境が最初にクリアされ、これらのみが与えられます。ブリッジが開いていない場合、MIKA_BRIDGE / MIKA_TOKEN は付与されません。 */
    fun env(
        dirs: SandboxDirs, bridgeName: String?, token: String?, conversationId: String,
        /** コンテナーのネットワーク プロキシ。空の文字列は、それを使用しないことを意味します (NetProxyStore を参照)。インターネットに接続している場合、呼び出し元は空の文字列を与えることはできません。 */
        proxy: String = "",
    ): Map<String, String> = buildMap {
        put("PROOT_LOADER", dirs.lib("libproot_loader.so").path)
        put("PROOT_TMP_DIR", dirs.tmp.path)
        put("HOME", "/root")
        put("PATH", PATH)
        put("LANG", "C.UTF-8")
        put("TMPDIR", "/tmp")
        // は、電話機が起動されるたびに、電話機の現在のタイム ゾーンを読み取ります。 Alpine の libc は Android システムのタイムゾーンを読み取りません。
        put("TZ", timezone())
        bridgeName?.let { put("MIKA_BRIDGE", it) }
        token?.let { put("MIKA_TOKEN", it) }
        put("MIKA_CONVERSATION", conversationId)
        // エージェントはコンテナ内のコマンドにのみ使用されます。一般的なツールはこれらの変数を認識します (大文字のみを認識するものもあります)。ローカルアドレスはエージェントを経由しません
        if (proxy.isNotBlank()) {
            for (name in listOf("http_proxy", "https_proxy", "all_proxy")) {
                put(name, proxy)
                put(name.uppercase(), proxy)
            }
            put("no_proxy", NO_PROXY)
            put("NO_PROXY", NO_PROXY)
        }
    }

    /**
     * 携帯電話のタイムゾーンはサンドボックス内では TZ と表記されます。地域名 (アジア/上海など) はそのまま与えられ、実行環境に付属の tzdata によって解析され、過去のオフセットと夏時間は保持されます。
     * 固定オフセット (GMT+08:00 など) は、POSIX の TZ の符号が逆のため、そのまま与えることはできません。全体的には、Etc/GMT-8 などの tzdata 名に置き換えます。
     * と残りは POSIX 形式 `<+0545>-05:45` で書かれます。タイムゾーンが読めない場合はUTCが使用されます。
     */
    internal fun timezone(zone: () -> ZoneId = ZoneId::systemDefault): String {
        val z = runCatching(zone).getOrNull() ?: return "UTC"
        val fixed = z as? ZoneOffset ?: z.id.takeIf { it.startsWith("GMT") || it.startsWith("UT") }?.let { z.normalized() as? ZoneOffset }
        val s = fixed?.totalSeconds ?: return z.id
        if (s == 0) return "UTC"
        if (s % 3600 == 0 && s / 3600 in -12..14) return "Etc/GMT" + (if (s > 0) "-" else "+") + abs(s / 3600)
        val a = abs(s)
        val hm = String.format(Locale.ROOT, "%02d%02d", a / 3600, a / 60 % 60)
        val posix = String.format(Locale.ROOT, "%02d:%02d", a / 3600, a / 60 % 60) +
            (if (a % 60 != 0) String.format(Locale.ROOT, ":%02d", a % 60) else "")
        return if (s > 0) "<+$hm>-$posix" else "<-$hm>$posix"
    }

    /** はストリームの終わりを読み取り、結果は [OutputCollector] に表示されます。 */
    internal fun collect(input: InputStream, limit: Int = OUTPUT_LIMIT): Pair<ByteArray, Long> =
        OutputCollector(limit).apply { drain(input) }.snapshot()
}

/** はストリームを最後まで読み取ります。最大 [limit] バイトが残り、それ以上は読み取られて破棄され、カウントされます (コマンドが完了していない場合、コマンドは書き込み出力で停止します)。読み取り中にスナップショットを撮ることもできます。 /dev は */
internal class OutputCollector(private val limit: Int = ProcRunner.OUTPUT_LIMIT) {
    private val kept = java.io.ByteArrayOutputStream(minOf(limit, ProcRunner.READ_CHUNK))
    private var dropped = 0L

    fun drain(input: InputStream) {
        val buf = ByteArray(ProcRunner.READ_CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            synchronized(this) {
                val room = limit - kept.size()
                if (room > 0) kept.write(buf, 0, minOf(n, room))
                if (n > room) dropped += n - maxOf(room, 0)
            }
        }
    }

    @Synchronized fun snapshot(): Pair<ByteArray, Long> = kept.toByteArray() to dropped
}
