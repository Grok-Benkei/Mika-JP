package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.verify.AnswerSource
import com.wangzan101.mika.core.verify.AnswerSources
import com.wangzan101.mika.core.verify.Criteria
import com.wangzan101.mika.core.verify.Grounding
import com.wangzan101.mika.core.verify.Verdict
import com.wangzan101.mika.core.verify.Verification
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** タスクを引き渡す前にタスクのステータスを確認するために応答します。 */
internal data class ReviewFacts(
    /** この作業に使用するツールを調整しました（調整されていないのはチャットと常識であり、確認する材料はありません）。 */
    val acted: Boolean,
    /** は、外部的または取り消しできないことを行いました (そのようなアクションを許可した、またはユーザーに支払いを引き渡しました)。 */
    val outward: Boolean,
    /** は、ステータスを変更する最後のアクション以降、再度読み取っていません。回答に記載されている結果を誰も読んでいません。 */
    val unreadChange: Boolean,
)

/**
 * 最終回答を渡す前の検証。リスクに従って来てください、リターンはありません:
 * - このタスクは単一のツールでチェックされていません (チャット、常識、対話で回答できます): チェックされていません、チェックされていません。
 * - リテラル チェック ([Grounding.check]): 解答に含まれる数字、数値、日付、引用、および記載された計算 (演算子 b = c) には、このタスクで読まれた資料の元のテキストが含まれている必要があります。
 *   回答にそのような事実があり、それらがすべて正しい場合、それは検証されたとみなされます。回答にそのような事実がなく、文字通り検証するものが何もない場合、その回答は未検証とみなされます。
 * - 何らかの外部的または元に戻せないことを行った場合、ステータスを変更するための最後のアクションの後に再読み込みしていない場合、または文字通りの検証が一致しない場合は、モデル ([Verifier]) を確認してください。合格した場合にのみ検証されたとみなされます。
 * 一致する結論 ([Review.gaps]、[Review.objections]) がある場合、ループはそれをモデルに返します。失敗した場合、これらのポイントは未確認としてマークされ、引き渡されます (AgentLoop を参照)。
 * ソース ([Review.sources]) は、コード ([AnswerSources]) によってここでも見つかります。
 */
internal class AnswerReview(
    private val tools: ToolDispatcher,
    private val verifier: Verifier?,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
) {
    /** 製品パスはソースを分類するだけであり、最終応答のための別の検証ラウンドは開始されません。 */
    suspend fun sourcesOnly(goal: String, answer: String, media: Map<Int, List<Int>>): List<AnswerSource> =
        AnswerSources.of(tools.evidence(fresh = false).ledger, goal, answer, media)

    /**
     * [gaps] は私が読んだ資料では見つからなかった回答の部分、[objections] は検証モデルが完成しなかった理由です。 2 つの組み合わせは [unconfirmed] です。
     * [reviewed] は false です。これは、何もチェックされていないことを意味します (何もチェックできず、チェックを要求されたモデルもありません)。 [verified] は、チェックされていてすべてが正しい場合にのみ true になります。
     * [note] は、検証者が検証に失敗した理由です。 [verdict] は検証モデルの結論です (null ではありません)。
     */
    class Review(
        val gaps: List<String> = emptyList(), val objections: List<String> = emptyList(), val sources: List<AnswerSource> = emptyList(),
        val reviewed: Boolean = true, val verified: Boolean = true, val note: String? = null, val verdict: Verdict? = null,
    ) {
        val unconfirmed: List<String> get() = (gaps + objections).distinct()
    }

    /** は、ステップ [step] で指定された回答 [answer] をチェックしました。 [media]: 各ステップで保存された画面番号。 [verifying]: モデルを検証したい場合は、最初に調整してください (インターフェイスに「検証中」と表示されます)。 */
    suspend fun review(
        goal: String, answer: String, step: Int, facts: ReviewFacts, earlierGoals: List<String>, media: Map<Int, List<Int>>, verifying: () -> Unit,
    ): Review {
        if (!facts.acted) return Review(reviewed = false, verified = false)
        val v = verifier
        // リテラル検証は台帳を参照するだけであり (最新の読み取りは既に台帳に含まれています)、再読み取りは行いません。モデルを検証したい場合にのみ、現在のインターフェイスを再読み取りしてください。
        val read = tools.evidence(fresh = false)
        val sources = AnswerSources.of(read.ledger, goal, answer, media)
        val g = Grounding.check(goal, answer, read.sources(read.ledger.forAnswer(goal, answer)), read.today)
        val gaps = g.unmatched.map { it.text }.distinct()
        val risky = facts.outward || facts.unreadChange
        emit(RunEvent.AnswerCheck(now(), step, g.claims.size, gaps.map(::detail), risky))
        val literalOk = gaps.isEmpty() && g.claims.isNotEmpty()
        if (!risky && gaps.isEmpty()) return Review(sources = sources, reviewed = literalOk, verified = literalOk)
        if (v == null) return Review(gaps = gaps, sources = sources, reviewed = literalOk || gaps.isNotEmpty(), verified = literalOk)
        verifying()
        val ev = tools.evidence(withImage = v.wantsImage)
        val r = v.verify(goal, Criteria.of(goal, facts.outward), answer, ev, earlierGoals)
        emit(RunEvent.Verifier(now(), r.verdict.wire, RunEvent.clip(Redactor.text(detailOf(r).toString())), step))
        return when (r.verdict) {
            Verdict.Pass -> Review(sources = sources, verdict = r.verdict)
            // 規格はユーザーのオリジナルの言葉だけです。満たされていない「基準」とは原語そのものであり、明確に記載されているのは検証者が理由を述べたものである。
            Verdict.Fail -> Review(gaps = gaps, objections = listOf(r.reason).filter(String::isNotBlank), sources = sources, verified = false, verdict = r.verdict)
            Verdict.CannotVerify -> (gaps + r.notFound).distinct().let { missing ->
                Review(gaps = missing, sources = sources, verified = false, note = r.reason, verdict = r.verdict)
            }
        }
    }

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    private fun detailOf(v: Verification) = buildJsonObject {
        put("reason", v.reason)
        v.groundedBy?.let { put("groundedBy", it) }
        if (v.notFound.isNotEmpty()) put("notFound", JsonArray(v.notFound.map(::JsonPrimitive)))
        put("checks", JsonArray(v.checks.map { c ->
            buildJsonObject { put("criterion", c.criterion); put("status", c.status); c.quote?.let { put("quote", it) } }
        }))
    }
}
