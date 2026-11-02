package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.Severity
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.tool.Verb

class RunTimelineViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_760_000_000_000L
    private val store by lazy { RunStore(tmp.root.resolve("files"), { null }, 30, { clock }) { it } }

    private fun run(goal: String, block: RunHandle.() -> Unit): String {
        val h = store.open(goal)
        h.block()
        h.close()
        return h.runId
    }

    private fun ready(runId: String) = RunTimelineViewModel(store, runId).load() as TimelineUi.Ready

    /** タスクがまだ実行中でイベントが終了していない場合、タスクは「進行中」とみなされます。それに応じてページは 1 秒ごとに再読み込みされ、実行後に停止します。 */
    @Test fun anUnfinishedRunOfALiveTaskReadsAsRunning() {
        val h = store.open("打开设置")
        store.append(h.runId, RunEvent.ModelReply(clock + 1_000, 1, "", null, null, 500, 10, 0, 900, repliedAtMs = clock + 1_000))
        val live = RunTimelineViewModel(store, h.runId, alive = { it == h.runId }).load() as TimelineUi.Ready
        assertTrue("未終了なら実行中として扱う", live.header.running)
        store.append(h.runId, RunEvent.TaskEnded(clock + 2_000, "succeeded", "好了"))
        val done = RunTimelineViewModel(store, h.runId, alive = { it == h.runId }).load() as TimelineUi.Ready
        assertFalse("終了後は再読み込みしない", done.header.running)
    }

    /** レコードは終了しておらず、タスクは存在しません (プロセスが強制終了され、レコードは終了していません): 終了したとみなされ、数秒以内に再読み込みが行われません。 */
    @Test fun anUnfinishedRunWhoseTaskIsGoneIsNotRunning() {
        val h = store.open("打开设置")
        store.append(h.runId, RunEvent.ModelReply(clock + 1_000, 1, "", null, null, 500, 10, 0, 900, repliedAtMs = clock + 1_000))
        val gone = RunTimelineViewModel(store, h.runId, alive = { false }).load() as TimelineUi.Ready
        assertFalse(gone.header.running)
        val other = RunTimelineViewModel(store, h.runId, alive = { it == "another-run" }).load() as TimelineUi.Ready
        assertFalse("別のタスクが実行中でも、この実行は実行中としない", other.header.running)
    }

    @Test fun usedMemoryAppearsInProcessHeader() {
        val id = run("总结") { event(RunEvent.MemoryUsed(clock, listOf("m1", "m2"))) }
        val ui = RunTimelineViewModel(store, id, resolveMemory = { ids -> ids.map { "记忆 $it" } }).load() as TimelineUi.Ready
        assertEquals(listOf("记忆 m1", "记忆 m2"), ui.header.usedMemory)
    }

    @Test fun headerCarriesRunMetrics() {
        val id = run("查天气") {
            event(RunEvent.TaskStarted(clock, "查天气", "m1"))
            event(RunEvent.ModelReply(clock + 3_000, 1, "", null, null, 1_000, 100, 0, 2_500, repliedAtMs = clock + 3_000))
            event(RunEvent.TaskEnded(clock + 4_000, "succeeded", "晴"))
        }
        val m = ready(id).header.metrics!!
        assertEquals(id, m.runId)
        assertEquals(4_000L, m.totalMs)
        assertEquals(3_000L, m.firstFeedbackMs)
        assertEquals(1, m.modelCalls)
    }

    @Test fun headerCarriesTheStepLimit() {
        val limited = run("查天气") {
            event(RunEvent.TaskStarted(clock, "查天气", "m1", stepLimit = 60))
            event(RunEvent.TaskEnded(clock + 1_000, "succeeded", "晴"))
        }
        assertEquals(60, ready(limited).header.stepLimit)
        val unlimited = run("看电量") {
            event(RunEvent.TaskStarted(clock, "看电量", "m1"))
            event(RunEvent.TaskEnded(clock + 1_000, "succeeded", "85%"))
        }
        assertNull(ready(unlimited).header.stepLimit)
    }

    /** ステップ数を使い切った後の最終ラウンドは上限＋1ステップとして記録されます。ステップ数表示時にはカウントされず、4/3は表示されません。 */
    @Test fun stepsUsedNeverExceedsTheLimit() {
        val id = run("查快递") {
            event(RunEvent.TaskStarted(clock, "查快递", "m1", stepLimit = 3))
            (1..4).forEach { step -> event(RunEvent.ModelReply(clock + step * 1_000L, step, "", null, null, 500, 10, 0, 900, repliedAtMs = clock + step * 1_000L)) }
            event(RunEvent.TaskEnded(clock + 5_000, "failed", "已达到 3 步上限"))
        }
        assertEquals(3, ready(id).header.stepsUsed)
        val early = run("看电量") {
            event(RunEvent.TaskStarted(clock, "看电量", "m1", stepLimit = 30))
            event(RunEvent.ModelReply(clock + 1_000, 1, "", null, null, 500, 10, 0, 900, repliedAtMs = clock + 1_000))
            event(RunEvent.ModelReply(clock + 2_000, 2, "85%", null, null, 500, 10, 0, 900, repliedAtMs = clock + 2_000))
            event(RunEvent.TaskEnded(clock + 3_000, "succeeded", "85%"))
        }
        assertEquals(2, ready(early).header.stepsUsed)
    }

    @Test fun groupsEventsIntoSteps() {
        val long = "先".repeat(100)
        val id = run("看电量") {
            event(RunEvent.TaskStarted(clock, "看电量", "m1"))
            event(RunEvent.Criteria(clock, listOf("看到电量百分比"), emptyList()))
            event(RunEvent.ModelReply(clock, 1, long, "phone_launch_app", """{"package":"com.android.settings"}""", 10, 1, null))
            event(RunEvent.ToolResult(clock, 1, "phone_launch_app", """{"ok":true}""", true, null))
            event(RunEvent.ModelReply(clock, 2, "看看", "phone_observe", "{}", 10, 1, null))
            event(RunEvent.ToolResult(clock, 2, "phone_observe", """{"ok":true,"elements":[]}""", null, 1))
            media(1, byteArrayOf(1, 2, 3))
            event(RunEvent.Media(clock, 1, 2, "com.android.settings"))
            event(RunEvent.ModelReply(clock, 3, "", "phone_tap", """{"ref":"@e3"}""", 10, 1, null))
            event(RunEvent.ToolResult(clock, 3, "phone_tap", """{"ok":true}""", false, null))
            event(RunEvent.ModelReply(clock, 4, "", "phone_tap", """{"ref":"@e9"}""", 10, 1, null))
            event(RunEvent.ToolResult(clock, 4, "phone_tap", """{"ok":false,"error":"ref_expired","message":"x"}""", null, null))
            clock += 65_000
            event(RunEvent.TaskEnded(clock, "succeeded", "85%"))
        }
        val ui = ready(id)
        assertEquals(listOf(1, 2, 3, 4), ui.steps.map { it.step })
        val (launch, look, tap, stale) = ui.steps
        assertEquals("先".repeat(80) + "…", launch.said)
        assertEquals("アプリを開く", launch.tool)
        assertEquals("com.android.settings", launch.detail)
        assertEquals("効果あり", launch.result)
        assertEquals("画面を見る", look.tool)
        assertEquals(1, look.screenshotId)
        assertTrue(store.screen(id, 1)!!.isFile)
        assertEquals("変化なし", tap.result)
        assertEquals("エラー: 画面が変わりました。見直しが必要です", stale.result)
        assertNull(launch.screenshotId)

        val h = ui.header
        assertEquals("看电量", h.goal)
        assertEquals(listOf("看到电量百分比"), h.criteria)
        assertEquals("完了", h.outcome)
        assertEquals("1 分 5 秒", h.duration)
        assertEquals(4, h.modelCalls)
        assertEquals("未確認", h.verdict)
    }

    @Test fun rejectedLightReplyCountsAsCallButNotStep() {
        val id = run("看电量") {
            event(RunEvent.ModelReply(clock, 1, "", "phone_observe", "{}", 10, 1, null, model = "light", role = "light_rejected"))
            event(RunEvent.ModelReply(clock, 1, "", "phone_tap", """{"ref":"@e3"}""", 10, 1, null, model = "main", role = "main"))
            event(RunEvent.ToolResult(clock, 1, "phone_tap", """{"ok":true}""", true, null))
            event(RunEvent.TaskEnded(clock, "succeeded", "85%"))
        }
        val ui = ready(id)
        assertEquals(2, ui.header.modelCalls)
        // 再割り当てされた観察は次のステップに進みません。このステップではメイン モデルのタップが表示されます。
        assertEquals(ToolLabels(knownTools()).label("phone_tap"), ui.steps.single().tool)
    }

    @Test fun stepTimingIncludesModelRetryAndToolWithoutCountingBatchChildrenTwice() {
        val id = run("连续操作") {
            event(RunEvent.ModelReply(clock, 1, "", "phone_observe", "{}", null, null, null,
                latencyMs = 1_200, role = "light_rejected"))
            event(RunEvent.ModelReply(clock, 1, "", "do_steps", "{}", null, null, null,
                latencyMs = 2_300, retryWaitMs = 500))
            event(RunEvent.ToolResult(clock, 1, "phone_tap", """{"ok":true}""", true, null,
                durationMs = 700, source = "batch"))
            event(RunEvent.ToolResult(clock, 1, "phone_type_text", """{"ok":true}""", true, null,
                durationMs = 900, source = "batch"))
            event(RunEvent.ToolResult(clock, 1, "do_steps", """{"ok":true}""", true, null,
                durationMs = 2_000, source = "model"))
        }
        val timing = ready(id).steps.single().timing
        assertEquals(4_000L, timing.modelMs)
        assertEquals(2_000L, timing.toolMs)
        assertEquals(500L, timing.retryMs)
        assertEquals(6_000L, timing.totalMs)
        assertEquals("約 6.0 秒 · モデル 4.0 秒 · 操作 2.0 秒 · 再試行を含む 500 ミリ秒",
            RunTimelineViewModel.timingLabel(timing))
    }

    @Test fun replayTimingAndLegacyMissingTimingAreLabeledHonestly() {
        val replay = run("回放") {
            event(RunEvent.ToolResult(clock, 1, "phone_tap", """{"ok":true}""", true, null,
                durationMs = 850, source = "replay"))
        }
        assertEquals("操作 850 ミリ秒", RunTimelineViewModel.timingLabel(ready(replay).steps.single().timing))

        val legacy = run("旧记录") {
            event(RunEvent.ModelReply(clock, 1, "", "phone_tap", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 1, "phone_tap", """{"ok":true}""", true, null))
        }
        assertEquals("時間は記録されていません", RunTimelineViewModel.timingLabel(ready(legacy).steps.single().timing))
        assertNull(ready(legacy).steps.single().timing.totalMs)
    }

    @Test fun activitiesMergeReadsAndKeepActionsSeparate() {
        val id = run("查页面并操作") {
            event(RunEvent.ModelReply(clock, 1, "", "do_steps", "{}", 10, 1, null))
            repeat(3) { i ->
                event(RunEvent.Activity(clock, 1, "observe", "web_read_page", "example.com", "completed", listOf(i + 1), callId = "r$i"))
            }
            event(RunEvent.Activity(clock, 1, "act", "web_tap", "example.com", "done", callId = "a1"))
            event(RunEvent.Activity(clock, 1, "act", "web_tap", "example.com", "no_effect", callId = "a2"))
        }
        val rows = ready(id).steps.single().activities
        assertEquals(3, rows.size)
        assertEquals(3, rows[0].count)
        assertEquals(listOf(1, 2, 3), rows[0].obsIds)
        assertEquals(listOf("act", "act"), rows.drop(1).map { it.kind })
    }

    @Test fun showsVerifierAndGuardTags() {
        // は、AgentLoop と同じ方法を使用して、独自のシリアル化結果を保護します。
        fun withGuard(vararg v: GuardVerdict) =
            JsonObject(mapOf("ok" to JsonPrimitive(true), "guard" to JsonArray(v.map { it.toJson() }))).toString()
        val warn = withGuard(GuardVerdict("no_progress", Severity.Warn, buildJsonObject { put("same", 3) }))
        val stop = withGuard(GuardVerdict("repeating_action", Severity.Stop, buildJsonObject { put("code", "x"); put("n", 3) }))
        // はキーの順番を入れ替えても認識されます。
        val reordered = """{"ok":true,"guard":[{"evidence":{"a":1},"severity":"warn","code":"too_many_failures"},{"severity":"warn","code":"brand_new"}]}"""
        val id = run("发消息") {
            event(RunEvent.ModelReply(clock, 1, "", "phone_scroll", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 1, "phone_scroll", warn, false, null))
            event(RunEvent.ModelReply(clock, 2, "", "phone_scroll", "{}", null, null, null))
            // 切り捨てられた結果は正当な JSON ではなくなりましたが、タグは引き続き認識可能です。
            event(RunEvent.ToolResult(clock, 2, "phone_scroll", RunEvent.clip(stop + " ".repeat(RunEvent.MAX_FIELD)), false, null))
            event(RunEvent.ModelReply(clock, 4, "", "phone_tap", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 4, "phone_tap", reordered, null, null))
            event(RunEvent.ModelReply(clock, 5, "", "phone_tap", "{}", null, null, null))
            event(RunEvent.ToolResult(clock, 5, "phone_tap", """{"ok":false,"error":"some_new_code"}""", null, null))
            event(RunEvent.ModelReply(clock, 3, "好了", "finish", """{"answer":"x"}""", null, null, null))
            event(RunEvent.Verifier(clock, "fail", "{}"))
            event(RunEvent.ToolResult(clock, 3, "finish", """{"ok":false,"error":"verification_failed"}""", null, null))
            event(RunEvent.TaskEnded(clock, "failed", "已安全停止"))
        }
        val ui = ready(id)
        assertEquals(listOf(Tag("ガードの警告: 画面が変化しません", TagTone.Warn)), ui.steps[0].tags)
        assertEquals(listOf(Tag("ガードが停止しました: 同じ操作を繰り返しています", TagTone.Bad)), ui.steps[1].tags)
        assertEquals(listOf(Tag("確認に失敗しました", TagTone.Bad)), ui.steps.single { it.step == 3 }.tags)
        val four = ui.steps.single { it.step == 4 }
        assertEquals(listOf(Tag("ガードの警告: 操作の失敗が続いています", TagTone.Warn), Tag("ガードの警告", TagTone.Warn)), four.tags)
        assertEquals("エラー", ui.steps.single { it.step == 5 }.result)
        assertFalse(ui.toString().contains("brand_new") || ui.toString().contains("some_new_code"))
        assertEquals("確認に失敗しました", ui.header.verdict)
        assertEquals("未完了", ui.header.outcome)
    }

    @Test fun missingRunShowsExpired() {
        assertEquals(TimelineUi.Expired, RunTimelineViewModel(store, "20250101-000000-abcdef").load())
        assertEquals(TimelineUi.Expired, RunTimelineViewModel(store, "../../etc").load())
        val id = run("x") { event(RunEvent.TaskEnded(clock, "cancelled", "")) }
        tmp.root.resolve("files/runs/$id").deleteRecursively()
        assertEquals(TimelineUi.Expired, RunTimelineViewModel(store, id).load())
    }

    @Test fun maskedFieldsStayMasked() {
        val key = "sk-" + "a".repeat(30)
        val id = run("登录 $key") {
            val args = Redactor.args(Verb.TypeText, """{"ref":"@e2","text":"hunter2"}""", secretField = true)
            event(RunEvent.ModelReply(clock, 1, Redactor.text("我输入密码 $key"), "phone_type_text", args, null, null, null))
            event(RunEvent.ToolResult(clock, 1, "phone_type_text", """{"ok":true,"secret":true}""", true, null))
        }
        val ui = ready(id)
        val all = ui.toString()
        assertFalse(all.contains("hunter2"))
        assertFalse(all.contains(key))
        assertEquals(Redactor.MASK, ui.steps.single().detail)
        assertTrue(ui.steps.single().said.contains(Redactor.MASK))
        assertTrue(ui.header.goal.contains(Redactor.MASK))
        assertEquals("終了していません", ui.header.outcome)
    }
}
