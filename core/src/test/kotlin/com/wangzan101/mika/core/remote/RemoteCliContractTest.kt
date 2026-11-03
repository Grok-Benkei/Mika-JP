package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** は、実際の Kotlin HTTP サービスに接続された実際の Python CLI を実行します。純粋な JVM テストとは別に :core:cliTest によって実行されます。 */
class RemoteCliContractTest {
    @Test fun realCliCompletesLifecycleOverThePlainApiOnly() {
        val tasks = FakeRemoteTasks()
        val host = RemoteHost(tasks, { "contract-test-token" }, 0, false, "test")
        // は自身で監視を開始し、CLI で要求されたパスを記録し、MCP を一度も経由せずに確認しました。
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = MiniHttpServer(RemoteHost.bindAddress(false, 0), { req -> paths += req.path; host.handle(req) })
        val directory = Files.createTempDirectory("mika-cli-contract").toFile()
        val token = File(directory, "token").apply { writeText("contract-test-token") }
        val errors = File(directory, "stderr")
        val script = File("../tools/mika/scripts/mika_cli.py").canonicalPath
        server.start()
        try {
            fun cli(vararg args: String, exit: Int = 0): JsonObject {
                val process = ProcessBuilder(listOf(System.getProperty("mika.python", "python3"), script,
                    "--url", "http://127.0.0.1:${server.port}", "--token-file", token.absolutePath, "--json") + args)
                    // stderr は人々が見ることができる進行状況であり、stdout は JSON のみであり、個別に読み取られ、パスワードのどちらの側も表示することは許可されません。
                    .directory(directory).redirectError(errors).start()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    fail("CLI did not finish within its test budget")
                }
                val output = process.inputStream.bufferedReader().readText()
                val progress = errors.readText()
                assertFalse(output.contains("contract-test-token") || progress.contains("contract-test-token"))
                assertEquals(output + progress, exit, process.exitValue())
                return Json.parseToJsonElement(output).jsonObject
            }
            assertTrue(cli("connect")["connected"]!!.jsonPrimitive.boolean)
            assertEquals("unverified", cli("doctor")["model_configuration"]!!.jsonPrimitive.content)
            val goal = "查询版本\n保留引号 \"与\" emoji 👋"
            val requestId = java.util.UUID.randomUUID().toString()
            val run = cli("task", "run", "--goal", goal, "--request-id", requestId)
            val id = run["task_id"]!!.jsonPrimitive.content
            assertEquals(requestId, id)
            assertEquals(goal, tasks.get(id)!!.goal)
            assertEquals(1, cli("task", "list")["tasks"]!!.jsonArray.size)
            tasks.set(id, TaskState.Running(1, "working"))
            val steer = cli("task", "steer", id, "--text", "只读今天\n不要发送 👋")
            assertTrue(steer["accepted"]!!.jsonPrimitive.boolean)
            assertEquals(id, steer["task_id"]!!.jsonPrimitive.content)
            val messageId = steer["message_id"]!!.jsonPrimitive.content
            assertEquals(listOf(id to "只读今天\n不要发送 👋"), tasks.steered)
            assertEquals(messageId, cli("task", "status", id)["steers"]!!.jsonArray.single().jsonObject["message_id"]!!.jsonPrimitive.content)
            assertEquals(1, tasks.tasks.size)
            assertEquals("running", cli("task", "wait", id, "--timeout", "1")["status"]!!.jsonPrimitive.content)
            assertTrue(tasks.cancelled.isEmpty())
            tasks.set(id, TaskState.WaitingInput("Which account?", listOf("personal", "work")))
            assertEquals("conflict", cli("task", "steer", id, "--text", "work", exit = 1)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("Which account?", cli("task", "wait", id)["question"]!!.jsonPrimitive.content)
            assertTrue(cli("task", "answer", id, "--text", "personal")["accepted"]!!.jsonPrimitive.boolean)
            assertEquals(listOf(id to "personal"), tasks.answers)
            tasks.set(id, TaskState.WaitingTakeover("payment", "Use the phone"))
            assertEquals("conflict", cli("task", "answer", id, "--text", "yes", exit = 1)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals(1, tasks.answers.size)
            val accepted = cli("task", "cancel", id)
            assertTrue(accepted["accepted"]!!.jsonPrimitive.boolean)
            assertNull(accepted["status"])
            tasks.set(id, TaskState.Cancelled)
            assertEquals("cancelled", cli("task", "status", id, exit = 1)["status"]!!.jsonPrimitive.content)
            val next = cli("task", "run", "--goal", "next", "--conversation", run["conversation"]!!.jsonPrimitive.content)
            val nextId = next["task_id"]!!.jsonPrimitive.content
            tasks.set(nextId, TaskState.Succeeded("Android", reviewed = false))
            val result = cli("task", "wait", nextId)
            assertEquals("done", result["status"]!!.jsonPrimitive.content)
            assertNull(result["verified"])
            assertEquals(2, tasks.tasks.size)
            assertTrue(paths.isNotEmpty())
            assertTrue(paths.toString(), paths.all { it == RemoteTaskApi.ROOT || it.startsWith("${RemoteTaskApi.ROOT}/") })
        } finally {
            server.stop()
            directory.deleteRecursively()
        }
    }
}
