package com.wangzan101.mika.core.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 常駐プロセスのリース: 通常、共有などの排他的なものはブロックされます。専用のものは来たらすぐに回収されブロックされません。 */
class SandboxTreeLeaseTest {
    @Test fun exclusiveRevokesLeaseInsteadOfWaiting() = runBlocking {
        val tree = SandboxTree()
        val stopped = AtomicInteger()
        val lease = tree.lease { stopped.incrementAndGet() }
        val r = withTimeout(5_000) { tree.exclusive { 42 } }
        assertEquals(42, r)
        assertEquals(1, stopped.get())
        assertTrue(lease.revoked)
        lease.close() // が取り消された後、所有者は通常どおりエラーなく閉じることができます。 独占期間中に
        assertEquals(1, stopped.get())
    }

    @Test fun closedLeaseFreesThePermitWithoutRevoking() = runBlocking {
        val tree = SandboxTree()
        val stopped = AtomicInteger()
        val lease = tree.lease { stopped.incrementAndGet() }
        lease.close()
        withTimeout(5_000) { tree.exclusive { } }
        assertEquals(0, stopped.get())
        assertFalse(lease.revoked)
    }

    /** によって要求されたリースはその次にランクされており、取得時に取り消されていませんでした。 */
    @Test fun leaseRequestedDuringExclusiveWaitsAndIsFresh() = runBlocking {
        val tree = SandboxTree()
        val stopped = AtomicInteger()
        val inside = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ex = async(Dispatchers.Default) { tree.exclusive { inside.complete(Unit); release.await() } }
        inside.await()
        val pending = async(Dispatchers.Default) { tree.lease { stopped.incrementAndGet() } }
        delay(100)
        assertFalse(pending.isCompleted)
        release.complete(Unit)
        ex.await()
        val lease = withTimeout(5_000) { pending.await() }
        assertFalse(lease.revoked)
        assertEquals(0, stopped.get())
        lease.close()
    }

    /** 通常の共有静止ブロックは排他的です。リースはそれを変更しません。 */
    @Test fun plainSharedStillBlocksExclusive() = runBlocking {
        val tree = SandboxTree()
        val inside = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sh = async(Dispatchers.Default) { tree.shared { inside.complete(Unit); release.await() } }
        inside.await()
        val ex = async(Dispatchers.Default) { tree.exclusive { 1 } }
        delay(100)
        assertFalse(ex.isCompleted)
        release.complete(Unit)
        sh.await()
        assertEquals(1, withTimeout(5_000) { ex.await() })
    }
}
