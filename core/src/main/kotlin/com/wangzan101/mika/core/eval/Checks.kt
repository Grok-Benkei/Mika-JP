package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.verify.Grounding

/** ミッションの実行後に評価者によって収集されたファクトは、スコアを付けるときにのみ参照されます。 */
data class Observed(
    /** succeeded|unverified|failed|cancelled|confirmation|takeover|timeout|skipped */
    val outcome: String,
    val answer: String,
    /** 証拠台帳内の回答によって選択されたマテリアルは一度に 1 つずつ読み取られ (実行時に検証者に与えられるのと同じセット、[RunFacts.evidence] を参照)、エージェント自身の入力のエコーは削除されています。 */
    val evidence: List<String>,
    /** カードフィールドを確認します: アクション/ターゲット/コンテンツ/金額。 */
    val confirmation: Map<String, String>?,
    /** キーは [Checks.screenKey] です。 */
    val screenHas: Map<String, Boolean>,
    /** タスクの後に実行されるプローブ: [名前] → [出力]。 */
    val probes: Map<String, String>,
    val steps: Int,
    val wallMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val failReason: String?,
    /** エージェントが触れたが、質問アプリには含まれていないアプリ。これは記録されるだけであり、失敗としてカウントされません。 */
    val leftScope: List<String> = emptyList(),
    /** 元の質問文： 質問文中の単語（アプリ名、計算式）を回答中に暗唱しても、画面から読み取る値としてカウントされません。 */
    val goal: String = "",
    /** 完了前の検証結果 (合格 / 失敗 / 検証不可)。検証されていない場合は null。 */
    val verdict: String? = null,
    /** メイン ループ内のモデル呼び出しの数 (ModelReply イベントの数。再生およびバッチ実行ステップはカウントされません)。 */
    val modelCalls: Int = 0,
    /** モデルの合計待機時間 (各 ModelReply.latencyMs の合計)。 */
    val modelMs: Long = 0,
    /** ツールの合計実行時間 (各 ToolResult.durationMs の合計)。 */
    val toolMs: Long = 0,
    /** 中間インターフェイスの安定部分 (各 ToolResult.waitMs の合計)。 */
    val waitMs: Long = 0,
    /** 再生中に完了したルートの数 / 途中でモデルに返されたルートの数。 */
    val replayHits: Int = 0,
    val replayHandBacks: Int = 0,
    /** タスクの開始時のメモリの数 (memory_used イベント)。 */
    val memoryUsed: Int = 0,
    /** 今回使用した個人スキルの名前（skill_usedイベント、重複排除）。 */
    val skillsUsed: List<String> = emptyList(),
    /** キーは [Checks.webKey] です。 */
    val webHas: Map<String, Boolean> = emptyMap(),
    /** 障害は乗っ取りの直前に報告され、その画面に表示されます ([RunFacts.takeoverBarrier] を参照)。 nullではありません。 */
    val takeoverBarrier: String? = null,
    /** エージェントはユーザー (ask_user) に時間を尋ねます。 */
    val askedUser: Int = 0,
    /** タスクの日 (時計が動いている日付): 特定の日付として「明日」と答える場合、それを押して確認します ([Grounding.check] を参照)。わからない場合は null です。 */
    val today: java.time.LocalDate? = null,
    /** 回答を提出した後に決定された結果 (実行中のレコード内の結果イベント: 完了 / 部分的 / 可能ではない / 不明)。決定されていない場合は null です。クリックしてスコアを獲得します。[Scoring.recorded] を参照してください。 */
    val judged: String? = null,
    /** この実行の概要 (目的地、最初のフィードバック、往復回数など、[com.wangzan101.mika.core.record.RunMetrics] を参照)。実行レコードがない場合は null です。 */
    val metrics: com.wangzan101.mika.core.record.RunMetrics? = null,
)

object Checks {
    fun screenKey(c: Check.ScreenHas): String = "${c.packageName}|${c.deepLink.orEmpty()}|${c.text}"

    fun webKey(c: Check.WebHas): String = "${c.url}|${c.text}"

    fun passes(c: Check, o: Observed): Boolean = when (c) {
        is Check.AnswerContains -> c.all.all { it in o.answer }
        is Check.AnswerRegex -> Regex(c.pattern).containsMatchIn(o.answer)
        is Check.AnswerGrounded -> grounded(o.answer, o.evidence, c.minItems, o.goal, o.today)
        is Check.ScreenHas -> o.screenHas[screenKey(c)] == true
        is Check.MemoryUsed -> o.memoryUsed >= c.min
        is Check.AskedUser -> (o.askedUser > 0) == c.expected
        is Check.WebHas -> o.webHas[webKey(c)] == true
        is Check.ProbeEquals -> o.probes[c.probe]?.trim()?.let { actual ->
            if (c.contains) c.expected.trim() in actual else actual == c.expected.trim()
        } ?: false
        is Check.ConfirmationShows -> o.confirmation?.let { card ->
            listOf("action" to c.action, "target" to c.target, "content" to c.content, "amount" to c.amount)
                .all { (k, want) -> want == null || want in card[k].orEmpty() }
        } ?: false
    }

    /**
     * の答えは十分に文書化されています。これは、最終結果を確認するのと同じ文字通りのルールに従います ([Grounding.check]: 引用、単位付きの数字、数字、日付はすべて資料内で見つけることができ、資料内の引用に基づいて見つけることができます)。
     * と十分に文書化されたエントリ ([Grounding.Result.items]) は [minItems] と同等です。質問 [goal] の数字と単語は既知であるとみなされます。
     * の値が回答に記載されているもの（どの行、どのページ）に属するかはコードによって決まるのではなく、検証モデル（結末が評価に成功したかどうかを参照）によって決まります。
     */
    internal fun grounded(answer: String, screens: List<String>, minItems: Int, goal: String = "", today: java.time.LocalDate? = null): Boolean =
        Grounding.check(goal, answer, screens, today).let { it.found != false && it.items >= minItems }
}
