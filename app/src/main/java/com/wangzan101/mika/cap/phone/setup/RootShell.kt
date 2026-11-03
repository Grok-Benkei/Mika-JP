package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.shell.SuAnswer
import com.wangzan101.mika.core.shell.SuProbe
import com.wangzan101.mika.core.shell.HelperHandoff
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 端末の su の検出、許可要求、プロセス実行。root 管理アプリの種類ではなく、su ファイルと実際の UID で判定する。
 * 許可画面でユーザーの操作を待つ場合があるため、ブロッキング処理は IO スレッドから呼ぶ。
 */
object RootShell {
    /** su の絶対パス。見つからなければ null。 */
    fun su(): String? = SuProbe.find(System.getenv("PATH")) { runCatching { File(it).exists() }.getOrDefault(false) }

    /**
     * 指定した [uid]（root は0、shell は2000）で `id` を実行するよう su に一度要求し、最大 [timeoutMs] 待つ。
     * 初回の許可画面を操作する時間も含む。su が見つからなければ null。
     */
    fun ask(uid: Int, timeoutMs: Long): SuAnswer? {
        val su = su() ?: return null
        val args = if (uid == 0) SuProbe.rootArgs(su, "id") else SuProbe.shellArgs(su, "id")
        val p = runCatching { start(args) }.getOrElse { return SuAnswer.Denied }
        val out = collect(p.inputStream)
        val exited = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) p.destroyForcibly()
        val text = runCatching { out.get(1, TimeUnit.SECONDS) }.getOrDefault("")
        return SuProbe.answer(text, timedOut = !exited, expectUid = uid)
    }

    /** su プロセスを起動する。拒否理由も取得できるよう標準エラーを標準出力へ統合する。 */
    fun start(args: List<String>): Process = ProcessBuilder(args).redirectErrorStream(true).start()

    /**
     * `id` の行が得られるまで読み、shell の UID/GID の両方を検査する。実行前に終了した場合は null、[timeoutMs] 超過時は
     * java.util.concurrent.TimeoutException。タイムアウト時のプロセス終了は呼び出し側が行う。
     */
    fun readShellAnswer(p: Process, timeoutMs: Long): SuAnswer? {
        val f = onThread {
            val r = p.inputStream.bufferedReader()
            val identity = generateSequence { runCatching { r.readLine() }.getOrNull() }
                .firstOrNull { SuProbe.uid(it) != null }
            identity?.let { SuProbe.answer(it, timedOut = false, expectUid = HelperHandoff.SHELL_UID) }
        }
        return f.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** 共有スレッドプールを占有しないよう、専用スレッドでブロッキング読み取りを行う。 */
    fun <T> onThread(block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { f ->
        Thread({ runCatching(block).fold({ f.complete(it) }, { f.completeExceptionally(it) }) }, "mika-su").apply { isDaemon = true }.start()
    }

    /** 別スレッドで [stream] を最後まで読み、最大 [max] バイトを保持して超過分は破棄する。 */
    fun collect(stream: InputStream, max: Int = 16 * 1024): CompletableFuture<String> = onThread {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        runCatching {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (buf.size() < max) buf.write(chunk, 0, minOf(n, max - buf.size()))
            }
        }
        buf.toString(Charsets.UTF_8.name())
    }
}
