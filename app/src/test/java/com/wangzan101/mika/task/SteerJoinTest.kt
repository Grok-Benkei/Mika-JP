package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Test

class SteerJoinTest {
    private val notes = listOf(
        SteerNote("m1", "改成查明天", SteerState.Received),
        SteerNote("m2", "顺便看看后天", SteerState.Received),
    )

    // 2 文の中断のバッチが一緒にモデルに与えられます。中断された段落は最初の文にのみあり、2 番目の文にはありません。
    @Test fun interruptedReplyGoesToTheFirstSteerOfTheBatch() {
        val join = SteerJoin()
        join.interrupted("今天晴")
        val after = join.joined(join.joined(notes, "m1"), "m2")
        assertEquals(listOf(SteerState.Joined, SteerState.Joined), after.map { it.state })
        assertEquals(listOf("今天晴", null), after.map { it.interrupted })
    }

    // 中断がない場合のみ方向を変更します
    @Test fun steerWithoutInterruptionOnlyChangesState() {
        val after = SteerJoin().joined(notes, "m2")
        assertEquals(listOf(SteerState.Received, SteerState.Joined), after.map { it.state })
        assertEquals(listOf(null, null), after.map { it.interrupted })
    }

    // 一度使用するとクリア: コメントの次のバッチには最後の段落が含まれません。
    @Test fun interruptedReplyIsUsedOnce() {
        val join = SteerJoin()
        join.interrupted("今天晴")
        val first = join.joined(notes, "m1")
        val second = join.joined(first + SteerNote("m3", "算了", SteerState.Received), "m3")
        assertEquals(listOf("今天晴", null, null), second.map { it.interrupted })
    }

    // 中断前に終了した返信もこのバッチの最初の文にリンクされており、中断された返信とは別に記録されます。
    @Test fun finishedReplyGoesToTheFirstSteerAndIsUsedOnce() {
        val join = SteerJoin()
        join.finished("光合作用是……的过程。")
        val after = join.joined(join.joined(notes, "m1"), "m2")
        assertEquals(listOf("光合作用是……的过程。", null), after.map { it.finished })
        assertEquals(listOf(null, null), after.map { it.interrupted })
    }

    @Test fun remoteStatusExcludesLocalMessagesAndDistinguishesReceiptConsumptionAndDiscard() {
        val local = SteerNote("local", "phone-only text", SteerState.Joined)
        val remote = SteerNote("remote", "only today", SteerState.Received, remote = true)
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "received")), remoteSteers(listOf(local, remote)))
        val joined = SteerJoin().joined(listOf(local, remote), "remote")
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "joined")), remoteSteers(joined))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(remote.copy(state = SteerState.Cancelled))))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(local, remote), finished = true))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "joined")),
            remoteSteers(joined, finished = true))
    }

    // リモート介入は本来取り消すことができません。それが撤回された場合、それは報告されるだけで役に立たず、外部のステータスはなくなります。携帯電話での出金はコンピュータには報告されません
    @Test fun withdrawnMapsToNotAppliedAndLocalWithdrawalsStayHidden() {
        val remote = SteerNote("remote", "only today", SteerState.Withdrawn, remote = true)
        val local = SteerNote("local", "phone-only text", SteerState.Withdrawn)
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")), remoteSteers(listOf(local, remote)))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(local, remote), finished = true))
    }
}
