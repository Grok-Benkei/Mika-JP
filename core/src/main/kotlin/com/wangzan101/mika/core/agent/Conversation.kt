package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * 一連の対話: ユーザーが言ったこととその結果。 [outcome] は成功の答え、または失敗の理由です。
 * [status] は成功 / 未検証 (完了したが検証に失敗しました。理由は [note] で、[unconfirmed] は未確認としてマークされています) / 失敗 / キャンセルされました。
 * [runId] はこのラウンドの走行記録です。レコードが開始されていない場合、または以前のラウンドが null の場合。
 */
/**
 * [memoryNotes]/[usedMemory]/[skillNotes]: 結果カードの下のメモリとスキルのヒントは、タスクの完了後に追加されます。モデルの上部はフィードされません。
 * [result]: 結果 ([RunOutcome.wire]) は、回答を提出した後に判定され、バックグラウンドで追加されます。判定されていない場合は null になります。
 */
data class Turn(
    val userText: String, val outcome: String, val status: String, val note: String? = null, val runId: String? = null,
    val memoryNotes: List<com.wangzan101.mika.core.memory.MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<com.wangzan101.mika.core.skills.SkillNote> = emptyList(),
    val unconfirmed: List<String> = emptyList(),
    val result: String? = null,
    /** タスクの進行中にユーザーがタスクを挿入し、タスクに割り当てられている場合 (インターフェイスはそのラウンドの下に表示されます)。 */
    val steers: List<String> = emptyList(),
    /** と [steers] には 1 対 1 の対応関係があります。つまり、各中断によって中断される応答内の単語はユーザーがすでに見た単語であり、中断されない単語は空の文字列です。インターフェイスのみが表示され、モデルの上部はフィードされません。 */
    val interruptedReplies: List<String> = emptyList(),
    /** と [steers] には 1 対 1 の対応関係があります。各間投詞がモデルに渡される前に、応答は完了して表示されており、空の文字列はありません。インターフェイス上にのみ表示されます。 */
    val finishedReplies: List<String> = emptyList(),
    /** このラウンドで実際に使用された能力グループ (携帯電話、Web ページ、サンドボックスなど)。次のラウンドは事前に開始され、モデルの上記にはフィードされません。 */
    val groups: List<String> = emptyList(),
    /** タスクの完了後に実行を継続するためにバックグラウンドで残されるサイトのページ (web_keep_open を参照)。残さない場合は null になります。 */
    val keptPage: String? = null,
    /** 残されたページが後に閉じられた理由 ([com.wangzan101.mika.core.cap.web.KeptPageClose.wire])。まだ開いているか、null のままになっていません。 */
    val keptPageClosed: String? = null,
)

object Conversation {
    const val HEADER = "Earlier turns of this conversation (for reference; what you read this time shows the current state):"

    /** 停止したターンにまだ説明がない(または説明する内容がない)場合に記録する文。 */
    const val CANCELLED_OUTCOME = "キャンセルされました"

    /** 旧データ互換:中国語の旧表記も説明なしとして扱う(保存済みラベルとして保持)。 */
    private const val LEGACY_CANCELLED_OUTCOME = "已取消"

    /** 停止したターンに追加された説明。なければ null。 */
    fun stopSummary(t: Turn): String? =
        t.outcome.trim().takeIf {
            t.status == "cancelled" && it.isNotEmpty() && it != CANCELLED_OUTCOME && it != LEGACY_CANCELLED_OUTCOME
        }

    /**
     * は、以前のラウンドをユーザー ロールの参照メッセージに作成し、最新のラウンドを最後に置きます。メッセージ全体が [maxTokens] を超える場合 ([TokenEstimate] を押す)、以前のラウンドは破棄されます。
     * ラウンドが 1 つだけ残っており、それが長すぎる場合、各フィールドは個別に切り捨てられます (ユーザーの元の単語と終わりのために 1 つの段落が残されます)。それでも長すぎる場合は、段落全体が切り詰められます。ラウンドがない場合は null を返します。
     */
    fun context(turns: List<Turn>, maxTurns: Int = 5, maxTokens: Int = 2_500): Message? {
        var recent = turns.takeLast(maxTurns.coerceAtLeast(0))
        if (recent.isEmpty()) return null
        val header = "$HEADER\n"
        fun render(ts: List<Turn>, clip: Int) = ts.mapIndexed { i, t -> line(i + 1, t, clip) }.joinToString("\n")
        while (recent.size > 1 && TokenEstimate.of(header + render(recent, Int.MAX_VALUE)) > maxTokens) recent = recent.drop(1)
        var body = render(recent, Int.MAX_VALUE)
        val room = (maxTokens - TokenEstimate.of(header)).coerceAtLeast(20)
        if (TokenEstimate.of(header + body) > maxTokens) {
            // ラウンドは 1 つだけ残っていますが、長すぎます。各フィールドは切り詰められ、元の単語と語尾にそれぞれ 1 つの段落が残ります。
            body = render(recent, room / 3)
            if (TokenEstimate.of(header + body) > maxTokens) body = TokenEstimate.clip(body, room, "…")
        }
        return Message(Role.User, listOf(Part.Text("$HEADER\n$body")), Author.Mika)
    }

    private fun line(n: Int, t: Turn, clip: Int): String {
        // は、それは行われていないか、部分的にしか行われていないと正直に答えました。引き継ぎの結果によれば、次のラウンドでは行われたとは思わないでしょう。
        val answered = t.status == "succeeded" || t.status == "unverified"
        val result = when {
            answered && t.result == RunOutcome.Partial.wire -> "only partly done: ${t.outcome.clip(clip)}"
            answered && t.result == RunOutcome.NotPossible.wire -> "not done: ${t.outcome.clip(clip)}"
            t.status == "succeeded" -> "result: ${t.outcome.clip(clip)}"
            t.status == "unverified" -> "result (could not be verified): ${t.outcome.clip(clip)}"
            // やめた後の説明（やったこと、やらなかったこと）を持参してください。 「続行」と言ったときにのみ、どこから進めばよいのかがわかります。古いレコードは「キャンセル」されただけです
            t.status == "cancelled" -> stopSummary(t)?.let { "the user stopped this task partway; before the stop: ${it.clip(clip)}" } ?: "the user cancelled this task"
            else -> "not finished, reason: ${t.outcome.clip(clip)}"
        }
        // ミッション終了後に残されたページ：次のラウンドで「まだ放送中か？」と聞かれると、まだ放送中か停止中かがわかります。
        val kept = t.keptPage?.let { host ->
            if (t.keptPageClosed == null) "; after it Mika kept the page on $host running in the background"
            else "; Mika kept the page on $host running after it, and that page has since been closed"
        }.orEmpty()
        return "$n. The user said: ${t.userText.clip(clip)}; $result$kept"
    }

    /** は [max] トークンで終わります (末尾の「...」を含む)。 */
    private fun String.clip(max: Int) = if (max == Int.MAX_VALUE || TokenEstimate.of(this) <= max) this else TokenEstimate.clip(this, max, "…")
}
