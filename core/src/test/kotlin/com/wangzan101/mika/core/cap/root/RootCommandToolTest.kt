package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 実行コマンドを記録する偽ポート。既定は成功し、コマンドをそのまま出力する。 */
private class FakeRoot(var answer: RootRun? = null) : RootPort {
    val ran = mutableListOf<String>()
    val timeouts = mutableListOf<Long>()
    var afterRun: (() -> Unit)? = null
    override suspend fun run(command: String, timeoutMs: Long): RootRun {
        ran += command
        timeouts += timeoutMs
        afterRun?.invoke()
        return answer ?: RootRun.Done(0, false, "out:$command", "", 0, 5)
    }
}

private class Events : RunSink {
    val all = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { all += e }
}

private var seq = 0
private fun root(command: String, timeoutSec: Int? = null) = ModelReply(
    listOf(Part.ToolCall("r${++seq}", "root_run_command", buildJsonObject {
        put("command", command)
        timeoutSec?.let { put("timeoutSec", it) }
    }.toString())),
    StopReason.ToolUse, null,
)
private val FINISH get() = answerReply("完了しました")

private fun FakeModel.result(i: Int): JsonObject =
    Json.parseToJsonElement(requests[i].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content).jsonObject
private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

/**
 * rootはポートがある場合だけ登録し、読み取りは直接実行する。変更は確認モードで本人へ確認する。
 * 承認時だけ実行し、自律モードは確認を省く。確認モードで本人へ確認できない場合は実行しない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootCommandToolTest {
    private fun loop(m: FakeModel, port: RootPort, autonomous: Boolean = false, sink: RunSink = RunSink.NONE, confirmTimeoutMs: Long? = null) =
        AgentLoop(m, toolDispatcher(FakeScreen(), root = port), "BASE", sink = sink, confirmations = ConfirmationGate(),
            approvals = ApprovalSettings { autonomous }, confirmTimeoutMs = confirmTimeoutMs)

    @Test
    fun registeredOnlyWithAPort() {
        assertTrue(toolDispatcher(FakeScreen()).offeredNow(all = true).none { it.name == "root_run_command" })
        val with = toolDispatcher(FakeScreen(), root = FakeRoot())
        assertTrue(with.offeredNow().any { it.name == "root_run_command" })
        // 保存済みスキル・ナレッジ内の名称としても認識する。
        assertTrue("root_run_command" in knownToolNames())
    }

    /** 読み取りは確認不要で、終了コードと出力を返す。 */
    @Test
    fun readOnlyCommandsRunWithoutAsking() = runTest {
        val port = FakeRoot(); val sink = Events()
        val m = FakeModel(root("ls -l /data/data/com.example.app"), FINISH)
        loop(m, port, sink = sink).run("t")
        assertEquals(listOf("ls -l /data/data/com.example.app"), port.ran)
        val r = m.result(1)
        assertEquals("true", r.s("ok"))
        assertEquals("0", r.s("exitCode"))
        assertEquals(listOf(30_000L), port.timeouts)
        assertTrue(r.s("stdout"), "out:ls" in r.s("stdout"))
        assertTrue(sink.all.none { it is RunEvent.Confirmation })
    }

    /** 変更は全文を確認し、承認した1回だけを実行する。同じコマンドでも次の実行には新しい承認が必要。 */
    @Test
    fun changesAskFirstAndRunOnceApproved() = runTest {
        val port = FakeRoot()
        val command = "  rm /sdcard/Download/old.apk  \n"
        val m = FakeModel(root(command), root(command), FINISH)
        val l = loop(m, port)
        val run = async { l.run("t") }
        advanceUntilIdle()
        val waiting = l.state.value as TaskState.WaitingConfirmation
        assertEquals(ROOT_CONFIRM_APP, waiting.request.app)
        assertEquals(IrrevAction.other, waiting.request.action)
        assertEquals(command, waiting.request.content)
        assertTrue(port.ran.isEmpty())
        l.answerConfirmation(ConfirmChoice.Once)
        advanceUntilIdle()
        assertEquals(listOf(command), port.ran)
        assertEquals(command, (l.state.value as TaskState.WaitingConfirmation).request.content)
        l.answerConfirmation(ConfirmChoice.Once)
        run.await()
        assertEquals(listOf(command, command), port.ran)
        assertEquals("true", m.result(1).s("ok"))
        assertEquals("true", m.result(2).s("ok"))
    }

    @Test
    fun declinedChangesDoNotRun() = runTest {
        val port = FakeRoot(); val sink = Events()
        val m = FakeModel(root("pm uninstall com.example.app"), FINISH)
        val l = loop(m, port, sink = sink)
        val run = async { l.run("t") }
        advanceUntilIdle()
        l.answerConfirmation(ConfirmChoice.Deny)
        run.await()
        assertTrue(port.ran.isEmpty())
        val r = m.result(1)
        assertEquals("confirmation_declined", r.s("error"))
        assertEquals("declined", sink.all.filterIsInstance<RunEvent.Confirmation>().last().decision)
    }

    @Test
    fun autonomousModeDoesNotAsk() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("rm /sdcard/x"), FINISH)
        val l = loop(m, port, autonomous = true)
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertTrue(l.state.value is TaskState.WaitingConfirmation)
        assertTrue(port.ran.isEmpty())
        l.answerConfirmation(ConfirmChoice.Once)
        run.await()
        assertEquals(listOf("rm /sdcard/x"), port.ran)
    }

    @Test
    fun autonomousUnattendedModeKeepsTheOptedInPolicy() = runTest {
        val port = FakeRoot(); val sink = Events()
        val m = FakeModel(root("rm /sdcard/x"), FINISH)
        val spec = RunSpec(RunId("r"), "t", "th", provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        loop(m, port, autonomous = true, sink = sink).run(spec, RunInbox())
        assertTrue(port.ran.isEmpty())
        assertTrue(sink.all.any { it is RunEvent.Approval && it.decision == "denied" })
    }

    @Test
    fun modeChangesCannotReuseAnApprovalForASecondCommand() = runTest {
        var autonomous = false
        val port = FakeRoot().apply { afterRun = { autonomous = false } }
        val command = "rm /sdcard/x"
        val m = FakeModel(root(command), root(command), FINISH)
        val l = AgentLoop(m, toolDispatcher(FakeScreen(), root = port), "BASE", confirmations = ConfirmationGate(),
            approvals = ApprovalSettings { autonomous })
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertTrue(l.state.value is TaskState.WaitingConfirmation)
        autonomous = true
        l.answerConfirmation(ConfirmChoice.Once)
        advanceUntilIdle()
        assertEquals(listOf(command), port.ran)
        assertTrue(l.state.value is TaskState.WaitingConfirmation)
        l.answerConfirmation(ConfirmChoice.Deny)
        run.await()
        assertEquals(listOf(command), port.ran)
        assertEquals("confirmation_declined", m.result(2).s("error"))
    }

    /** 読み取りツールの名前でも、外部スクリプトは変更として確認し、拒否されたら実行しない。 */
    @Test
    fun indirectWritesCannotBypassTheConfirmationMode() = runTest {
        val port = FakeRoot()
        val command = "sed -f /data/local/tmp/script /data/x"
        val m = FakeModel(root(command), FINISH)
        val l = loop(m, port)
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertEquals(command, (l.state.value as TaskState.WaitingConfirmation).request.content)
        assertTrue(port.ran.isEmpty())
        l.answerConfirmation(ConfirmChoice.Deny)
        run.await()
        assertTrue(port.ran.isEmpty())
        assertEquals("confirmation_declined", m.result(1).s("error"))
    }

    /** 確認モードの無人タスクは変更せず、読み取りだけを実行する。 */
    @Test
    fun unattendedRunsDoNotChangeAnything() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("rm /sdcard/x"), root("ls /sdcard"), FINISH)
        val spec = RunSpec(RunId("r"), "t", "th", provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        loop(m, port).run(spec, RunInbox())
        assertEquals(listOf("ls /sdcard"), port.ran)
        assertEquals("approval_unavailable", m.result(1).s("error"))
    }

    @Test
    fun nonUiRunsCannotApproveChangesEvenWhenReachable() = runTest {
        for (origin in listOf(RunOrigin.Channel, RunOrigin.Trigger, RunOrigin.Mcp, RunOrigin.Child)) {
            val port = FakeRoot(); val sink = Events()
            val m = FakeModel(root("rm /sdcard/x"), FINISH)
            val spec = RunSpec(RunId("r"), "t", "th", provenance = RunProvenance(origin, Attendance.Reachable))
            loop(m, port, sink = sink).run(spec, RunInbox())
            assertTrue(origin.name, port.ran.isEmpty())
            assertEquals(origin.name, "approval_unavailable", m.result(1).s("error"))
            assertTrue(origin.name, sink.all.filterIsInstance<RunEvent.Confirmation>().none { it.decision == "approved" })
        }
    }

    @Test
    fun unansweredConfirmationNeverRunsTheCommand() = runTest {
        val port = FakeRoot(); val sink = Events()
        val m = FakeModel(root("rm /sdcard/x"), FINISH)
        loop(m, port, sink = sink, confirmTimeoutMs = 25).run("t")
        assertTrue(port.ran.isEmpty())
        assertEquals("confirmation_declined", m.result(1).s("error"))
        assertTrue(sink.all.filterIsInstance<RunEvent.Confirmation>().any { it.decision == "timed_out" })
    }

    @Test
    fun timeoutBoundsAreCheckedBeforeRunning() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("ls /data", 0), root("ls /data", 301), root("ls /data", 300), FINISH)
        loop(m, port).run("t")
        assertEquals("invalid_arguments", m.result(1).s("error"))
        assertEquals("invalid_arguments", m.result(2).s("error"))
        assertEquals(listOf("ls /data"), port.ran)
        assertEquals(listOf(300_000L), port.timeouts)
    }

    @Test
    fun timedOutCommandKeepsPartialOutputAndDoesNotSuggestRepeatingIt() = runTest {
        val port = FakeRoot(RootRun.Done(143, true, "partial output", "", 0, 30_000))
        val m = FakeModel(root("ls /data"), FINISH)
        loop(m, port).run("t")
        val r = m.result(1)
        assertEquals("false", r.s("ok"))
        assertEquals("true", r.s("timedOut"))
        assertTrue(r.s("stdout"), "partial output" in r.s("stdout"))
        assertTrue(r.s("hint"), "同じ操作を自動で再実行しない" in r.s("hint"))
        assertEquals(listOf("ls /data"), port.ran)
    }

    /** suの拒否は事実として返し、コマンド失敗として自動再試行しない。 */
    @Test
    fun deniedSuIsReported() = runTest {
        val m = FakeModel(root("ls /data"), FINISH)
        loop(m, FakeRoot(RootRun.Denied)).run("t")
        val r = m.result(1)
        assertEquals("root_denied", r.s("error"))
        assertFalse(r.s("message").isBlank())
        assertNull(r["stdout"])
    }
}
