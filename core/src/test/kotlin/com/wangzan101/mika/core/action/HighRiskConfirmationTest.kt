package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.settings.SelfConfig
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRiskConfirmationTest {
    @Test fun askIsTheDefaultAndIsNotAutonomous() {
        assertFalse(ApprovalSettings.ASK.autonomous())
    }

    @Test fun settingsChangeDoesNotRunWithoutThatExactConfirmation() = runTest {
        val writes = mutableListOf<String>()
        val config = object : SelfConfig {
            override fun options() = emptyList<SelfConfig.Option>()
            override fun set(key: String, value: String): String? {
                writes += "$key=$value"
                return null
            }
        }
        val executor = ActionExecutor(
            toolDispatcher(FakeScreen(), selfConfig = config),
            OperationGuards(),
            null,
            gate = {},
            recorder = ActionRecorder(RunSink.NONE),
            approvals = ApprovalSettings.ASK,
        )
        val blocked = executor.run(
            Part.ToolCall("c", "settings_set", """{"key":"theme","value":"dark","approved":true}"""),
            Origin.Model(1),
        ).execution().content
        assertEquals("false", blocked["ok"].toString())
        assertTrue(writes.isEmpty())
        val risk = toolDispatcher(FakeScreen(), selfConfig = config).approvalRisk(
            "settings_set",
            kotlinx.serialization.json.Json.parseToJsonElement("""{"key":"theme","value":"dark"}""") as kotlinx.serialization.json.JsonObject,
        )
        assertEquals("dark", risk?.ask?.content)
        assertEquals("theme", risk?.ask?.target)
    }

    @Test fun exactSettingsConfirmationRunsOnceAndAChangedValueDoesNot() = runTest {
        val writes = mutableListOf<String>()
        val config = object : SelfConfig {
            override fun options() = emptyList<SelfConfig.Option>()
            override fun set(key: String, value: String): String? {
                writes += "$key=$value"
                return null
            }
        }
        val gate = ConfirmationGate()
        val req = ConfirmationRequest(IrrevAction.other, "theme", "dark", null, "settings")
        assertEquals(com.wangzan101.mika.core.gate.DeclareResult.Pending, gate.declare(req, "settings"))
        gate.decide(ConfirmChoice.Once, 1)
        val executor = ActionExecutor(
            toolDispatcher(FakeScreen(), selfConfig = config),
            OperationGuards(), gate, gate = {}, recorder = ActionRecorder(RunSink.NONE),
            approvals = ApprovalSettings { true },
        )
        val ok = executor.run(Part.ToolCall("c", "settings_set", """{"key":"theme","value":"dark"}"""), Origin.Model(1))
        assertTrue("$ok", ok is ActionOutcome.Ran)
        assertEquals(listOf("theme=dark"), writes)
        val changed = executor.run(Part.ToolCall("c2", "settings_set", """{"key":"theme","value":"light"}"""), Origin.Model(1)).execution().content
        assertEquals("false", changed["ok"].toString())
        assertEquals(listOf("theme=dark"), writes)
    }

    @Test fun lowRiskTapStillRunsWhenAutonomous() = runTest {
        val executor = ActionExecutor(
            toolDispatcher(FakeScreen()), OperationGuards(), null, gate = {},
            recorder = ActionRecorder(RunSink.NONE), autonomous = true,
        )
        val out = executor.run(Part.ToolCall("c", "phone_tap", """{"x":1,"y":1}"""), Origin.Model(1))
        assertTrue("$out", out is ActionOutcome.Ran)
    }
}
