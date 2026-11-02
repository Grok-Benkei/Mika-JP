package com.wangzan101.mika.core.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * `files/sandbox/` このツリーの所有者: ツリーに書き込みたい人は、まずここで許可を得る必要があります。プロセス内のコピーはアプリによって与えられ、構築パラメーターを介して書き込まれる場所に渡されます。
 *
 * - [exclusive]: ツリーの構造を変更するか、部分全体を削除します (ルート ファイル システムのインストール/解凍/変更、ワークスペースのリセット、クリアまたは削除、アプリ `SandboxDirs.exclusiveWrite`)。私がそれを受け取ったとき、他に書いている人はいませんでした。
 * - [shared]: 既存のツリーに項目を追加します (スキルの同期、コマンドの実行、Web ページのダウンロード、添付ファイルのインポート)。同時にたくさんあることもありますが、
 *   は [exclusive] と相互排他的です。リセットが途中で完了すると、誰もディレクトリを再度作成しなくなります (この場合、最終的な rmdir は ENOTEMPTY になります)。
 *
 * 誰かが [exclusive] を待っているとき、新しい [shared] もその後ろにキューに入れられ、リセットがコマンドの安定した流れによって枯渇することはありません。
 * 同じコルーチン チェーンが既に保持されている場合 (いずれかのタイプ)、直接解放されます。ネストされた呼び出し自体はロックされません。
 *
 * - [lease]: 常駐プロセス (サンドボックス内の stdio MCP サーバー) は共有を使用します。生きている限り奪うことはできますが、取り戻すこともできます。
 *   [exclusive] は、入口を取得するとすぐにすべてのリースを取り消します (所有者にプロセスを停止し、その場でライセンスを解放するように通知します)。常駐プロセスによって数十分間ブロックされることはありません。
 *   リースが欠落しているため、リセットを防ぐことができません。ルート ファイル システムを変更してワークスペースをクリアすると、プロセスは実行できなくなります。削除されたディレクトリで実行し続けるよりも、停止する方が良いでしょう。
 */
class SandboxTree {
    /** は取得後にのみ開始できます。[shared] は短期間だけ保持され、[exclusive] はずっと保持されるため、ライターがキューに並ぶと、新しいリーダーも待機することになります。 */
    private val entry = Mutex()
    /** は、使用時に [shared] をロックします。 */
    private val noShared = Mutex()
    private val count = Mutex()
    private var holders = 0

    /** このコルーチン チェーンは現時点でどのツリーに対する権限を保持していますか? */
    private class Held(val trees: Set<SandboxTree>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private suspend fun heldTrees() = currentCoroutineContext()[Held]?.trees.orEmpty()

    suspend fun <T> exclusive(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        return entry.withLock {
            revokeLeases()
            noShared.withLock { withContext(Held(held + this)) { block() } }
        }
    }

    /** 登録済みリース (まだキューにあるものを含む)。 [exclusive]は[entry]を押しながら1つずつ取り返します。 */
    private val leases = LinkedHashSet<Lease>()

    /**
     * が長期間保持していた共有ライセンスは、[Lease.close] が使用されると解放されます。 [exclusive] を使用する場合は、最初に [onRevoke] を調整します (IO スレッドでは、ブロックしてプロセスが終了するのを待つことができます) その後、
     * はライセンスを解放します。その後、ホルダーはエラーなく通常どおり閉じることができます。キュー期間中に取り戻された場合は、[exclusive] 以降に再度取得できます。引き渡されたリースは、受け取った時点ではまだ返却されていません。
     * 所有者は、リースを取得した後に開始することを確認する必要があります。 [Lease.revoked]: 引き出しは、引き出しを取得してから開始するまでの間に行われる可能性があります。
     */
    suspend fun lease(onRevoke: () -> Unit): Lease {
        check(this !in heldTrees()) { "lease() must not be called while holding the tree" }
        while (true) {
            val lease = Lease(onRevoke)
            // 最初に登録してから列に並びます。入り口で列に並んでいるリースも収集でき、許可を取得しても登録しない間にギャップがなくなり、リセットがブロックされます。
            synchronized(leases) { leases += lease }
            val acquired = CompletableDeferred<Boolean>()
            val holder = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    shared {
                        acquired.complete(lease.markHeld())
                        lease.released.await()
                    }
                } finally {
                    synchronized(leases) { leases -= lease }
                    acquired.complete(false)
                }
            }
            val ok = try {
                acquired.await()
            } catch (e: Throwable) {
                lease.close()
                holder.cancel()
                throw e
            }
            if (ok) return lease
        }
    }

    private suspend fun revokeLeases() {
        val all = synchronized(leases) { leases.toList() }
        if (all.isEmpty()) return
        withContext(Dispatchers.IO + NonCancellable) { for (l in all) l.revoke() }
    }

    /** [lease] を参照。 */
    class Lease internal constructor(private val onRevoke: () -> Unit) : java.io.Closeable {
        internal val released = CompletableDeferred<Unit>()
        private var held = false

        /** は [exclusive] によって撤回されました。撤回後は許可が解除され、所有者はツリーに書き込みを行うことができなくなります。 */
        @Volatile var revoked = false
            private set

        /** ライセンスを取得するときに呼び出されます。以前に取り消されている場合は false を返します。 */
        internal fun markHeld(): Boolean = synchronized(this) { if (revoked) { released.complete(Unit); false } else { held = true; true } }

        internal fun revoke() {
            val wasHeld = synchronized(this) {
                if (revoked || released.isCompleted) return
                revoked = true
                held
            }
            // まだキューに残っている場合は、停止する必要はありません。この時間が経過すると再びキューに入れられ、ピックアップされます。
            if (wasHeld) runCatching { onRevoke() }
            released.complete(Unit)
        }

        override fun close() { released.complete(Unit) }
    }

    suspend fun <T> shared(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        entry.withLock { count.withLock { if (holders++ == 0) noShared.lock() } }
        try {
            return withContext(Held(held + this)) { block() }
        } finally {
            withContext(NonCancellable) { count.withLock { if (--holders == 0) noShared.unlock() } }
        }
    }
}
