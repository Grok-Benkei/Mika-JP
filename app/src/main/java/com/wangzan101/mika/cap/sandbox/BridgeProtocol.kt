package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** は 1 行で応答します。 [close] が true の場合、応答後に接続が切断されます。 */
class BridgeReply(val json: String, val close: Boolean = false)

/**
 * サンドボックスの `mika` コマンドとミカの間のプロトコル: 1 行に 1 つの JSON リクエスト `{"token","method","params"}`、1 行に 1 つの JSON 応答。
 * モバイル メソッドは、現在のタスクのアクション エントリのみを転送します。コマンドプロセスは仮想画面に直接接続できません。
 */
object BridgeProtocol {
    const val MAX_REQUEST = 64 * 1024
    const val MAX_PROGRESS = 300

    sealed interface Line {
        class Text(val text: String) : Line
        data object TooLarge : Line
        data object End : Line
    }

    /** は 1 行を読み取ります (改行を除く)。これ以上の読み取りは [max] バイトを超えません。末尾に改行のない半行は完全なものとみなされます。 */
    fun readLine(input: InputStream, max: Int = MAX_REQUEST): Line {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return Line.End
            if (b == '\n'.code) return Line.Text(buf.toString(Charsets.UTF_8.name()))
            if (buf.size() >= max) return Line.TooLarge
            buf.write(b)
        }
    }

    /**
     * [progress] は、今回は電流制限によって破棄されたことを示す false を返します。 [share] はサンドボックスのパスとオプションの保存名を受け取り、失敗した場合は [SandboxError] をスローします。
     * [info] は、インターネットに接続されているかどうかなど、Mika のバージョンを示します。トークンが正しくない、リクエストが JSON オブジェクトではない、または大きすぎる場合は、エラーを返して切断します。
     */
    suspend fun handle(
        line: String, token: String,
        progress: (String) -> Boolean,
        share: suspend (String, String?) -> String,
        info: () -> JsonObject,
        phone: (suspend (String, JsonObject) -> JsonObject)? = null,
        /** 予期せぬ障害が発生した場合、ミカはログを記録することになります。エラー コードのみがサンドボックスに返されます。 */
        onError: (Throwable) -> Unit = {},
    ): BridgeReply {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_REQUEST) return error("too_large", close = true)
        val req = try { Json.parseToJsonElement(line) as? JsonObject } catch (e: SerializationException) { null }
            ?: return error("bad_request", close = true)
        val given = (req["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (given == null || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return error("bad_token", close = true)
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        fun str(k: String) = (params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when ((req["method"] as? JsonPrimitive)?.content) {
            "progress" -> {
                val text = str("text")?.let(::cleanProgress)?.takeIf { it.isNotEmpty() } ?: return error("bad_params")
                ok { put("shown", progress(text)) }
            }
            "share" -> {
                val path = str("path") ?: return error("bad_params")
                try {
                    val saved = share(path, str("name"))
                    ok { put("savedAs", saved) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SandboxError) {
                    error(e.code, e.message)
                } catch (e: Exception) {
                    onError(e)
                    error("share_failed")
                }
            }
            "info" -> BridgeReply(JsonObject(mapOf("ok" to JsonPrimitive(true)) + info()).toString())
            "phone" -> {
                val tool = str("tool")?.takeIf { it.length in 1..80 } ?: return error("bad_params")
                val args = params["args"] as? JsonObject ?: return error("bad_params")
                val handler = phone ?: return error("phone_unavailable")
                try {
                    BridgeReply(handler(tool, args).toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                    error("phone_failed")
                }
            }
            else -> error("unknown_method")
        }
    }

    /**
     * タスク カード上の単語の行: 制御文字と書式設定文字 (幅ゼロの文字、方向文字の書き換えなど。表示される単語と実際の単語が不一致になる可能性があります) を削除します。
     * および行/段落の区切り文字は、コード ポイントによって [MAX_PROGRESS] に切り捨てられます (サロゲート ペアの分割なし)。
     */
    internal fun cleanProgress(s: String): String {
        val out = StringBuilder()
        var count = 0
        var i = 0
        while (i < s.length && count < MAX_PROGRESS) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029) continue
            out.appendCodePoint(cp)
            count++
        }
        return out.toString().trim()
    }

    private fun ok(b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = BridgeReply(buildJsonObject { put("ok", true); b() }.toString())

    private fun error(code: String, message: String? = null, close: Boolean = false) = BridgeReply(buildJsonObject {
        put("ok", false); put("error", code); message?.let { put("message", it) }
    }.toString(), close)
}

/** は、[intervalMs] ごとに最大 1 回進行状況を見逃すことになり、超過分は破棄されます。 */
class ProgressGate(private val intervalMs: Long = 1_000, private val now: () -> Long = System::currentTimeMillis) {
    private var last = Long.MIN_VALUE

    @Synchronized fun offer(): Boolean {
        val t = now()
        if (last != Long.MIN_VALUE && t - last < intervalMs) return false
        last = t
        return true
    }
}
