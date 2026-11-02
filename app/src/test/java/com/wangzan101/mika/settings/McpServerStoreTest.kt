package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.mcp.McpOAuthTokens
import com.wangzan101.mika.core.cap.mcp.McpStdioLauncher
import com.wangzan101.mika.core.cap.mcp.McpStdioProcess
import com.wangzan101.mika.core.resource.ResourceScope
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class McpServerStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** バイトを反転するだけ; secret ファイルが平文でないことを確認するために使う。 */
    private class ReverseCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }

    private fun store() = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient())

    @Test fun addsKeepsAndEncryptsToken() {
        val s = store()
        val added = s.add("Demo", "https://mcp.example.com/mcp", "secret-token").getOrThrow()
        assertEquals(listOf("Demo"), s.servers.value.map { it.name })
        assertEquals("secret-token", s.token(added.id))

        val secrets = File(tmp.root, "mcp-server-secrets.bin").readBytes()
        assertFalse(String(secrets, Charsets.UTF_8).contains("secret-token"))

        val reopened = store()
        assertEquals("Demo", reopened.servers.value.single().name)
        assertEquals("secret-token", reopened.token(added.id))
    }

    @Test fun enableUpdateAndRemove() {
        val s = store()
        val added = s.add("A", "https://a.example/mcp").getOrThrow()
        s.setEnabled(added.id, false)
        assertFalse(s.servers.value.single().enabled)
        s.update(s.servers.value.single().copy(name = "B")).getOrThrow()
        assertEquals("B", s.servers.value.single().name)
        s.setToken(added.id, "t")
        assertNotNull(s.token(added.id))
        s.remove(added.id)
        assertTrue(s.servers.value.isEmpty())
        assertEquals(null, s.token(added.id))
    }

    @Test fun rejectsUnsafeUrls() {
        val s = store()
        assertTrue(s.add("x", "http://example.com/mcp").isFailure)
        assertTrue(s.add("x", "http://localhost:1234/mcp").isFailure) // 明示的な allowLocalHttp が必要
        assertTrue(s.add("x", "https://user:pw@example.com/mcp").isFailure)
        assertTrue(s.add("x", "http://169.254.169.254/mcp").isFailure)
        assertTrue(s.add("x", "https://169.254.169.254/mcp").isFailure)
        assertTrue(s.add("x", "https://0.0.0.0/mcp").isFailure)
        assertTrue(s.add("x", "not a url").isFailure)
        assertNotNull(s.add("x", "https://example.com/mcp").getOrNull())
    }

    @Test fun localHttpIsAllowedOnlyWhenOptedIn() {
        val s = store()
        val added = s.add("local", "http://localhost:1/mcp").let { it.exceptionOrNull() }
        assertNotNull(added)
        val ok = s.add("local", "https://localhost/mcp").getOrThrow().let {
            s.update(it.copy(allowLocalHttp = true)).getOrThrow()
        }
        assertEquals(true, ok.allowLocalHttp)
        // http のローカルアドレスに変えれば通せる
        assertEquals(null, s.validate(ok.copy(url = "http://localhost:1/mcp")))
    }

    @Test fun connectionsConnectsEnabledServersAndReportsFailures() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"demo"}}}"""
            ))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search","description":"s","inputSchema":{"type":"object"}}]}}"""
            ))
            val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient())
            val added = s.add("demo", "https://localhost/mcp").getOrThrow()
            val url = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString()
            s.update(added.copy(url = url, allowLocalHttp = true)).getOrThrow()
            val connections = s.connections()
            assertEquals(0, connections.failures.size)
            assertEquals(1, connections.connections.size)
            assertEquals(listOf("search"), connections.connections.single().endpoint.tools().map { it.name })
            connections.close()
        } finally {
            server.shutdown()
        }
    }

    /** 偽の stdio サーバー: ストアの起動とアクセスを確認するために使用される初期化とツール/リストのみを回答します。 */
    private fun stdioLauncher(): McpStdioLauncher = McpStdioLauncher { _, _ ->
        val toServer = PipedOutputStream()
        val fromClient = PipedInputStream(toServer)
        val fromServer = PipedOutputStream()
        val toClient = PipedInputStream(fromServer)
        val json = Json { ignoreUnknownKeys = true }
        thread(isDaemon = true, name = "fake-store-stdio") {
            runCatching {
                val reader = fromClient.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    val message = json.parseToJsonElement(line).jsonObject
                    val id = (message["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val result = when (method) {
                        "initialize" -> buildJsonObject {
                            put("protocolVersion", "2025-03-26")
                            put("serverInfo", buildJsonObject { put("name", "local") })
                        }
                        "tools/list" -> buildJsonObject {
                            put("tools", JsonArray(listOf(buildJsonObject {
                                put("name", "search")
                                put("description", "Search")
                                put("inputSchema", buildJsonObject { put("type", "object") })
                            })))
                        }
                        else -> continue
                    }
                    val response = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id.toLong())
                        put("result", result)
                    }
                    synchronized(fromServer) {
                        fromServer.write((response.toString() + "\n").toByteArray())
                        fromServer.flush()
                    }
                }
            }
        }
        object : McpStdioProcess {
            override val stdin = toServer
            override val stdout = toClient
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() {
                runCatching { toServer.close() }
                runCatching { fromServer.close() }
                runCatching { fromClient.close() }
                runCatching { toClient.close() }
            }
        }
    }

    @Test fun stdioConnectionsGoThroughTheSandboxLauncher() = runBlocking {
        val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), stdio = stdioLauncher())
        s.add(McpServerConfig(name = "local", url = "", transport = "stdio", command = "fake-server")).getOrThrow()
        val connections = s.connections(workspace = "ws")
        assertEquals(0, connections.failures.size)
        assertEquals(listOf("search"), connections.connections.single().endpoint.tools().map { it.name })
        connections.close()
    }

    /** stdio サーバーが応答しません: ハンドシェイクは応答を待つことができません。 */
    private fun silentProcess(): McpStdioProcess {
        val toServer = PipedOutputStream()
        val fromClient = PipedInputStream(toServer, 1 shl 16)
        val fromServer = PipedOutputStream()
        val toClient = PipedInputStream(fromServer)
        return object : McpStdioProcess {
            override val stdin = toServer
            override val stdout = toClient
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() {
                runCatching { toServer.close() }
                runCatching { fromServer.close() }
                runCatching { fromClient.close() }
                runCatching { toClient.close() }
            }
        }
    }

    /** はサンドボックスのようなものです。プロセスは、ツリーが生きているときはツリーのリースを保持し、シャットダウンされるとそれを返します。排他的に回復されると、プロセスが停止され、[revokes] に記録されます。コマンドは沈黙しており、応答しません。 */
    private fun leasingLauncher(tree: SandboxTree, closes: AtomicInteger, revokes: AtomicInteger = AtomicInteger()): McpStdioLauncher {
        val inner = stdioLauncher()
        return McpStdioLauncher { ws, argv ->
            val p = if (argv.first() == "silent") silentProcess() else inner.launch(ws, argv)
            val lease = tree.lease { revokes.incrementAndGet(); p.close() }
            object : McpStdioProcess by p {
                override fun close() {
                    closes.incrementAndGet()
                    p.close()
                    lease.close()
                }
            }
        }
    }

    /** タスクが終了し、ResourceScope が閉じます (TaskRun は最終的に、キャンセルと失敗を含みます)。接続されたサーバーはシャットダウンされ、サンドボックスのアクセス許可が解放され、排他的アクセス許可はブロックされなくなります。 */
    @Test fun taskScopeCloseClosesConnectionsAndFreesThePermit() = runBlocking {
        val tree = SandboxTree()
        val closes = AtomicInteger()
        val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), stdio = leasingLauncher(tree, closes))
        s.add(McpServerConfig(name = "local", url = "", transport = "stdio", command = "fake-server")).getOrThrow()
        val resources = ResourceScope()
        val connections = s.connectionsFor(resources, workspace = "ws")
        assertEquals(1, connections.connections.size)
        // はタスク内でまだ開いています。通常の排他は、使用されるとリースを取り戻し (プロセスを停止し)、タスク全体を待機しません。
        resources.close()
        assertEquals(1, closes.get())
        connections.close() // 最後に繰り返さずにもう一度閉じます
        assertEquals(1, closes.get())
        withTimeout(5_000) { tree.exclusive { } }
        // タスクはずっと前に終了しました。リースはありません。専用のリースは直接取得され、転送されません。
        assertEquals(1, closes.get())
    }

    /** タスクが開いているときに実行環境を変更します。リースを独占的に取り戻し、サーバーを停止し、タスクが終了するのを待ちます。 */
    @Test fun exclusiveStopsALongLivedServerInsteadOfWaiting() = runBlocking {
        val tree = SandboxTree()
        val closes = AtomicInteger()
        val revokes = AtomicInteger()
        val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), stdio = leasingLauncher(tree, closes, revokes))
        s.add(McpServerConfig(name = "local", url = "", transport = "stdio", command = "fake-server")).getOrThrow()
        val resources = ResourceScope()
        s.connectionsFor(resources, workspace = "ws")
        withTimeout(5_000) { tree.exclusive { } }
        assertEquals(1, revokes.get())
        resources.close()
    }

    /** 接続の途中でタスクがキャンセルされました。接続されているものも閉じられ、権限が解放されます。 */
    @Test fun cancelDuringConnectClosesWhatConnected() = runBlocking {
        val tree = SandboxTree()
        val closes = AtomicInteger()
        val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), stdio = leasingLauncher(tree, closes))
        val ok = s.add(McpServerConfig(name = "local", url = "", transport = "stdio", command = "fake-server")).getOrThrow()
        s.add(McpServerConfig(name = "silent", url = "", transport = "stdio", command = "silent")).getOrThrow()
        val resources = ResourceScope()
        val job = launch(Dispatchers.IO) { s.connectionsFor(resources, workspace = "ws") }
        withTimeout(5_000) { while (s.health.value[ok.id] == null) delay(20) } // 返信した方が先に繋がります。
        job.cancelAndJoin()
        resources.close()
        assertEquals(2, closes.get()) // 接続されている方の電源をオフにし、途中で接続されている方の電源もオフにします。
        withTimeout(5_000) { tree.exclusive { } }
    }

    /** サーバーが遅いからといってタスクの開始が遅れることはありません。合計時間制限に達するとタスクはスキップ（オフ）され、失敗として記録され、接続されているサーバーは通常どおり使用されます。 */
    @Test fun slowServerIsSkippedAfterTheBudget() = runBlocking {
        val fast = MockWebServer().also { it.start() }
        try {
            scriptTools(fast, "search")
            val tree = SandboxTree()
            val closes = AtomicInteger()
            val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient(), stdio = leasingLauncher(tree, closes))
            val cf = s.add("fast", "https://localhost/a").getOrThrow()
            s.update(cf.copy(url = fast.url("/mcp").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true)).getOrThrow()
            val cs = s.add(McpServerConfig(name = "silent", url = "", transport = "stdio", command = "silent", timeoutSec = 600)).getOrThrow()
            val started = System.nanoTime()
            val connections = s.connections(budgetMs = 1_500)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $tookMs ms", tookMs < 5_000)
            assertEquals(listOf(cf.id), connections.connections.map { it.endpoint.serverId })
            assertEquals(listOf(cs.id), connections.failures.map { it.first })
            assertFalse(s.health.value.getValue(cs.id).ok)
            assertTrue(s.health.value.getValue(cs.id).message.orEmpty().contains("skipped"))
            assertTrue(s.health.value.getValue(cf.id).ok)
            assertEquals(1, closes.get()) // スキップされたプロセスはシャットダウンされました
            withTimeout(5_000) { tree.exclusive { } }
            connections.close()
        } finally {
            fast.shutdown()
        }
    }

    @Test fun stdioConfigNeedsOnlyACommand() {
        val s = store()
        assertTrue(s.add(McpServerConfig(name = "local", url = "", transport = "stdio", command = "python3 /root/server.py")).isSuccess)
        assertTrue(s.add(McpServerConfig(name = "bad", url = "", transport = "stdio", command = " ")).isFailure)
        assertTrue(s.add(McpServerConfig(name = "bad", url = "", transport = "stdio", command = "a\nb")).isFailure)
    }

    @Test fun oauthTokensAreEncryptedAndSurviveRestart() {
        val s = store()
        val added = s.add("oauth", "https://oauth.example/mcp").getOrThrow()
        val tokens = McpOAuthTokens(
            accessToken = "at-secret",
            refreshToken = "rt-secret",
            expiresAtMs = System.currentTimeMillis() + 3_600_000,
            clientId = "cid",
            tokenEndpoint = "https://oauth.example/token",
        )
        s.setOAuthTokens(added.id, tokens)
        assertEquals(tokens.accessToken, s.oauthTokens(added.id)?.accessToken)

        val secrets = File(tmp.root, "mcp-server-secrets.bin").readBytes()
        assertFalse(String(secrets, Charsets.UTF_8).contains("at-secret"))
        val reopened = store()
        assertEquals("at-secret", reopened.oauthTokens(added.id)?.accessToken)
        assertEquals("cid", reopened.oauthTokens(added.id)?.clientId)

        s.remove(added.id)
        assertEquals(null, s.oauthTokens(added.id))
    }

    @Test fun connectionsUseOAuthAccessToken() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            scriptTools(server, "search")
            val s = store()
            val added = s.add("oauth", "https://localhost/mcp").getOrThrow()
            val config = added.copy(
                url = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString(),
                allowLocalHttp = true,
                auth = "oauth",
            )
            s.update(config).getOrThrow()
            s.setOAuthTokens(
                added.id,
                McpOAuthTokens("at", null, null, "cid", null,
                    server.url("/token").newBuilder().host("127.0.0.1").build().toString()),
            )
            val connections = s.connections()
            assertEquals(0, connections.failures.size)
            assertEquals(listOf("search"), connections.connections.single().endpoint.tools().map { it.name })
            assertEquals("Bearer at", server.takeRequest().getHeader("Authorization"))
            connections.close()
        } finally {
            server.shutdown()
        }
    }

    @Test fun expiredOAuthTokenIsRefreshedBeforeConnecting() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val tokenUrl = server.url("/token").newBuilder().host("127.0.0.1").build().toString()
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"access_token":"new-at","expires_in":3600}"""),
            )
            scriptTools(server, "search")
            val s = store()
            val added = s.add("oauth", "https://localhost/mcp").getOrThrow()
            s.update(
                added.copy(
                    url = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString(),
                    allowLocalHttp = true,
                    auth = "oauth",
                ),
            ).getOrThrow()
            s.setOAuthTokens(
                added.id,
                McpOAuthTokens("old-at", "rt", 0L, "cid", null, tokenUrl),
            )

            val connections = s.connections()
            assertEquals(0, connections.failures.size)
            val refresh = server.takeRequest()
            assertEquals("/token", refresh.path)
            assertTrue(refresh.body.readUtf8().contains("grant_type=refresh_token"))
            val init = server.takeRequest()
            assertEquals("/mcp", init.path)
            assertEquals("Bearer new-at", init.getHeader("Authorization"))
            assertEquals("new-at", s.oauthTokens(added.id)?.accessToken)
            assertEquals("rt", s.oauthTokens(added.id)?.refreshToken)
            connections.close()
        } finally {
            server.shutdown()
        }
    }

    private fun scriptTools(server: MockWebServer, vararg tools: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"demo"}}}"""
        ))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[""" +
                tools.joinToString(",") { """{"name":"$it","description":"","inputSchema":{"type":"object"}}""" } + "]}}"
        ))
    }

    @Test fun connectionsConnectAllEnabledServersAndRecordHealth() = runBlocking {
        val a = MockWebServer().also { it.start() }
        val b = MockWebServer().also { it.start() }
        try {
            scriptTools(a, "search")
            scriptTools(b, "send")
            val s = store()
            val ca = s.add("a", "https://localhost/a").getOrThrow()
            val cb = s.add("b", "https://localhost/b").getOrThrow()
            s.update(ca.copy(url = a.url("/mcp").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true)).getOrThrow()
            s.update(cb.copy(url = b.url("/mcp").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true, allowedTools = listOf("send"))).getOrThrow()

            val connections = s.connections()
            assertEquals(0, connections.failures.size)
            assertEquals(2, connections.connections.size)
            assertEquals(null, connections.connections.single { it.endpoint.serverId == ca.id }.allowedTools)
            assertEquals(setOf("send"), connections.connections.single { it.endpoint.serverId == cb.id }.allowedTools)
            assertEquals(listOf("search"), s.health.value.getValue(ca.id).tools)
            assertTrue(s.health.value.getValue(cb.id).ok)
            connections.close()
        } finally {
            a.shutdown()
            b.shutdown()
        }
    }

    @Test fun failedConnectionIsReportedAndDoesNotThrow() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            val s = store()
            val added = s.add("dead", "https://localhost/mcp").getOrThrow()
            s.update(added.copy(url = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true)).getOrThrow()
            val connections = s.connections()
            assertEquals(0, connections.connections.size)
            assertEquals(listOf(added.id), connections.failures.map { it.first })
            assertFalse(s.health.value.getValue(added.id).ok)
        } finally {
            server.shutdown()
        }
    }

    @Test fun testAllRefreshesHealthOfEnabledServers() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            scriptTools(server, "a")
            val s = store()
            val added = s.add("demo", "https://localhost/mcp").getOrThrow()
            s.update(added.copy(url = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true)).getOrThrow()
            val results = s.testAll()
            assertEquals(1, results.size)
            assertTrue(results.single().isSuccess)
            assertTrue(s.health.value.getValue(added.id).ok)
            assertEquals(listOf("a"), s.health.value.getValue(added.id).tools)
        } finally {
            server.shutdown()
        }
    }

    @Test fun testConnectionReturnsServerInfo() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"demo"},"instructions":"hi"}}"""
            ))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"a","description":"","inputSchema":{"type":"object"}}]}}"""
            ))
            val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient())
            val added = s.add("demo", "https://localhost/mcp").getOrThrow()
            val config = added.copy(url = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true)
            s.update(config).getOrThrow()
            val result = s.test(config).getOrThrow()
            assertEquals("demo", result.serverName)
            assertEquals("hi", result.instructions)
            assertEquals(listOf("a"), result.tools)
        } finally {
            server.shutdown()
        }
    }
}
