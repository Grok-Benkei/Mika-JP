package com.wangzan101.mika.task

import com.wangzan101.mika.cap.web.TabBudget
import com.wangzan101.mika.core.uistate.AppWait
import com.wangzan101.mika.takeover.TakeoverQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReentrantLock

/** プロセス内の共有リソース エントリ。 TaskCenter は実行キューを管理し、相互に競合するリソースを管理します。 */
class ResourceScheduler(
    val tabBudget: TabBudget = TabBudget(),
    val takeoverQueue: TakeoverQueue = TakeoverQueue(),
    val clipboard: ReentrantLock = ReentrantLock(true),
) {
    /** アプリ (パッケージ名、別のユーザーの場合は「パッケージ名@ユーザー」と表記) → タスクを占有します。 */
    private val apps = HashMap<String, String>()
    /** は別のタスクを待機しています。アプリのタスク→待機中のアプリを解放します。 */
    private val waiting = HashMap<String, String>()
    /** タスクが占有しているアプリを解放する場合、1 つ追加します。待機中のタスクは、次のポーリングを待たずにすぐにそれを確認します。 */
    private val released = MutableStateFlow(0L)

    /** は同じロックを占有してチェックインします。アプリは、別のタスクによって解放または終了されたときに引き継ぐことができます。 */
    @Synchronized
    fun reserveApp(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        if (!appFree(packageName, owner, holderActive, stillUsed)) return false
        apps[packageName] = owner
        return true
    }

    /** [owner] 現時点で占有できますか? [packageName]: 誰も占有していません。私自身が占有しています。占有されているタスクはもう存在しないか、もう使用されていません ([stillUsed] は false)。チェックだけで会計はしません。 */
    @Synchronized
    fun appFree(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        val holder = apps[packageName]
        return holder == null || holder == owner || !holderActive(holder) || !stillUsed()
    }

    @Synchronized fun releaseOwner(owner: String) {
        waiting.remove(owner)
        if (apps.values.removeAll { it == owner }) released.value++
    }

    @Synchronized fun appHolder(packageName: String): String? = apps[packageName]

    /**
     * [owner] が [packageName] の解放を待ち、最大で [timeoutMs]。[free] は呼び出し側の完全な判定（登録以外に、他の画面にそれがあるかも見る）、
     * [pollMs] ごとに確認し、タスクが App を解放したときは直ちに再確認する。待つだけで占有はしない: 待機が終わったら呼び出し側が自分で占有し、開く。
     *
     * 互いに待ち合う場合は待たない: それを占有しているタスクが（「誰が誰の手にある App を待っているか」を辿っていくと）最終的に [owner] の手にある App を待っているとき、
     * [AppWait.WouldDeadlock] を返す; 確認と登録は同じロック内で行い、2つのタスクが同時に待ち始めたとき、後から来た方は先行する方を見られる。
     * [holderActive] 占有されたタスクはまだ存在します (不在は占有としてカウントされません)。
     */
    suspend fun awaitApp(
        packageName: String, owner: String, timeoutMs: Long,
        holderActive: (String) -> Boolean, free: suspend () -> Boolean, pollMs: Long = POLL_MS,
    ): AppWait {
        if (!startWaiting(owner, packageName, holderActive)) return AppWait.WouldDeadlock
        try {
            return withTimeoutOrNull(timeoutMs) {
                while (!free()) {
                    val seen = released.value
                    withTimeoutOrNull(pollMs) { released.first { it != seen } }
                }
                AppWait.Free
            } ?: AppWait.TimedOut
        } finally {
            stopWaiting(owner)
        }
    }

    @Synchronized private fun startWaiting(owner: String, packageName: String, holderActive: (String) -> Boolean): Boolean {
        if (wouldDeadlock(owner, packageName, holderActive)) return false
        waiting[owner] = packageName
        return true
    }

    @Synchronized private fun stopWaiting(owner: String) {
        waiting.remove(owner)
    }

    /** [packageName] の占有者から始めて、「誰のアプリを待っていますか?」に従ってください。そして[owner]に戻ってお互いを待ちます。 */
    private fun wouldDeadlock(owner: String, packageName: String, holderActive: (String) -> Boolean): Boolean {
        fun holderOf(pkg: String) = apps[pkg]?.takeIf(holderActive)
        var holder = holderOf(packageName)?.takeIf { it != owner } ?: return false
        val seen = HashSet<String>()
        while (seen.add(holder)) {
            val next = holderOf(waiting[holder] ?: return false) ?: return false
            if (next == owner) return true
            holder = next
        }
        return false
    }

    private companion object {
        /** 画面上の他のアプリを閉じても、ここでは通知されません: 長い時間が経った後、もう一度見てください。 モデルが原因で */
        const val POLL_MS = 1_000L
    }
}
