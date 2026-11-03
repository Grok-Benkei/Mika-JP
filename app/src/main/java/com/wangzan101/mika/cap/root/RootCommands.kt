package com.wangzan101.mika.cap.root

import android.os.SystemClock
import android.util.Base64
import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.root.RootRun
import com.wangzan101.mika.core.shell.SuAnswer
import com.wangzan101.mika.core.shell.SuProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `su -c` で root として実行するエージェント用のコマンド。標準出力と標準エラーは別々に各1 MBまで保持する。
 * コマンドを base64 にし、root 側で復号して `sh -c` に渡す。引用符や改行を保持し、外側の sh の構文を壊さない。
 * 内側の sh の構文エラーと su の拒否は、実行開始を示すマーカーの有無で区別する。
 * toybox の timeout があれば root 側でも時間を制限し、なければ呼び出し側で su を終了する。
 * [allowed] で現在の許可を再確認する。[su] は su の絶対パス（未検出なら null）、
 * [refused] は拒否状態を設定へ通知する。null は su の消失を示す。
 */
class RootCommands(
    private val su: () -> String?,
    private val allowed: () -> Boolean,
    private val refused: (SuAnswer?) -> Unit,
) : RootPort {
    override suspend fun run(command: String, timeoutMs: Long): RootRun = runInterruptible(Dispatchers.IO) {
        // タスク開始後にスイッチをオフにした場合も、新しい su プロセスを起動しない。
        if (!allowed()) return@runInterruptible RootRun.Denied
        val su = su() ?: return@runInterruptible RootRun.Unavailable.also { refused(null) }
        val started = SystemClock.elapsedRealtime()
        val p = runCatching { ProcessBuilder(SuProbe.rootArgs(su, script(command, timeoutMs))).start() }
            .getOrElse { return@runInterruptible RootRun.Denied.also { refused(SuAnswer.Denied) } }
        runCatching { p.outputStream.close() }
        val out = onThread { drain(p.inputStream) }
        val err = onThread { drain(p.errorStream) }
        // root 側の timeout を先に働かせ、su 自体が許可画面などで停止したときだけ呼び出し側から終了する。
        val exited = try {
            p.waitFor(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            p.destroyForcibly()
            throw e
        }
        if (!exited) p.destroyForcibly()
        val duration = SystemClock.elapsedRealtime() - started
        val (stdout, outDropped) = runCatching { out.get(2, TimeUnit.SECONDS) }.getOrDefault("" to 0L)
        val (stderr, errDropped) = runCatching { err.get(2, TimeUnit.SECONDS) }.getOrDefault("" to 0L)
        if (!stdout.startsWith(MARK)) {
            // 実行開始のマーカーがなければ、su はコマンドを実行していない。
            val a = if (!exited) SuAnswer.NoAnswer else SuAnswer.Denied
            refused(a)
            return@runInterruptible if (a == SuAnswer.NoAnswer) RootRun.NoAnswer else RootRun.Denied
        }
        val exit = if (exited) p.exitValue() else -1
        // timeout -s KILL で終了した場合の終了コードは137または124。
        val timedOut = !exited || (exit == 137 || exit == 124) && duration >= timeoutMs - 500
        RootRun.Done(exit, timedOut, stdout.removePrefix(MARK).removePrefix("\n"), stderr, outDropped + errDropped, duration)
    }

    private fun script(command: String, timeoutMs: Long): String {
        val b64 = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val sec = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        return "echo $MARK; c=\"\$(echo $b64 | base64 -d)\"; " +
            "if command -v timeout >/dev/null 2>&1; then exec timeout -s KILL $sec sh -c \"\$c\"; else exec sh -c \"\$c\"; fi"
    }

    /** 共有スレッドプールを占有しないよう、専用スレッドで読む。 */
    private fun <T> onThread(block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { f ->
        Thread({ runCatching(block).fold({ f.complete(it) }, { f.completeExceptionally(it) }) }, "mika-root").apply { isDaemon = true }.start()
    }

    /** ストリームを最後まで読み、[MAX_BYTES] まで保持し、超過分はバイト数を数えて破棄する。 */
    private fun drain(stream: InputStream): Pair<String, Long> {
        val buf = ByteArrayOutputStream()
        var dropped = 0L
        val chunk = ByteArray(8192)
        runCatching {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                val keep = minOf(n, MAX_BYTES - buf.size())
                if (keep > 0) buf.write(chunk, 0, keep)
                dropped += n - keep
            }
        }
        return buf.toString(Charsets.UTF_8.name()) to dropped
    }

    private companion object {
        const val MARK = "__mika_root_ok__"
        const val MAX_BYTES = 1 shl 20
        const val GRACE_MS = 5_000L
    }
}
