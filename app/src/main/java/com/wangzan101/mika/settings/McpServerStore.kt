package com.wangzan101.mika.settings

import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.cap.mcp.HttpMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.LegacySseMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpOAuth
import com.wangzan101.mika.core.cap.mcp.McpOAuthException
import com.wangzan101.mika.core.cap.mcp.McpOAuthTokens
import com.wangzan101.mika.core.cap.mcp.McpStdioLauncher
import com.wangzan101.mika.core.cap.mcp.StdioMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.parseStdioCommand
import com.wangzan101.mika.core.cap.mcp.McpClientException
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.McpTrust
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.oauth.LoopbackCallback
import com.wangzan101.mika.core.resource.ResourceScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 外部 MCP サーバーの構成。トークンはこのオブジェクトには含まれず、個別に保存され、暗号化されます。 */
@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** restricted | trust-annotations */
    val trust: String = "restricted",
    val timeoutSec: Int = 60,
    val previewChars: Int = 4000,
    val maxCostUnits: Long? = null,
    val allowLocalHttp: Boolean = false,
    /** ストリーミング可能-http | http-sse(2024-11-05 旧バージョン) | stdio (サンドボックス内のローカルプロセス) */
    val transport: String = "streamable-http",
    /** stdio コマンドライン。シェルを経由せずに、argv を押して直接実行します。 */
    val command: String = "",
    /** トークン | oauth (トークンは手動ベアラー/カスタム ヘッダーです。oauth は OAuth 2.1 ログインを使用します) */
    val auth: String = "token",
    /** はこれらのツールのみを公開します。 null はすべてを意味します。 */
    val allowedTools: List<String>? = null,
    /** 暗号化されていないカスタム リクエスト ヘッダー。認証はファイルとして保存されます。 */
    val headers: Map<String, String> = emptyMap(),
)

data class McpHealth(val ok: Boolean, val message: String?, val tools: List<String>, val atMs: Long)

data class McpConnections(
    val connections: List<McpConnection>,
    val close: () -> Unit,
    val failures: List<Pair<String, String>> = emptyList(),
)

data class McpTestResult(val serverName: String?, val instructions: String?, val tools: List<String>)

/**
 * 外部 MCP サーバー設定: 構成は `files/settings/mcp-servers.json` として保存され、トークンは暗号化されて [KeyCipher] として保存されます。
 * [connectionsFor] タスクの開始時に有効な項目をすべて接続します (セクション全体には合計時間制限があります)。障害やタイムアウトは他のタスクや他のサーバーには影響しません。
 */
class McpServerStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val http: OkHttpClient = NetProxy.shared.client(
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS),
    ),
    /** stdio サーバーはサンドボックスでのみ起動できます。 null の場合、stdio 構成は使用不可として報告されます。 */
    private val stdio: McpStdioLauncher? = null,
) {
    private val file = File(dir, "mcp-servers.json")
    private val secretsFile = File(dir, "mcp-server-secrets.bin")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()
    private val oauth = McpOAuth(http)
    private val tokenJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _health = MutableStateFlow<Map<String, McpHealth>>(emptyMap())
    /** 最新の接続/テストの結果。プロセス内のみであり、ディスクにはドロップされません。 */
    val health: StateFlow<Map<String, McpHealth>> = _health.asStateFlow()

    @Synchronized fun add(name: String, url: String, token: String? = null): Result<McpServerConfig> =
        add(McpServerConfig(name = name.trim(), url = url.trim()), token)

    @Synchronized fun add(config: McpServerConfig, token: String? = null): Result<McpServerConfig> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val next = _servers.value + config
        saveConfigs(next)
        _servers.value = next
        if (!token.isNullOrBlank()) setToken(config.id, token)
        return Result.success(config)
    }

    @Synchronized fun update(config: McpServerConfig): Result<McpServerConfig> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val next = _servers.value.map { if (it.id == config.id) config else it }
        saveConfigs(next)
        _servers.value = next
        return Result.success(config)
    }

    @Synchronized fun remove(id: String) {
        val next = _servers.value.filterNot { it.id == id }
        saveConfigs(next)
        _servers.value = next
        val secrets = loadSecrets().toMutableMap()
        val hadToken = secrets.remove(id) != null
        val hadOAuth = secrets.remove("oauth:$id") != null
        if (hadToken || hadOAuth) saveSecrets(secrets)
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val next = _servers.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveConfigs(next)
        _servers.value = next
    }

    @Synchronized fun token(id: String): String? = loadSecrets()[id]

    @Synchronized fun setToken(id: String, token: String?) {
        val secrets = loadSecrets().toMutableMap()
        if (token.isNullOrBlank()) secrets.remove(id) else secrets[id] = token.trim()
        saveSecrets(secrets)
    }

    fun validate(config: McpServerConfig): String? {
        if (config.name.isBlank()) return "name is required"
        if (config.transport == "stdio") {
            if (config.command.isBlank()) return "command is required"
            if (config.command.length > 4000) return "command is too long"
            if (config.command.any { it == '\n' || it == '\u0000' }) return "command must be one line"
            return null
        }
        val url = config.url.toHttpUrlOrNull() ?: return "invalid URL"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "URL must not contain user info"
        if (isBlockedHost(url.host)) return "this host is not allowed"
        if (url.scheme == "http") {
            if (!config.allowLocalHttp) return "HTTP is only allowed for local/private servers after enabling it"
            if (!isLocalHost(url.host)) return "HTTP is only allowed for local/private hosts"
        } else if (url.scheme != "https") {
            return "URL must be https (or http for a local server)"
        }
        return null
    }

    suspend fun test(config: McpServerConfig, workspace: String = SETTINGS_WORKSPACE): Result<McpTestResult> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val headers = try {
            authHeaders(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _health.update { it + (config.id to healthFailure(e)) }
            return Result.failure(e)
        }
        val endpoint = open(config, headers, workspace).getOrElse { e ->
            _health.update { it + (config.id to healthFailure(e)) }
            return Result.failure(e)
        }
        return try {
            val name = when (endpoint) {
                is HttpMcpEndpoint -> endpoint.serverName
                is LegacySseMcpEndpoint -> endpoint.serverName
                is StdioMcpEndpoint -> endpoint.serverName
                else -> null
            }
            val instructions = when (endpoint) {
                is HttpMcpEndpoint -> endpoint.instructions
                is LegacySseMcpEndpoint -> endpoint.instructions
                is StdioMcpEndpoint -> endpoint.instructions
                else -> null
            }
            val result = McpTestResult(name, instructions, endpoint.tools().map { it.name })
            _health.update { it + (config.id to McpHealth(true, null, result.tools, System.currentTimeMillis())) }
            Result.success(result)
        } finally {
            (endpoint as? Closeable)?.close()
        }
    }

    /** 有効なすべてのサーバーのステータスを (並行して) 更新します。 */
    suspend fun testAll() = coroutineScope {
        _servers.value.filter { it.enabled }.map { config -> async { test(config) } }.awaitAll()
    }

    /**
     * はタスクの開始時に使用されます。[owner] (タスクの [ResourceScope]) に接続され、タスクが終了してスコープが閉じられると一緒にオフになります。 接続の途中でキャンセルされた場合、または接続されたばかりでまだハンドバックされていない場合でも、
     * は見逃されません (それに応じて、stdio サーバーのプロセスとサンドボックスのリースが解放されます)。
     */
    suspend fun connectionsFor(
        owner: ResourceScope,
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore? = null,
        workspace: String = SETTINGS_WORKSPACE,
        budgetMs: Long = CONNECT_BUDGET_MS,
    ): McpConnections = owner.acquire(Dispatchers.IO, { c: McpConnections -> c.close() }) { connections(resultStore, workspace, budgetMs) }

    /**
     * は、すべての有効な項目を並列に接続します。単一の失敗は記録されるだけで、スローされません。セクション全体の最大待機数は [budgetMs] です。最後に接続できなかった場合、今回はピックアップしません。
     * は失敗として記録され (ヘルス ステータスにタイムアウトが示されます)、タスクの開始は遅れません。すでに接続されているものは通常どおり使用できます。キャンセルすると、接続されているものも閉じられ、再度スローされます。
     */
    suspend fun connections(
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore? = null,
        workspace: String = SETTINGS_WORKSPACE,
        budgetMs: Long = CONNECT_BUDGET_MS,
    ): McpConnections {
        val enabled = _servers.value.filter { it.enabled }
        val done = ConcurrentHashMap<String, Result<McpConnection>>()
        try {
            withTimeoutOrNull(budgetMs) {
                coroutineScope {
                    enabled.map { config -> async { done[config.id] = attempt(config, resultStore, workspace) } }.awaitAll()
                }
            }
        } catch (e: Throwable) {
            done.values.forEach { r -> r.getOrNull()?.let { (it.endpoint as? Closeable)?.let { c -> runCatching { c.close() } } } }
            throw e
        }
        val late = "did not connect within ${(budgetMs + 999) / 1000} s; skipped for this task"
        val attempts = enabled.map { config ->
            config to (done[config.id] ?: Result.failure<McpConnection>(McpClientException(late)).also {
                _health.update { h -> h + (config.id to McpHealth(false, late, emptyList(), System.currentTimeMillis())) }
            })
        }
        val connected = attempts.mapNotNull { (c, r) -> r.getOrNull()?.let { c to it } }
        val failures = attempts.mapNotNull { (c, r) -> r.exceptionOrNull()?.let { c.id to (it.message ?: it.javaClass.simpleName) } }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return McpConnections(
            connections = connected.map { it.second },
            // タスクが終了し、その ResourceScope が閉じられます。一度だけ閉店した
            close = {
                if (closed.compareAndSet(false, true)) connected.forEach { (it.second.endpoint as? Closeable)?.let { c -> runCatching { c.close() } } }
            },
            failures = failures,
        )
    }

    /** サーバーに接続し、健全性ステータスを記録します。 */
    private suspend fun attempt(
        config: McpServerConfig,
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore?,
        workspace: String,
    ): Result<McpConnection> {
        val result = try {
            open(config, if (config.transport == "stdio") emptyMap() else authHeaders(config), workspace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val connection: Result<McpConnection> = result.map { endpoint ->
            McpConnection(
                endpoint = endpoint,
                resultStore = resultStore,
                maxCostUnits = config.maxCostUnits,
                previewChars = config.previewChars,
                trust = if (config.trust == "trust-annotations") McpTrust.TrustAnnotations else McpTrust.Restricted,
                allowedTools = config.allowedTools?.toSet(),
            )
        }
        _health.update {
            it + (config.id to connection.fold(
                onSuccess = { c -> McpHealth(true, null, c.endpoint.tools().map { t -> t.name }, System.currentTimeMillis()) },
                onFailure = { e -> McpHealth(false, e.message ?: e.javaClass.simpleName, emptyList(), System.currentTimeMillis()) },
            ))
        }
        return connection
    }

    private suspend fun open(
        config: McpServerConfig,
        headers: Map<String, String>,
        workspace: String,
    ): Result<com.wangzan101.mika.core.cap.mcp.McpEndpoint> = try {
        when (config.transport) {
            "stdio" -> {
                val launcher = stdio ?: throw IllegalStateException("stdio MCP servers are not available on this build")
                val argv = parseStdioCommand(config.command)
                StdioMcpEndpoint.connect(
                    serverId = config.id, command = argv, launcher = launcher, workspace = workspace,
                    timeoutSec = config.timeoutSec,
                    clientName = "Mika", clientVersion = BuildConfig.VERSION_NAME,
                ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
            }
            "http-sse" -> LegacySseMcpEndpoint.connect(
                serverId = config.id, url = config.url, http = http,
                headers = headers, timeoutSec = config.timeoutSec,
                clientName = "Mika", clientVersion = BuildConfig.VERSION_NAME,
            ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
            else -> HttpMcpEndpoint.connect(
                serverId = config.id, url = config.url, http = http,
                headers = headers, timeoutSec = config.timeoutSec,
                clientName = "Mika", clientVersion = BuildConfig.VERSION_NAME,
            ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun authHeaders(config: McpServerConfig): Map<String, String> {
        if (config.transport == "stdio") return emptyMap()
        return buildMap {
            putAll(config.headers)
            if (config.auth == "oauth") {
                var tokens = oauthTokens(config.id) ?: throw McpOAuthException("oauth_login_required")
                if (!tokens.isFresh()) {
                    tokens = oauth.refresh(tokens)
                    setOAuthTokens(config.id, tokens)
                }
                put("Authorization", "Bearer ${tokens.accessToken}")
            } else {
                token(config.id)?.let { put("Authorization", "Bearer $it") }
            }
        }
    }

    @Synchronized fun oauthTokens(id: String): McpOAuthTokens? =
        loadSecrets()["oauth:$id"]?.let { runCatching { tokenJson.decodeFromString<McpOAuthTokens>(it) }.getOrNull() }

    @Synchronized fun setOAuthTokens(id: String, tokens: McpOAuthTokens) {
        val secrets = loadSecrets().toMutableMap()
        secrets["oauth:$id"] = tokenJson.encodeToString(tokens)
        saveSecrets(secrets)
    }

    @Synchronized fun clearOAuth(id: String) {
        val secrets = loadSecrets().toMutableMap()
        if (secrets.remove("oauth:$id") != null) saveSecrets(secrets)
    }

    /**
     * OAuth 2.1 ログイン: 認可サーバーを検出 → ネイティブ コールバックを動的に登録 → ブラウザを開く → コールバックがトークンを変更するのを待ちます。
     * [openBrowser] ログインの開始時に 1 回呼び出されます (システム ブラウザが UI によって開かれます)。プロセス全体は最大 5 分間待つ必要があります。
     */
    suspend fun authorize(config: McpServerConfig, openBrowser: (String) -> Unit): Result<McpOAuthTokens> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val serverUrl = config.url.toHttpUrlOrNull() ?: return Result.failure(IllegalArgumentException("invalid URL"))
        return try {
            Result.success(LoopbackCallback.open().use { callback ->
                val server = oauth.discover(serverUrl)
                val redirect = callback.redirectUri(LoopbackCallback.HOST)
                val registration = oauth.register(server, redirect)
                val attempt = oauth.newAttempt(redirect)
                openBrowser(oauth.authorizeUrl(server, registration, attempt, server.resource))
                var tokens: McpOAuthTokens? = null
                var failure: Exception? = null
                callback.await(
                    timeoutMs = 5 * 60_000L,
                    accept = { it["state"] == attempt.state },
                ) { params ->
                    try {
                        val error = oauth.readCallbackError(params, attempt)
                        if (error != null) {
                            failure = error
                            oauthPage(false)
                        } else {
                            tokens = oauth.exchange(server, registration, oauth.readCode(params, attempt), attempt, server.resource)
                            tokens?.let { setOAuthTokens(config.id, it) }
                            oauthPage(true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e
                        oauthPage(false)
                    }
                } ?: throw McpOAuthException("oauth_timeout")
                failure?.let { throw it }
                tokens ?: throw McpOAuthException("oauth_no_token")
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun healthFailure(e: Throwable): McpHealth =
        McpHealth(false, e.message ?: e.javaClass.simpleName, emptyList(), System.currentTimeMillis())

    private fun oauthPage(ok: Boolean): String = if (ok) {
            "<html lang=\"ja\"><body><h3>Mika JP: ログインしました</h3><p>Mika JPに戻ってください。</p></body></html>"
    } else {
            "<html lang=\"ja\"><body><h3>Mika JP: ログインできませんでした</h3><p>Mika JPに戻り、もう一度お試しください。</p></body></html>"
    }

    private fun loadServers(): List<McpServerConfig> = runCatching {
        if (!file.isFile) emptyList()
        else json.decodeFromString<List<McpServerConfig>>(file.readText())
    }.getOrDefault(emptyList())

    private fun saveConfigs(configs: List<McpServerConfig>) = atomicWrite(file) { json.encodeToString(configs) }

    private fun loadSecrets(): Map<String, String> = runCatching {
        if (!secretsFile.isFile) emptyMap()
        else {
            val text = String(cipher.decrypt(secretsFile.readBytes()), Charsets.UTF_8)
            val obj = Json.parseToJsonElement(text).jsonObject
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
    }.getOrDefault(emptyMap())

    private fun saveSecrets(secrets: Map<String, String>) {
        val text = JsonObject(secrets.mapValues { JsonPrimitive(it.value) }).toString()
        atomicWriteBytes(secretsFile) { cipher.encrypt(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun atomicWrite(target: File, text: () -> String) {
        atomicWriteBytes(target) { text().toByteArray(Charsets.UTF_8) }
    }

    private fun atomicWriteBytes(target: File, bytes: () -> ByteArray) {
        dir.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes())
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun isLocalHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)

    companion object {
        /** 設定ページで標準入出力サーバーをテストするときに使用されるサンドボックス ワークスペース。セッション ID はタスクで使用されます。 */
        const val SETTINGS_WORKSPACE = "mcp-settings"
        /**
         * タスクの開始時に外部サーバーに接続するための最大待機時間 (すべてのサーバーが一緒にカウントされます)。単一サーバー自体のタイムアウトは数分に及ぶ場合があります。
         * がツールに対して呼び出されます。それは最初の握手だけです。遅い場合は、タスクが「準備中」のままにならないように、今回は受け入れません。サンドボックス内の stdio サーバーは PRoot を開始し、それをより広い範囲に残しておく必要があります。
         */
        const val CONNECT_BUDGET_MS = 15_000L
    }

    private fun isBlockedHost(host: String): Boolean =
        host == "169.254.169.254" || host == "metadata.google.internal" ||
            host.startsWith("169.254.") || host == "0.0.0.0" || host == "::" || host == "[::]" ||
            host.startsWith("fd00:ec2:") || host.startsWith("fe80:")
}
