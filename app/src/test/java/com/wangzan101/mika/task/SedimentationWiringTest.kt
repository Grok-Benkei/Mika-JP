package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.knownTools

@OptIn(ExperimentalCoroutinesApi::class)
class SedimentationWiringTest {
    @get:Rule val texts = ChineseTexts()

    private val catalog = GuideCatalog.loadBundled()
    private val tools = setOf("phone_launch_app", "phone_observe", "phone_tap", "phone_type_text", "hand_to_user", "finish")
    private val draftMd = "---\nname: send-wechat-message\ndisplay: 发微信消息\ndescription: 给指定联系人发一条微信消息\napps: [com.tencent.mm]\nsource: learned\n---\n" +
        "## 入口\n用 `phone_launch_app` 打开。\n## 步骤\n1. 点搜索\n## 容易出错的地方\n同名群聊。\n## 怎样算办成(核实证据)\n消息出现在聊天里。\n## 什么时候停下交给用户\n要登录时。"

    private fun events(verdict: String = "pass", extra: List<RunEvent> = emptyList()): List<RunEvent> {
        var t = 0L
        val ev = mutableListOf<RunEvent>(RunEvent.TaskStarted(++t, "发微信", "m"))
        listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_type_text", "phone_tap", "phone_tap").forEachIndexed { i, tool ->
            ev += RunEvent.ModelReply(++t, i + 1, "", tool, "{}", null, null, null)
            ev += RunEvent.ToolResult(++t, i + 1, tool, """{"ok":true}""", true, null)
        }
        ev += extra
        ev += RunEvent.Verifier(++t, verdict, """{"reason":"","checks":[]}""")
        ev += RunEvent.TaskEnded(++t, "succeeded", "ok")
        return ev
    }

    private fun learning(skills: MapSkills, learn: Boolean, autoApprove: Boolean = false, model: TextModel = TextModel(draftMd)) =
        RunLearning(MemorySwitches(learnSkills = learn), null, skills, GuideCatalog.withPersonal(catalog, skills), null, SkillDrafter(model), { "draft-1" }, autoApprove)

    private fun after(ev: List<RunEvent>, end: TaskState = TaskState.Succeeded("ok"), outcome: RunOutcome = RunOutcome.Done) = RunAftermath(
        "c1", "run-1", "给老妈发微信", end, emptyList(), emptyList(), emptyList(), tools, knownTools(), events = { ev }, outcome = outcome,
    )

    @Test fun draftOnlyWhenEnabledAndEligible() {
        val off = MapSkills()
        val offModel = TextModel(draftMd)
        runBlocking { learning(off, learn = false, model = offModel).learn(after(events())) }
        assertTrue(off.drafts.isEmpty() && offModel.requests.isEmpty())

        // 検証を見ずに結果を見ることを学びましょう。検証に立候補せず、成功したと判断された人もドラフトされます。検証に合格したが不合格となった者はドラフトされない
        val unreviewed = MapSkills()
        runBlocking { learning(unreviewed, learn = true).learn(after(events(), end = TaskState.Succeeded("ok", verified = false, reviewed = false))) }
        assertEquals(listOf("draft-1"), unreviewed.drafts.keys.toList())
        val doubtful = MapSkills()
        runBlocking { learning(doubtful, learn = true).learn(after(events(), end = TaskState.Succeeded("ok", verified = false, note = "x"))) }
        assertTrue(doubtful.drafts.isEmpty())

        // の回答は検証されましたが、一部のみが完了したか、完了できなかったか、または結論が出ていません: 下書きされていません
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) {
            val notDone = MapSkills()
            val model = TextModel(draftMd)
            runBlocking { learning(notDone, learn = true, model = model).learn(after(events(), outcome = o)) }
            assertTrue(o.wire, notDone.drafts.isEmpty() && model.requests.isEmpty())
        }

        // はリプレイ ルートを選択しています: ドラフトは不要です
        val replayed = MapSkills()
        runBlocking { learning(replayed, learn = true).learn(after(events(extra = listOf(RunEvent.Replay(50, "r", 1, 3, true, ""))))) }
        assertTrue(replayed.drafts.isEmpty())

        val on = MapSkills()
        val notes = runBlocking { learning(on, learn = true).learn(after(events())) }
        assertEquals(listOf("draft-1"), on.drafts.keys.toList())
        assertEquals("Mika がスキルを学習しました: 给指定联系人发一条微信消息", notes.skillNotes.single().text)
        assertEquals("draft-1", notes.skillNotes.single().draftId)
    }

    // モデルは、これは確認すべきドラフトの更新であることを示しています。そのドラフトの番号を書き戻し、新しいドラフトを開始しないでください。
    @Test fun draftUpdatingPendingDraftReplacesIt() {
        val skills = MapSkills()
        skills.putPending("draft-0", PersonalSkill("send-wechat-message", draftMd, SkillStatus.pending, "r0", null, 0, emptyList()))
        val updated = draftMd.replace("source: learned", "updates: send-wechat-message\nsource: learned")
        val notes = runBlocking { learning(skills, learn = true, model = TextModel(updated)).learn(after(events())) }
        assertEquals(listOf("draft-0"), skills.drafts.keys.toList())
        assertEquals("run-1", skills.drafts.getValue("draft-0").learnedFrom)
        assertEquals("draft-0", notes.skillNotes.single().draftId)
    }

    @Test fun evalAutoApproveHook() {
        val skills = MapSkills()
        val notes = runBlocking { learning(skills, learn = true, autoApprove = true).learn(after(events())) }
        assertTrue(skills.drafts.isEmpty())
        assertEquals(SkillStatus.active, skills.get("send-wechat-message")!!.status)
        assertTrue(notes.skillNotes.isEmpty())
    }

    @Test fun draftingRunsAfterResultNotification() = runTest {
        val order = mutableListOf<String>()
        RunLearning.launchAfter(this, notify = { order += "notify" }) { order += "learn" }
        assertEquals(listOf("notify"), order)
        runCurrent()
        assertEquals(listOf("notify", "learn"), order)
    }

    @Test fun usedSkillOutcomeRecordedAndSuspensionNoted() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        val used = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed"))
        val notes = runBlocking {
            learning(skills, learn = true).learn(after(events(verdict = "fail", extra = used), TaskState.Failed("核查未通过:消息没发出去", FailKind.GaveUp)))
        }
        assertEquals(SkillStatus.suspended, skills.get("wechat-msg")!!.status)
        assertEquals(listOf("verifier_rejected"), skills.get("wechat-msg")!!.outcomes)
        assertEquals("wechat-msg", notes.skillNotes.single().suspended)
        // 個人のスキルに従って作成: ドラフトではなく結果のみを記憶
        val ok = MapSkills(skill)
        runBlocking { learning(ok, learn = true).learn(after(events(extra = used))) }
        assertEquals(listOf("verified"), ok.get("wechat-msg")!!.outcomes)
        assertTrue(ok.drafts.isEmpty())
        // 検査には行っていない、判断されて行われた：それに基づいて行われた
        val unreviewed = MapSkills(skill)
        runBlocking { learning(unreviewed, learn = true).learn(after(events(extra = used), TaskState.Succeeded("ok", verified = false, reviewed = false))) }
        assertEquals(listOf("verified"), unreviewed.get("wechat-msg")!!.outcomes)
        // は正直に答え、一部しか実行できなかったと言いました。自分のスキルどおりに実行できなかったため、失敗として記録しました。
        val partial = MapSkills(skill)
        runBlocking { learning(partial, learn = true).learn(after(events(extra = used), outcome = RunOutcome.Partial)) }
        assertEquals(listOf("failed"), partial.get("wechat-msg")!!.outcomes)
        // キャンセルは記憶されません
        val cancelled = MapSkills(skill)
        runBlocking { learning(cancelled, learn = false).learn(after(events(extra = used), TaskState.Cancelled)) }
        assertNull(cancelled.get("wechat-msg")!!.outcomes.firstOrNull())
    }

    // トライアル中の個人スキル: エージェントは skill_read を使用して読み取り、今回完了し、正式になりました
    @Test fun viewedTrialSkillPromotesOnDoneRun() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.trial, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        runBlocking { learning(skills, learn = true).learn(after(events(extra = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed"))))) }
        assertEquals(SkillStatus.active, skills.get("wechat-msg")!!.status)
        assertEquals(listOf("verified"), skills.get("wechat-msg")!!.outcomes)
        assertTrue(skills.drafts.isEmpty())
    }

    // 古い実行レコード (先頭に全文が挿入されている) に挿入されたものは引き続き読み取られ、結果は読み取られたかのように記録されます。
    @Test fun oldInjectedRecordCountsAsUse() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        val rejected = TaskState.Failed("核查未通过:没发出去", FailKind.GaveUp)
        val notes = runBlocking { learning(skills, learn = false).learn(after(events(verdict = "fail", extra = listOf(RunEvent.SkillUsed(40, "wechat-msg", "injected"))), rejected)) }
        assertEquals(listOf("verifier_rejected"), skills.get("wechat-msg")!!.outcomes)
        assertEquals(SkillStatus.suspended, skills.get("wechat-msg")!!.status)
        assertEquals("wechat-msg", notes.skillNotes.single().suspended)
    }
}
