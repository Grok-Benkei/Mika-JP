package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.agent.TokenEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * は完全なメモリ インジェクションを備えています (製品のデフォルト: 適合する場合は除外されず、適合しない場合は取得のために返されます)。
 */
class MemoryContextFullModeTest {
    @Test fun fullGivesEveryActiveItem() {
        // はゴールという単語とは何の関係もなく、以前であれば検索によって破棄されていました。
        val items = (1..12).map { mem("无关事实第${it}条:用户周末去爬山") }
        val block = MemoryContext.build(items, goal = "帮我点个外卖", lastUserTurn = null, mode = MemoryContext.Mode.Full)
        assertEquals(items.map { it.id }.toSet(), block.usedIds.toSet())
        assertEquals(12, block.usedIds.size)
    }

    @Test fun autoGivesEveryItemWhenItFits() {
        val items = (1..30).map { mem("事实第${it}条") }
        val block = MemoryContext.build(items, goal = "随便", lastUserTurn = null, mode = MemoryContext.Mode.Auto)
        assertEquals(30, block.usedIds.size)
    }

    @Test fun autoFallsBackToRelevantWhenTooBig() {
        val filler = "很".repeat(120)
        // 最初の 8 項目のみが重要です。すべてに同じ単語が含まれているという理由だけで項目を含めないのではなく、検索に返すときに項目を選択する必要があります。
        val items = (1..120).map { mem("第${it}条$filler", keywords = if (it <= 8) listOf("外卖") else emptyList()) }
        val block = MemoryContext.build(items, goal = "点外卖", lastUserTurn = null, mode = MemoryContext.Mode.Auto)
        assertTrue(block.usedIds.size in 1..MemoryContext.MAX_RELEVANT)
        val message = block.message
        assertNotNull(message)
        assertTrue(TokenEstimate.of(message!!) <= MemoryContext.MAX_TOKENS)
    }

    @Test fun fullDropsOldestFactsBeforePinned() {
        val filler = "很".repeat(120)
        val pinned = mem("置顶的住址在张江$filler", pinned = true)
        val facts = (1..80).map { mem("第${it}条$filler") }
        val block = MemoryContext.build(facts + pinned, goal = "随便", lastUserTurn = null, mode = MemoryContext.Mode.Full)
        assertTrue(pinned.id in block.usedIds)
        assertTrue(TokenEstimate.of(block.message!!) <= MemoryContext.FULL_MAX_TOKENS)
        assertTrue(block.usedIds.size < facts.size)
    }

    @Test fun pendingNeverInjected() {
        val pending = mem("还没批准的做法", kind = MemoryKind.procedure, status = MemoryStatus.pending)
        assertNull(MemoryContext.build(listOf(pending), "随便", null, mode = MemoryContext.Mode.Full).message)
    }

    // 自動モードをインストールできる場合、すべての方法は次のとおりです。更新時間に応じて最初のいくつかの項目を切り捨て、今回役に立ったものを捨てても構いません。
    @Test fun autoGivesEveryProcedureWhenItFits() {
        val procs = (1..6).map { mem("做法$it", kind = MemoryKind.procedure) }
        val block = MemoryContext.build(procs, "随便", null, mode = MemoryContext.Mode.Auto)
        assertEquals(procs.map { it.id }.toSet(), block.usedIds.toSet())
    }

    @Test fun fullKeepsProceduresCapped() {
        val procs = (1..6).map { mem("做法$it", kind = MemoryKind.procedure) }
        val block = MemoryContext.build(procs, "随便", null, mode = MemoryContext.Mode.Full)
        assertEquals(MemoryContext.MAX_PROCEDURES, block.usedIds.size)
    }
}
