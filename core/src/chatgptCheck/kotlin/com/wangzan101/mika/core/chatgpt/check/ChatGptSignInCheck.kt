package com.wangzan101.mika.core.chatgpt.check

import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.ChatGptSession
import com.wangzan101.mika.core.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.chatgpt.SignInException
import com.wangzan101.mika.core.chatgpt.SignInFailure
import com.wangzan101.mika.core.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * デスクトップ上で「ChatGPT でログイン」するプロセス全体を説明します。アプリ内で同じログイン、列モデル、応答コードのセットを使用し、OpenAI の実際のサービスに直面します。
 * 幸いなことに、携帯電話を使用する前に、サーバー側に問題がないことを確認してください。使用方法については、docs/dev/chatgpt-signin-check.md を参照してください。
 *
 * ローカル ID と登録された client_id は ~/.mika-dev/chatgpt-check/ (ディレクトリ 0700) に存在し、アプリと同様に複数回の実行後に使用されます。
 * トークン、認証コード、および ID トークンは出力されません。デフォルトでは、これらはメモリ内にのみ存在し、`--keep` は同じディレクトリ内の session.json (0600) に書き込まれます。
 *
 * パラメータ: `--timeout=<秒>` コールバックを待つ時間 (デフォルトは 300)。 `--model=<slug>` はモデルを指定します。 `--keep` はログインを保存します。
 */
fun main(args: Array<String>) {
    val opts = args.associate { a -> a.removePrefix("--").let { it.substringBefore('=') to it.substringAfter('=', "") } }
    val timeoutSec = opts["timeout"]?.toLongOrNull()?.takeIf { it > 0 } ?: 300L
    val ok = runBlocking { Check(timeoutSec, opts["model"]?.takeIf { it.isNotBlank() }, "keep" in opts).run() }
    exitProcess(if (ok) 0 else 1)
}

private class Check(private val timeoutSec: Long, private val modelArg: String?, private val keep: Boolean) {
    private val dir = File(System.getProperty("user.home"), ".mika-dev/chatgpt-check")
    private val hostFile = File(dir, "host.json")
    private val sessionFile = File(dir, "session.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val auth = ChatGptAuth(http)
    private val results = mutableListOf<Pair<String, Boolean>>()

    suspend fun run(): Boolean {
        println("== Mika: ChatGPTでログイン · デスクトップ検査 ==")
        println("作業ディレクトリ:${dir.absolutePath}（トークンは出力しません; ${if (keep) "--keep: 終了時にログイン状態を保存" else "既定ではトークンを保存しません"}）")
        prepareDir()
        val (hostId, savedClient) = registration()
        println("この端末の登録:${if (savedClient == null) "初回のため、新しいclientを登録します" else "登録済みのclientを使用します"}")

        var session = signIn(hostId, savedClient) ?: return summary()
        val tokens = ChatGptTokens(auth, { session }, { session = it })

        val model = listModels(session.accessToken)
        if (model != null) {
            val caps = Capabilities(
                toolCalls = true, vision = model.vision ?: false,
                contextTokens = model.contextTokens ?: 128_000, reasoning = model.reasoning ?: false,
            )
            val responses = ResponsesModel(ChatGptAuth.API_BASE, tokens, model.id, caps, http, chatGptPlan = true)
            simpleCall(responses)
            toolRoundTrip(responses)
        }
        refresh(tokens) { session }
        if (keep) save(session)
        return summary()
    }

    // ---------- 1. ローカル識別 ----------

    private fun prepareDir() {
        dir.mkdirs()
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }

    private fun registration(): Pair<String, String?> {
        val saved = runCatching { json.parseToJsonElement(hostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writePrivate(hostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }

    private fun rememberClient(hostId: String, clientId: String) {
        writePrivate(hostFile, buildJsonObject { put("hostId", hostId); put("clientId", clientId) }.toString())
        step("clientを登録（client_idを${hostFile.name}に保存済み）", true)
    }

    // ---------- 2-3.ログイン ----------

    private suspend fun signIn(hostId: String, clientId: String?): ChatGptSession? {
        // は、ログイン プロンプトとして最後の電子メール アドレスのみを使用します。 ID トークンがないと、印刷された認証アドレスにはトークンが含まれません。
        val previous = if (sessionFile.isFile) ChatGptSession.fromJson(sessionFile.readText())?.signedOut() else null
        return try {
            val s = signInWithChatGpt(
                auth, hostId, clientId, previous,
                openBrowser = { url -> openBrowser(url) },
                onRegistered = { rememberClient(hostId, it) },
                page = ::callbackPage,
                timeoutMs = timeoutSec * 1000,
            )
            step("コールバックを受信（stateの照合成功）", true)
            step("認可コードをトークンへ交換", true)
            step("IDトークンを検証（署名、発行者、対象、有効期限、nonce）", true)
            step("${ChatGptAuth.PLAN_SCOPE} の許可を取得", true)
            println("    付与された権限:${s.scopes.joinToString(" ")}")
            println("    アカウント:${s.email.ifEmpty { "（IDトークンにメールアドレスがありません）" }}")
            println("    アクセストークンの有効期間: 約 ${(s.expiresAtMs - System.currentTimeMillis()) / 1000} 秒; リフレッシュトークン:${if (s.refreshToken.isNotEmpty()) "あり" else "なし"}")
            s
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInException) {
            step(signInStep(e.reason), false, explain(e))
            null
        } catch (e: Exception) {
            step("ログイン", false, "${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun openBrowser(url: String) {
        println("認可URL（トークンは含みません）:")
        println("    $url")
        val opened = runCatching {
            System.getProperty("os.name").lowercase().contains("mac") &&
                ProcessBuilder("open", url).inheritIO().start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
        step(if (opened) "既定のブラウザーで認可ページを開きました" else "ブラウザーを自動で開けませんでした。上記のURLを手動で開いてください", opened, null, record = false)
        println("コールバックを待機中…（最大 $timeoutSec 秒; Ctrl+Cで中止）")
    }

    private fun callbackPage(failure: SignInException?): String {
        val text = if (failure != null) "ログインに失敗しました（${failure.reason}）。端末のコンソールで原因を確認してください。" else "ログインが完了しました。このページを閉じ、端末のコンソールで結果を確認してください。"
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Mika</title></head>" +
            "<body style=\"font-family:system-ui;margin:3em\"><p>$text</p></body></html>"
    }

    private fun signInStep(r: SignInFailure): String = when (r) {
        SignInFailure.Denied, SignInFailure.StateMismatch, SignInFailure.ClientMismatch, SignInFailure.NoClientId -> "コールバックを照合"
        SignInFailure.TimedOut -> "コールバックを待機"
        SignInFailure.Exchange, SignInFailure.CodeExpired, SignInFailure.Region, SignInFailure.Blocked -> "認可コードをトークンへ交換"
        SignInFailure.IdToken -> "IDトークンを検証"
        SignInFailure.NoPlanScope -> "${ChatGptAuth.PLAN_SCOPE} の許可"
        SignInFailure.Network -> "OpenAIのログインサービスへ接続"
    }

    // ---------- 4. コラムモデル ----------

    private suspend fun listModels(accessToken: String): ListedModel? {
        val listed = when (val r = ModelCatalog(http).openAiCompat(ChatGptAuth.API_BASE, accessToken)) {
            is ModelListResult.Ok -> r.models
            is ModelListResult.Failed -> {
                step("モデル一覧を取得（GET /v1/models）", false, r.message)
                return null
            }
        }
        step("モデル一覧を取得（GET /v1/models）:${listed.size} 件", listed.isNotEmpty(), if (listed.isEmpty()) "一覧が空です" else null)
        listed.forEach { m ->
            val flags = listOfNotNull(
                m.contextTokens?.let { "コンテキスト $it" }, m.vision?.let { if (it) "画像対応" else "画像非対応" },
                m.reasoning?.takeIf { it }?.let { "推論" }, m.toolCalls?.let { if (it) "ツール対応" else "ツール非対応" },
            )
            println("    ${m.id}${m.displayName.takeIf { it.isNotEmpty() && it != m.id }?.let { "  「$it」" }.orEmpty()}" +
                if (flags.isEmpty()) "" else "  (${flags.joinToString(",")})")
        }
        val chosen = if (modelArg != null) {
            listed.firstOrNull { it.id == modelArg } ?: ListedModel(modelArg).also { println("    注意: --model=$modelArg は一覧にありませんが、指定したモデルを検査します") }
        } else {
            // 推論モデルを優先します。ツールを呼び出すステップでは、暗号的思考の再生も検証する必要があります。
            listed.firstOrNull { it.usable && it.reasoning == true && it.toolCalls != false } ?: listed.firstOrNull { it.usable }
        }
        chosen?.let { println("    使用モデル:${it.id}") }
        return chosen
    }

    // ---------- 5. 簡単な通話 ----------

    private suspend fun simpleCall(model: ResponsesModel) {
        val name = "Responses呼び出し"
        try {
            val reply = model.complete("", listOf(Message(Role.User, listOf(Part.Text("Reply with the single word: pong")), Author.User)), emptyList())
            val text = reply.text()
            step(name, text.isNotBlank(), if (text.isBlank()) "回答が空です" else null)
            println("    回答:${text.trim().take(200)}")
            println("    ${usage(reply.usage)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step(name, false, explain(e))
        }
    }

    // ---------- 6. ツール呼び出し往復 ----------

    private suspend fun toolRoundTrip(model: ResponsesModel) {
        val tool = ToolSpec(
            "get_time", "Returns the current local time as an ISO-8601 string.",
            """{"type":"object","properties":{},"additionalProperties":false}""",
        )
        val system = "You can call tools. When asked for the time, call get_time, then answer with the time it returned."
        val history = mutableListOf(Message(Role.User, listOf(Part.Text("What time is it now? Use the get_time tool.")), Author.User))
        try {
            val first = model.complete(system, history, listOf(tool))
            val call = first.parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (call == null) {
                step("ツール呼び出し: モデルがget_timeを呼ぶ", false, "モデルがツールを呼ばず直接回答しました:${first.text().take(120)}")
                return
            }
            step("ツール呼び出し: モデルが${call.name}を呼び出しました（引数 ${call.argsJson.take(80)}）", call.name == tool.name)
            println("    暗号化された推論データ:${if (call.opaque != null) "あり。次の呼び出しでそのまま再送します（store:false）" else "なし（モデルが返していないか、推論モデルではありません）"}")
            println("    ${usage(first.usage)}")
            val now = OffsetDateTime.now().withNano(0).toString()
            history += Message(Role.Assistant, first.parts, Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult(call.id, """{"time":"$now"}""", isError = false)), Author.Mika)
            val second = model.complete(system, history, listOf(tool))
            val text = second.text()
            val more = second.parts.any { it is Part.ToolCall }
            step("ツール呼び出し: function_call_outputを受信後に回答", text.isNotBlank() && !more,
                when { more -> "モデルが再びツールを呼びました"; text.isBlank() -> "回答が空です"; else -> null })
            println("    回答:${text.trim().take(300)}")
            println("    ${usage(second.usage)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step("ツール呼び出しの往復", false, explain(e))
        }
    }

    // ---------- 7. 新しいトークンに置き換えます ----------

    private suspend fun refresh(tokens: ChatGptTokens, current: () -> ChatGptSession) {
        val before = current()
        try {
            tokens.get(fresh = true)
            val after = current()
            step("リフレッシュトークンで新しいトークンを取得", true)
            println("    アクセストークン${if (after.accessToken != before.accessToken) "更新済み" else "変更なし"};" +
                "リフレッシュトークン${if (after.refreshToken != before.refreshToken) "ローテーション済み" else "変更なし"};" +
                "新しい有効期間は約 ${(after.expiresAtMs - System.currentTimeMillis()) / 1000} 秒")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step("リフレッシュトークンで新しいトークンを取得", false, explain(e))
        }
    }

    private fun save(session: ChatGptSession) {
        writePrivate(sessionFile, session.toJson())
        println("ログイン状態を${sessionFile.absolutePath}に保存しました（0600）")
    }

    // ---------- 出力 ----------

    private fun step(name: String, ok: Boolean, why: String? = null, record: Boolean = true) {
        println("${if (ok) "✓" else "✗"} $name")
        if (!ok && why != null) println("    原因:$why")
        if (record) results += name to ok
    }

    private fun summary(): Boolean {
        val failed = results.filterNot { it.second }
        println()
        println("== 結果: ${results.size - failed.size}/${results.size} 項目に合格 ==")
        failed.forEach { println("✗ ${it.first}") }
        return failed.isEmpty() && results.isNotEmpty()
    }

    private fun ModelReply.text() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    private fun usage(u: Usage?) = u?.let { "使用量: 入力 ${it.inputTokens}（キャッシュ ${it.cachedInputTokens}）、出力 ${it.outputTokens}" } ?: "使用量: サーバーから情報がありません"

    private fun writePrivate(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}

/** 既知のエラーを言葉で表現します。 */
private fun explain(e: Throwable): String = when (e) {
    is SignInException -> when (e.reason) {
        SignInFailure.Denied -> "認可ページがエラーを返しました（${e.message?.substringAfter(": ", "")}）:" +
            "access_deniedは認可が拒否されたことを示します。「必要な権限を利用できません」と表示された場合は、契約プランと連携権限を確認してください"
        SignInFailure.StateMismatch -> "コールバックのstateが今回のリクエストと一致しません（今回のログインへのコールバックではありません）"
        SignInFailure.ClientMismatch -> "コールバックのclient_idが保存済みの値と一致しません。~/.mika-dev/chatgpt-check/host.jsonのclientIdを削除してからやり直してください"
        SignInFailure.NoClientId -> "初回登録ですが、コールバックにclient_idがありません"
        SignInFailure.TimedOut -> "コールバックの待機がタイムアウトしました。ブラウザーでログインが完了していないか、127.0.0.1へ戻っていません"
        SignInFailure.Exchange -> "トークン交換が拒否されました（${e.message?.substringAfter(": ", "")}）。redirect_uriまたはPKCEの不一致などが考えられます"
        SignInFailure.CodeExpired -> "トークン交換が拒否されました（${e.message?.substringAfter(": ", "")}）。認可コードが期限切れ、使用済み、または今回の認可と一致しません"
        SignInFailure.Region -> "トークン交換が拒否されました（${e.message?.substringAfter(": ", "")}）。現在のネットワークの地域はサポートされていません"
        SignInFailure.Blocked -> "トークン交換が拒否されました（${e.message?.substringAfter(": ", "")}）。JSONではなくWebページが返され、リクエストが保護機能に遮断されました"
        SignInFailure.IdToken -> "IDトークンの検証に失敗しました（${e.message?.substringAfter(": ", "")}）"
        SignInFailure.NoPlanScope -> "${ChatGptAuth.PLAN_SCOPE} が付与されていません。契約プランと連携権限を確認し、認可時に必要な権限を許可したか確認してください"
        SignInFailure.Network -> "OpenAIのログインサービスへ接続できません（${e.message?.substringAfter(": ", "")}）"
    }
    is ModelError.ChatGptNotEligible -> "subscription_sharing_user_not_eligible: このアカウントでは他のアプリでChatGPTの利用枠を使えません（プラン、地域、アカウントのポリシーによる制限）" +
        e.detail.takeIf { it.isNotEmpty() }?.let { "; サーバーの原文: $it" }.orEmpty()
    is ModelError.ChatGptLimitReached -> "usage_limit_exceeded: 他のアプリで使えるChatGPTプランの利用枠が上限に達しました。回復後に再試行してください（使用量: ${ChatGptAuth.USAGE_URL}）"
    is ModelError.SignInExpired -> "リフレッシュトークンを利用できません（無効化、期限切れ、再利用）。再ログインが必要です"
    is ModelError.Auth -> "トークンが拒否されたか、クライアント設定が正しくありません（401/403、client_idが認識されない等）"
    is ModelError.ModelNotFound -> "model_not_found: このアカウントではこのモデルを利用できません。別のモデルを指定してください（--model=…）"
    is ModelError.ContextTooLong -> "context_length_exceeded"
    is ModelError.Network -> "ネットワーク接続に失敗:${e.cause?.message.orEmpty()}"
    is ModelError.Server -> "サーバーエラー（HTTP ${e.status}）。しばらくしてから再試行してください"
    is ModelError.BadResponse -> if (e.message.orEmpty().contains("服务端不支持请求里的")) {
        "unsupported_capability:${e.message?.substringAfter("服务端不支持请求里的 ")?.let { "リクエストの `$it` はサポートされていません" }}"
    } else e.message.orEmpty()
    else -> "${e::class.simpleName}: ${e.message}"
}
