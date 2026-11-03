package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb

/** ループがモデルへ送る通知1件:出典ラベルと本文([ContextBuilder.notice] が文脈として包む)。 */
internal data class Notice(val source: String, val text: String)

/** ターン方針が見るスナップショット:現在のステップと上限。 */
internal data class TurnSnapshot(val step: Int, val maxSteps: Int)

/** 基本指示と同じ規則:求める結果に必要な機能・項目・値がなければ実行不可。「あるか」を聞かれた場合のみ、アプリ内検索の明確な「なし」が答えになる。 */
internal val ANSWER_OR_FAILURE: String get() = Feedback["answer_or_failure"]

/**
 * ターン方針:各ステップ開始時の通知(進捗確認)、空応答(または出力上限で切れた応答)への対応、
 * 照合不一致時の通知、ステップ上限後の最終ターンの内容、提供ツールを決める。純粋関数。
 * ツール呼び出しのない応答自体が回答であり、ここでは扱わない。空応答のみ1回通知する。
 */
internal object TurnPolicy {
    /** 進捗確認:使用率がこの割合に達したら1回通知する。探し尽くしたら正直に諦める。ステップが少ないタスクでは通知しない。 */
    private val PROGRESS_CHECKS = listOf(0.5, 0.75)
    private const val PROGRESS_CHECK_MIN_STEPS = 10
    /** ステップ上限後の最終ターンでは諦めるツールのみを提供し、回答は文章で書く。 */
    val FINAL_VERBS = setOf(Verb.EndNotDone)

    /** このステップでモデルへ送る前に追加する通知。なければ null。 */
    fun beforeStep(s: TurnSnapshot): Notice? {
        if (s.maxSteps < PROGRESS_CHECK_MIN_STEPS) return null
        val used = s.step - 1
        if (PROGRESS_CHECKS.none { (s.maxSteps * it).toInt() == used }) return null
        return Notice(Feedback["progress_source"],
            Feedback.format("progress_check", "used" to used, "max" to s.maxSteps, "answer_or_failure" to ANSWER_OR_FAILURE))
    }

    /**
     * 応答に呼び出しも文字もない場合([truncated]: 出力上限で切れており完全な回答ではない)。
     * [already] が true なら直前に1回通知済みのため、今回は通知せず null を返してタスクを終える。
     */
    fun afterEmpty(already: Boolean, truncated: Boolean): Notice? =
        if (already) null else Notice(Feedback["nudge_source"], Feedback[if (truncated) "truncated_reply" else "empty_reply"])

    /**
     * 回答の照合が一致しなかったため1回差し戻す際の通知。[gaps] は読み取った資料に見つからない箇所、
     * [objections] は照合モデルが未達成と判断した理由であり、分けて伝える。
     */
    fun unconfirmed(gaps: List<String>, objections: List<String> = emptyList()): Notice {
        val findings = listOfNotNull(
            gaps.takeIf { it.isNotEmpty() }?.let { Feedback.format("unconfirmed_gaps", "items" to it.joinToString("\n") { g -> "- $g" }) },
            objections.takeIf { it.isNotEmpty() }?.let { Feedback.format("unconfirmed_objection", "reasons" to it.joinToString("\n") { o -> "- $o" }) },
        ).joinToString("\n")
        return Notice(Feedback["unconfirmed_source"], Feedback.format("unconfirmed_nudge", "findings" to findings))
    }

    /** 回答本文が日本語でない場合([m] は回答の語族と目標語族、[ReplyLanguage] 参照):1回差し戻し、全文を日本語に書き直させる。目標はユーザーの言語ではなく常時日本語であり、互換性のため {{user}} には日本語を渡す。 */
    fun language(m: ReplyLanguage.Mismatch): Notice =
        Notice(Feedback["language_source"], Feedback.format("language_nudge", "answer" to m.answer.display, "user" to ReplyLanguage.TARGET.display))

    /** 応答本文に [tool] への呼び出しが漏れた場合([LeakedToolCall] 参照):1回差し戻し、ツール経由で再呼び出しさせるか文章で回答させる。 */
    fun leakedToolCall(tool: String): Notice =
        Notice(Feedback["leaked_call_source"], Feedback.format("leaked_call_nudge", "tool" to tool))

    /** ユーザーが同意または回答した([what])後、何もせずに回答を出そうとした場合:1回通知し、実行するか理由を明示させる。 */
    fun followUp(what: String): Notice = Notice(Feedback["followup_source"], Feedback.format("followup_nudge", "what" to what))

    /** ステップ上限後の最終ターンの前に送る通知。 */
    fun finalTurn(): Notice = Notice(Feedback["final_source"], Feedback["final_turn"])

    /** 最終ターンでモデルへ渡すツール:諦めるもののみ。完了していれば文章で直接回答する。 */
    fun finalTools(specs: List<ToolSpec>, verbOf: (String) -> Verb?): List<ToolSpec> = specs.filter { verbOf(it.name) in FINAL_VERBS }
}
