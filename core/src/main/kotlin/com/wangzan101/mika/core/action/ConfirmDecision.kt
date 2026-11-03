package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.gate.Permit
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.run.RunProvenance

/** アクションが実行される前の承認の結論。 */
sealed interface Decision {
    /** 判定は必要ありません、または判定が下されます（支払い時のユーザーの同意を含みます）。 */
    data object Clear : Decision
    /** は解放されましたが、要素が見つかりません。トレースは実行記録に残されます。 */
    data class Traced(val detail: String) : Decision
    /** は、ポイントがどの要素上にあるかを見つけることができません。この情報源は盲点ではありません。 */
    data object Unresolved : Decision
    /**
     * 支出または [ask] が示す画面外の操作に今回の承認が必要だが、有効な承認がない。
     * [ask] があれば、確認できる呼び出し元がその場で本人へ確認する（[FollowUp.approve]）。
     */
    data class Required(val hit: Irreversible, val ask: ConfirmationRequest? = null) : Decision
    /** 承認した内容と今回の操作が一致せず、承認は無効になった。 */
    data class Mismatch(val hit: Irreversible, val reason: String) : Decision
    /** はお金がかかりますが、電話の隣のユーザーにこの実行を依頼することはできず、直接拒否しました。 */
    data class Denied(val hit: Irreversible) : Decision
    /** はミカ自身に行為をし、それを行いません。 */
    data object OwnApp : Decision
}

/**
 * 不可逆判定と承認の照合は、ここから [IrreversibleDetector] と [ConfirmationGate.permits] を呼ぶ。
 * 呼び出し元ごとの差は [OriginPolicy] にまとめ、確認の要否は [ApprovalPolicy.decide] で決める。
 */
class ConfirmDecision(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    private val risks: RiskJudge = ClassifiedRisk,
    private val recorder: ActionRecorder? = null,
    private val provenance: RunProvenance = RunProvenance(),
    /** が開始されると、独立して実行されます (確認レベルなし)。支払いは要求されず、インターフェイス ターゲットの推測によってアクションがブロックされません。途中で、[ApprovalSettings.autonomous] の単独表示に置き換えられます。 */
    private val autonomous: Boolean = false,
    /** ミカ自身のパッケージ名: 書き込み操作は実行されません (自分に許可を与えたり、設定を変更したりしないでください)。 */
    private val selfPackage: String? = null,
) {
    /**
     * 先ほど判断したステップは、外部アクション (送信、送信: ヒットの決定、リリース) を実行するときに配置される Web サイトまたはアプリです ([OutwardPacing.siteOf] を参照)。
     * 実行エントリは最後の外部アクションから分離されています。それはヌルではありません。単独実行時（他の画面を読み込まず、モデルの手元の画面に従って判断する場合）にも認識され、リズムが無効になることはありません。
     */
    var outwardSite: String? = null
        private set

    /** 画面をロックして待機した後、判定が行われます。この時点で画面判定再読み込みを押します。 */
    suspend fun judge(tool: String, args: JsonObject, origin: Origin): Decision {
        outwardSite = null
        if (autonomous || approvals.autonomous()) {
            // 確認待ち中に自律へ切り替わっても、その操作への承認を次回へ持ち越さない。
            // 自律モードでは照合結果にかかわらず実行できるが、既存の1回限りの承認は消費する。
            tools.approvalRisk(tool, args)?.ask?.let { confirmations?.permitsExactly(it, origin.step) }
            val target = tools.gateTarget(tool)
            if (IrreversibleDetector.needsCheck(target, args)) {
                val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
                outwardSite = outward(IrreversibleDetector.classify(target, aim.args, aim.aimed), aim.aimed)
            }
            return Decision.Clear
        }
        val policy = origin.policy
        val target = tools.gateTarget(tool)
        // このステップがどこに該当するかは、インターフェイス側で指定されます。画像をクリックして @vN を見つけると、パラメータは登録された座標と探しているテキストに置き換えられます。
        val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
        val checked = aim.args
        if (ownApp(tool, args, aim.aimed)) return Decision.OwnApp
        tools.approvalRisk(tool, checked)?.let { declared ->
            return approve(tool, checked, origin, null, declared, null, null, null) ?: Decision.Clear
        }
        if (confirmations == null && !policy.judgeWithoutGate) return Decision.Clear
        val verb = tools.verb(tool)
        val typing = if (verb == Verb.TypeText) (args["text"] as? JsonPrimitive)?.contentOrNull else null
        // 取り消し不能な言葉のないアクション (一般的な変更、インターフェイス間の移動) には費用がかからず、問い合わせる必要もありません
        if (!IrreversibleDetector.needsCheck(target, args)) return Decision.Clear
        val snap = aim.aimed
        val fresh = aim.now()
        if (ownApp(tool, args, fresh)) return Decision.OwnApp
        // 確認済みテキストを再読します（@vN のみ）: 読み取り画面（自己描画インターフェイス）にテキストがない場合、支払いに同意するときにクリックしてオブジェクトを確認します
        val seen = aim.seen
        val hit = IrreversibleDetector.classify(target, checked, snap, fresh)
        val risk = risks.assess(verb, hit) { fresh ?: snap }
        outwardSite = outward(hit, fresh ?: snap)
        approve(tool, checked, origin, hit, risk, fresh ?: snap, typing, seen)?.let { return it }
        if (hit != null || !IrreversibleDetector.unresolved(target, checked, snap, fresh)) return Decision.Clear
        return if (policy.refuseUnresolved) Decision.Unresolved else Decision.Traced(unresolvedDetail(args, aim.readAgeMs, fresh != null))
    }

    /** は、送信または送信するときにこの画面の Web サイトまたはアプリにヒットします。 */
    private fun outward(hit: Irreversible?, screen: ScreenSnapshot?): String? =
        if (hit?.action == IrrevAction.send || hit?.action == IrrevAction.submit) OutwardPacing.siteOf(screen?.packageName, screen?.web != null) else null

    /** 書き込み操作は、Mika 自体に影響します。Mika が画面上にあるか、Mika (アプリケーションの詳細ページを含む) を開く必要があります。 */
    private fun ownApp(tool: String, args: JsonObject, screen: ScreenSnapshot?): Boolean {
        val self = selfPackage ?: return false
        if (!tools.writesScreen(tool)) return false
        if (screen?.packageName == self) return true
        return listOf("package", "uri").any { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.let { self in it } == true }
    }

    /** 情報源による判断。お金を使うときにユーザーの一度限りの同意を確認します。動作は null のままにしておきます。 */
    private fun approve(
        tool: String, checked: JsonObject, origin: Origin, hit: Irreversible?, risk: Risk, screen: ScreenSnapshot?,
        typing: String?, seen: String?,
    ): Decision? {
        val policy = origin.policy
        // ツール自身のリスク申告がある場合はその内容を記録し、それ以外は対象要素を「other」として記録する。
        val asked = hit ?: risk.ask?.let { Irreversible(it.action, it.content) } ?: Irreversible(IrrevAction.other,
            screen?.let { ClassifiedRisk.target(tools.verb(tool), checked, it) }?.name?.trim()?.takeIf { it.isNotEmpty() } ?: tool)
        // 外部情報、お金が必要なもの、公開されていない情報を記録します。インターフェース上の変更は記録されません。
        fun audit(decision: String) {
            if (decision != "allowed" || risk.level.outward || risk.payment) recorder?.approval(origin, tool, asked, risk, decision)
        }
        when (ApprovalPolicy.decide(risk, provenance)) {
            Approval.Allow -> return null.also { audit("allowed") }
            Approval.Deny -> return Decision.Denied(asked).also { audit("denied") }
            Approval.Ask -> Unit
        }
        val required = Decision.Required(asked, risk.ask)
        val gate = confirmations?.takeIf { policy.usesGrant } ?: return required.also { audit("required") }
        val permit = risk.ask?.let { gate.permitsExactly(it, origin.step) } ?: gate.permits(asked, screen, origin.step, typing, listOfNotNull(seen))
        return when (val p = permit) {
            Permit.Allowed -> null.also { audit("allowed") }
            Permit.NoGrant -> required.also { audit("required") }
            is Permit.Mismatch -> Decision.Mismatch(asked, p.reason).also { audit("mismatch") }
        }
    }

    /** が書き込み操作を実行しました。契約は無効になるか、ルールに従って保持されます。 */
    fun noteWrite(tool: String) {
        confirmations?.noteWrite(tools.verb(tool))
    }

    /** 痕跡を残します: どの番号がクリックされたか、どれくらい前に画面を読み取ったか、またはポイントの座標。 */
    private fun unresolvedDetail(args: JsonObject, readAgeMs: Long?, freshRead: Boolean): String {
        fun arg(k: String) = args[k]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        val ref = arg("ref")
        return if (ref != null) {
            val sid = arg("snapshotId")
            "ref=$ref snapshotId=$sid age=${readAgeMs?.let { "${it}ms" } ?: "unknown"}"
        } else "x=${arg("x")},y=${arg("y")} freshRead=$freshRead"
    }

    companion object {
        /**
         * が画面を読み取らない予測 (大型モデルと小型モデル間の分業に使用): 最後の画面読み取りを押します。このステップは元に戻せない可能性があります。または、何をクリックしたかわからない可能性があります (画像からは確認できますが、要素が見つかりません)。
         * このステップはマスター モデルによって実行されます。
         */
        fun mayBeIrreversible(tools: ToolDispatcher, tool: String, args: JsonObject): Boolean {
            val target = tools.gateTarget(tool)
            if (!IrreversibleDetector.needsCheck(target, args)) return false
            val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
            val checked = aim.args
            val snap = aim.aimed
            return aim.bySight || IrreversibleDetector.classify(target, checked, snap) != null ||
                IrreversibleDetector.unresolved(target, checked, snap)
        }
    }
}
