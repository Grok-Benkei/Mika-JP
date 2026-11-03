package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillOutcomesTest {
    @Test fun trialNeedsVerifiedUseBeforeBecomingActive() {
        val draft = personalSkill("trial-skill", status = SkillStatus.trial)
        assertEquals(SkillStatus.trial, SkillOutcomes.record(draft, SkillResult.unverified, followed = true).status)
        assertEquals(SkillStatus.active, SkillOutcomes.record(draft, SkillResult.verified, followed = true).status)
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(draft, SkillResult.verifier_rejected, followed = true).status)
    }
    private val s = personalSkill("wechat-msg")

    @Test fun keepsLast5() {
        var cur = s
        repeat(7) { cur = SkillOutcomes.record(cur, SkillResult.verified, followed = true) }
        assertEquals(SkillOutcomes.KEEP, cur.outcomes.size)
        assertEquals(SkillStatus.active, cur.status)
    }

    @Test fun twoOfLastThreeSuspends() {
        var cur = SkillOutcomes.record(s, SkillResult.unverified, followed = false)
        assertEquals(SkillStatus.active, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.verified, followed = false)
        assertEquals(SkillStatus.active, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.suspended, cur.status)
        assertEquals(listOf("unverified", "verified", "failed"), cur.outcomes)
    }

    @Test fun followedRejectionSuspendsImmediately() {
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(s, SkillResult.verifier_rejected, followed = true).status)
        // 守らない場合（あくまで推奨です）、使用をやめずに一度返品してください。
        assertEquals(SkillStatus.active, SkillOutcomes.record(s, SkillResult.verifier_rejected, followed = false).status)
    }

    @Test fun cancelledNotRecorded() {
        assertNull(SkillOutcomes.resultOf(TaskState.Cancelled, RunOutcome.Unknown))
        assertEquals(SkillResult.verified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = true), RunOutcome.Done))
        assertEquals(SkillResult.unverified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, note = "x"), RunOutcome.Done))
        // 判決は完了しましたが、検証は行われませんでした。検証と同様に良好であると見なされます（それに応じて、裁判は公式に変換されます）
        assertEquals(SkillResult.verified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, reviewed = false), RunOutcome.Done))
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, reviewed = false), RunOutcome.Unknown))
        assertEquals(SkillResult.verifier_rejected, SkillOutcomes.resultOf(TaskState.Failed("核查未通过:没发出去", FailKind.GaveUp), RunOutcome.Unknown))
        // は、それは行われておらず、一部だけが行われただけであると正直に答えました。それは失敗とみなされます。それは成功したとは判断されませんでした。効果的でも効果的でもありませんでした。
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Succeeded("找不到这个菜单", verified = true), RunOutcome.NotPossible))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Succeeded("只看了两条", verified = true), RunOutcome.Partial))
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = true), RunOutcome.Unknown))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Failed("找不到", FailKind.GaveUp), RunOutcome.Unknown))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Failed("步数用完", FailKind.StepLimit), RunOutcome.Unknown))
        // モデルエラー、動作環境の問題、動作保護停止：スキルや知識が有効かどうか説明できない
        for (k in listOf(FailKind.Model, FailKind.Environment, FailKind.Guard, FailKind.Interrupted))
            assertEquals(k.name, SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Failed("x", k), RunOutcome.Unknown))
    }
}
