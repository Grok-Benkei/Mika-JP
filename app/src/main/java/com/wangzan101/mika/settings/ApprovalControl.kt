package com.wangzan101.mika.settings

import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.gate.ConfirmationGate

/**
 * タスク独自の承認設定: 自律的かどうかは作業開始時にグローバルに決定され、開発者向けオプション ([set]) の「自律モード」によって変更されます。
 * 要求するときは、最初に支払いだけを要求します。独立しているときは、支払いを要求することさえありません。
 */
class RunApproval(startAutonomous: Boolean) : ApprovalSettings {
    /** は開始時に自律的です。このタスクには確認レベルがなく、request_confirmation も与えられません。自律性は途中でオフになり、次のタスクに任せられます。 */
    val startedAutonomous: Boolean = startAutonomous
    @Volatile private var auto = startAutonomous

    override fun autonomous(): Boolean = auto

    fun set(autonomous: Boolean) {
        auto = autonomous
    }

    /** このタスク (およびそのサブタスク) で使用される支払い確認レベルは次のとおりです。作業開始時のリク​​エストに応じてのみ利用可能であり、毎回新しいレベルです。 */
    fun gate(): ConfirmationGate? = if (startedAutonomous) null else ConfirmationGate()
}

/**
 * クエリ/自律性はグローバル スイッチであり、デフォルトのクエリである [ApprovalStore.autonomous] があります。各タスクの開始時に独自のコピー ([start]) を取得します。
 * 変更スイッチ ([setAutonomous]): 今後のタスクを管理するために保存し、実行中のタスクを変更します (照会から自律に変更すると、次のステップから支払いを要求されなくなります)。
 */
class ApprovalControl(private val store: ApprovalStore) {
    private val runs = mutableSetOf<RunApproval>()

    /**
     * [remote] は true です。これは、MCP を通じてコン​​ピュータによって与えられるタスクです。スイッチに関係なく、常にクエリを押して開始し、スイッチに応じて変更されることはありません。
     * このタイプのタスクでは支払いステップが実行されることはありません (レビューでは、携帯電話以外から開始されたすべてのリクエストが拒否されます)。リモート エンドがそれを放棄する方法はありません。
     */
    @Synchronized fun start(remote: Boolean = false): RunApproval =
        if (remote) RunApproval(false) else RunApproval(store.autonomous.value).also { runs += it }

    @Synchronized fun end(run: RunApproval) {
        runs -= run
    }

    @Synchronized fun setAutonomous(on: Boolean) {
        store.setAutonomous(on)
        runs.forEach { it.set(on) }
    }
}
