package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.LearnedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppKnowledgeFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val facts = "- 首页顶部有搜索入口。\n- 「我的」页里有设置。"
    private fun draft(id: String, md: String = facts, lesson: Boolean = false, revises: String? = null) =
        AppKnowledgeDraft(id, "qq", "com.tencent.mobileqq", md, "run-$id", AppKnowledgeStage.Draft, 1, lesson = lesson, revises = revises)

    @Test fun draftTrialVerifiedAndArchive() {
        val files = AppKnowledgeFiles(tmp.root)
        assertTrue(files.putPending(draft("d1")))
        // 同じアプリには確認できるものが最大 1 つあります
        assertFalse(files.putPending(draft("d2")))
        assertTrue(files.notes().isEmpty())
        assertTrue(File(tmp.root, "knowledge/app-drafts/d1/APP.md").isFile)
        assertTrue(files.approve("d1"))
        assertTrue(files.notes().getValue("qq").trial)
        assertEquals(facts, files.notes().getValue("qq").text)
        assertEquals(AppKnowledgeStage.Verified, files.recordTrial("qq", true)?.stage)
        assertFalse(files.notes().getValue("qq").trial)
        assertTrue(File(tmp.root, "knowledge/app-drafts/archive").listFiles().orEmpty().isNotEmpty())
    }

    private fun verifiedD1(): AppKnowledgeFiles {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1")); files.approve("d1"); files.recordTrial("qq", true)
        return files
    }

    @Test fun revisionReplacesTheOneInUseOnceItWorks() {
        val files = verifiedD1()
        // 使用しているものは新しいリビジョンをブロックしません
        assertTrue(files.putPending(draft("d2", "$facts\n- 消息页右上角的加号能建群。", revises = "d1")))
        assertEquals(facts, files.notes().getValue("qq").text)
        assertTrue(files.approve("d2"))
        // 試用期間中にリビジョンを挿入し、古いものを保持します
        assertEquals("d2", files.inUse("qq")?.id)
        assertTrue(files.notes().getValue("qq").let { it.trial && it.text.contains("加号") })
        assertEquals(AppKnowledgeStage.Verified, files.all().single { it.id == "d1" }.stage)
        assertEquals(AppKnowledgeStage.Verified, files.recordTrial("qq", true)?.stage)
        assertEquals(AppKnowledgeStage.Archived, files.all().single { it.id == "d1" }.stage)
        assertEquals("d2", files.inUse("qq")?.id)
    }

    @Test fun badTrialOfRevisionRestoresThePreviousNotes() {
        val files = verifiedD1()
        files.putPending(draft("d2", "$facts\n- 消息页右上角的加号能建群。", revises = "d1")); files.approve("d2")
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertEquals("d1", files.inUse("qq")?.id)
        assertEquals(LearnedNote(facts, trial = false), AppKnowledgeFiles(tmp.root).notes().getValue("qq"))
        // ユーザーにも同じことが当てはまります
        files.putPending(draft("d3", "- 别的。", revises = "d1")); files.approve("d3"); files.reject("d3")
        assertEquals("d1", files.inUse("qq")?.id)
    }

    @Test fun staleRevisionIsRefused() {
        val files = verifiedD1()
        // は両方のランで d1 に対してドラフトされました。最初に受け取ったコピーが使用された後、もう一方のコピーは廃止されました。
        assertTrue(files.putPending(draft("r1", "- 一。", revises = "d1"))); assertTrue(files.approve("r1"))
        assertFalse(files.putPending(draft("r2", "- 二。", revises = "d1")))
        // 保存されたリビジョンは期限切れです (改訂されたコピーはアーカイブされます): 受け入れられず、その場でアーカイブされ、このアプリの新しいドラフトはブロックされなくなります
        assertTrue(files.putPending(draft("r3", "- 三。", revises = "r1")))
        files.reject("r1")
        assertEquals(AppKnowledgeStage.Archived, files.all().single { it.id == "r3" }.stage)
        assertFalse(files.approve("r3"))
        assertEquals("d1", files.inUse("qq")?.id)
        assertTrue(files.putPending(draft("r4", "- 四。", revises = "d1")))
    }

    @Test fun badTrialArchivesDraftsThatRevisedIt() {
        val files = verifiedD1()
        files.putPending(draft("r1", "- 一。", revises = "d1")); files.approve("r1")
        assertTrue(files.putPending(draft("r2", "- 二。", revises = "r1")))
        // トライアルは機能しません。r1 はアーカイブされ、d1 に置き換えられます。r1 に対してドラフトされた r2 は期限切れです。
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertEquals(AppKnowledgeStage.Archived, files.all().single { it.id == "r2" }.stage)
        assertFalse(files.approve("r2"))
        assertTrue(files.putPending(draft("r3", "- 三。", revises = "d1")))
        assertTrue(files.approve("r3"))
        assertEquals("r3", files.inUse("qq")?.id)
    }

    @Test fun goodTrialKeepsDraftsThatStillMatch() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("a1")); files.approve("a1")
        assertTrue(files.putPending(draft("a2", revises = "a1")))
        // は正式バージョンに変換中のため、現在のシェアを変更する必要はありません。a2 はまだ確認および承認される予定です。
        assertEquals(AppKnowledgeStage.Verified, files.recordTrial("qq", true)?.stage)
        assertEquals(AppKnowledgeStage.Draft, files.all().single { it.id == "a2" }.stage)
        assertTrue(files.approve("a2"))
    }

    @Test fun failedTrialIsArchivedAndNeverInjected() {
        val files = AppKnowledgeFiles(tmp.root)
        assertTrue(files.putPending(draft("d1")))
        files.approve("d1")
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertTrue(files.notes().isEmpty())
        assertEquals(AppKnowledgeStage.Archived, AppKnowledgeFiles(tmp.root).all().single().stage)
    }

    @Test fun lessonSurvivesOneBadTrial() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1", lesson = true)); files.approve("d1")
        assertEquals(AppKnowledgeStage.Trial, files.recordTrial("qq", false)?.stage)
        assertTrue(AppKnowledgeFiles(tmp.root).all().single().lesson)
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertNull(files.inUse("qq"))
    }

    @Test fun olderFullAppMdDraftsInjectOnlyTheirBody() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1", "---\nname: qq\npackages: [com.tencent.mobileqq]\n---\n## 是什么\nQQ 应用。")); files.approve("d1")
        assertEquals("## 是什么\nQQ 应用。", files.notes().getValue("qq").text)
    }
}
