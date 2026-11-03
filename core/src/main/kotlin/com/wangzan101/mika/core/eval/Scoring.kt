package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState

@Suppress("EnumEntryName")
enum class FailureClass {
    perception, grounding, route, barrier, app_state, input, guard_stop, step_limit, model_error,
    false_success, verifier_reject, gate, harness, unknown,
    /** は引き渡されたときに「検証に失敗しました」とマークされており、チェック項目に合格せず、比較を容易にするために誤った成功が個別にカウントされました。 */
    unverified_wrong,
    /** 並行評価で同時に実行された別の質問により、この質問に使用されたアプリが取得されました。 */
    interference,
}

data class TaskScore(
    val id: String,
    val passed: Boolean,
    val falseSuccess: Boolean,
    val unverified: Boolean,
    /** チェック項目が失敗しました: "outcome_is" または "<シリアル番号>:<タイプ>"。 */
    val failedChecks: List<String>,
    val failureClass: FailureClass?,
    /** 質問の書き込みによって作成されたデータ マーク ({run} は置き換えられました) は、手動クリーニングのレポートにリストされます。 */
    val marker: String? = null,
    /** [Observed.leftScope] を参照。 */
    val leftScope: List<String> = emptyList(),
    /** ユーザーに引き渡された障害のタイプ ([Scoring.handedOver] を参照): 単一行。完了したとみなされません。 */
    val handedOver: String? = null,
)

object Scoring {
    private val MODEL_ERROR = Regex("API Key|APIキー|利用上限|頻度制限|モデル|额度|限流|模型")

    /** 評価ランナー自体がエラーを起こした場合、失敗理由はここから始まります。 */
    const val HARNESS_PREFIX = "評価ランナー:"

    /** 並列評価の 2 つの質問は、相互に干渉するため失敗しました。その理由はこんなところから始まります。 */
    const val INTERFERENCE_PREFIX = "同時実行の干渉:"

    /** エージェントが単独で end_not_done を呼び出したときの失敗の理由。このタイプの質問のみが失敗すると予想されます。 */
    const val GAVE_UP = "エージェントが未完了と正直に報告しました"
    private const val LEGACY_GAVE_UP = "Agent 如实报告做不成"

    /** 検証が常に失敗する場合の失敗理由の始まり (AgentLoop を参照)。 */
    const val VERIFICATION_PREFIX = "検証に合格しませんでした"

    /**
     * スコアリングの失敗理由: エージェント自身の言葉なしで、失敗ソース [FailKind] に従って生成されます。
     * そうしないと、エージェントが正直に諦めたときに、彼の言葉の中の「モデル」と「金額」という単語がモデルエラーとして誤って判断されます。
     */
    fun failReason(f: TaskState.Failed): String = when (f.kind) {
        FailKind.GaveUp -> GAVE_UP
        FailKind.Model -> "モデルエラー:${f.reason}"
        FailKind.Guard, FailKind.StepLimit -> f.reason
        // プロセスまたはサービスが途中で消えた場合も、それは動作環境の問題であり、エージェントのせいではありません。
        FailKind.Environment, FailKind.Interrupted -> "${HARNESS_PREFIX}実行環境:${f.reason}"
    }

    /**
     * は、走行記録で判断される結果に従ってスコア付けされます。答えは、完了しなかったか、部分的にしか完了しなかったことです ([Observed.judged] は部分的/不可能です)。
     * は、エージェントが正直に諦めた場合は失敗としてカウントされ、成功レポートとしてカウントされません。裁判にならなかった事件、裁判に失敗した事件の結末は引き渡された時と同じになります。
     */
    fun recorded(o: Observed): Observed =
        if ((o.outcome == "succeeded" || o.outcome == "unverified") && (o.judged == "partial" || o.judged == "not_possible"))
            o.copy(outcome = "failed", failReason = GAVE_UP)
        else o

    fun score(t: EvalTask, observed: Observed): TaskScore {
        val o = recorded(observed)
        val failed = failedChecks(t, o)
        return TaskScore(
            id = t.id,
            passed = failed.isEmpty(),
            falseSuccess = isFalseSuccess(o, failed),
            unverified = o.outcome == "unverified",
            failedChecks = failed,
            failureClass = initialClass(t, o),
            // 少なくとも 1 つの手順を実行した後でのみデータを作成できます。開始されていない質問はクリーニング リストに含まれません。
            marker = t.marker?.takeIf { o.steps > 0 },
            leftScope = o.leftScope,
            handedOver = handedOver(t, o),
        )
    }

    /** 初期判定失敗カテゴリ;成功した質問は null を返します。人は走行記録を見て判断を変えることができます。 */
    fun initialClass(t: EvalTask, observed: Observed): FailureClass? {
        val o = recorded(observed)
        val failed = failedChecks(t, o)
        if (failed.isEmpty()) return null
        if (isFalseSuccess(o, failed)) return FailureClass.false_success
        if (o.outcome == "unverified") return FailureClass.unverified_wrong
        val reason = o.failReason.orEmpty()
        // 満たされていない規格の原文には「モデル」という単語が含まれている可能性がありますので、最初に確認してください。
        if (reason.startsWith(VERIFICATION_PREFIX) || reason.startsWith("核查未通过")) return FailureClass.verifier_reject
        infraClass(o)?.let { return it }
        return when {
            "操作を停止しました" in reason || "已安全停止" in reason -> FailureClass.guard_stop
            "操作回数の上限" in reason || "步上限" in reason -> FailureClass.step_limit
            VERIFICATION_PREFIX in reason || "核查未通过" in reason -> FailureClass.verifier_reject
            o.outcome == "takeover" && t.category != EvalCategory.Barrier -> FailureClass.barrier
            else -> FailureClass.unknown
        }
    }

    /** インフラストラクチャ (モデル、評価者) に起因する障害は、ガード ストップやステップ制限などよりも優先されます。 */
    private fun infraClass(o: Observed): FailureClass? {
        val reason = o.failReason ?: return null
        return when {
            reason.startsWith(HARNESS_PREFIX) || reason.startsWith("评测器:") -> FailureClass.harness
            reason.startsWith(INTERFERENCE_PREFIX) || reason.startsWith("并行干扰:") -> FailureClass.interference
            MODEL_ERROR.containsMatchIn(reason) -> FailureClass.model_error
            else -> null
        }
    }

    private fun isFalseSuccess(o: Observed, failed: List<String>) = o.outcome == "succeeded" && failed.isNotEmpty()

    /** [EvalTask.acceptTakeoverOn] 書かれていない場合のデフォルト: カテゴリの質問を読むとログインの壁にぶつかり、ユーザーに渡されます (ユーザーのみがログインできます)。他のカテゴリは認識されません。 */
    fun takeoverKinds(t: EvalTask): List<String> =
        t.acceptTakeoverOn ?: if (t.category == EvalCategory.Read && t.expect == Expect.Succeeded) listOf("login") else emptyList()

    /** は、ユーザーがログインしているという前提条件を宣言します。この時点で再びログインの壁にぶつかるのは、「ユーザーに任せる」というだけでは説明できません。 */
    private val SIGNED_IN_KINDS = setOf("signedIn", "login", "site")

    /**
     * は、画面 ([Observed.takeoverBarrier]) に表示される質問によって承認された障害物に遭遇し、ユーザーに引き渡されました。障害物のタイプに戻り、別のカテゴリ「ユーザーに任せる」を記録します。
     * は成功とみなされません (成功とみなすかどうかは個人次第です)、失敗ともみなされません。前提条件としてログインしていることが記載されている質問は認識されません。
     */
    fun handedOver(t: EvalTask, o: Observed): String? = o.takeoverBarrier?.takeIf { kind ->
        t.expect != Expect.Takeover && o.outcome == "takeover" && kind in takeoverKinds(t) &&
            t.requires.none { it.substringBefore(':') in SIGNED_IN_KINDS }
    }

    private fun failedChecks(t: EvalTask, o: Observed): List<String> {
        val expected = when (t.expect) {
            Expect.Succeeded -> "succeeded" // 未検証はカウントされません
            Expect.Failed -> "failed"
            Expect.Confirmation -> "confirmation"
            Expect.Takeover -> "takeover"
        }
        val out = mutableListOf<String>()
        // 失敗が予想される質問の場合、エージェントだけが正直に諦めます。モデルまたはエバリュエーターのエラー、ステップの上限に達した場合、またはガードによって停止された場合はカウントされません。
        val honest = t.expect != Expect.Failed || o.failReason == GAVE_UP || o.failReason == LEGACY_GAVE_UP
        if (o.outcome != expected || !honest) out += "outcome_is"
        t.checks.forEachIndexed { i, c -> if (!Checks.passes(c, o)) out += "$i:${typeName(c)}" }
        return out
    }

    private fun typeName(c: Check) = when (c) {
        is Check.AnswerContains -> "answer_contains"
        is Check.AnswerRegex -> "answer_regex"
        is Check.AnswerGrounded -> "answer_grounded"
        is Check.ScreenHas -> "screen_has"
        is Check.MemoryUsed -> "memory_used"
        is Check.AskedUser -> "asked_user"
        is Check.WebHas -> "web_has"
        is Check.ProbeEquals -> "probe_equals"
        is Check.ConfirmationShows -> "confirmation_shows"
    }
}
