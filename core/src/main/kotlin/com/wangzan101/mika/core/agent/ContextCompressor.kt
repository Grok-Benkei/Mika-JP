package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.context.StepSummary
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/** [estimate]: [messages] のトークンは概算 (未校正) であり、発行後に実際の使用量と比較するために使用されます。 [rewritten]: 今回リクエストのプレフィックスが書き換えられたかどうか。 */
data class Compressed(
    val messages: List<Message>,
    val foldedSteps: Int,
    val estimate: Int = TokenEstimate.of(messages),
    val rewritten: Boolean = false,
)

/**
 * リクエストはキャッシュによって分割されます: エリア 0 システム プロンプト ワードおよびツール (タスク内で変更なし) → エリア 1 デプロイメント コンテキスト (1 回のみ書き込まれます) → エリア 2 履歴 (追加のみ) → エリア 3 末尾
 * (このステップの結果、画面、実行時ステータス)。サービスプロバイダーはプレフィックスごとにキャッシュするため、通常、一度送信されたメッセージはそのまま再送信されます。
 *
 * は、リクエスト全体のサイズ ([UsageMeter]: 最新の実際の使用量 + その後の推定値) がトリガー ポイント ([ContextLimits.trigger]) を超えるときに 1 回だけ圧縮されます。
 * 最近の [keepRecent] ツールの往復の前に、まず古い結果と古い画像を短縮します。
 * ([HistoryPruner.prune])、Huancao は、これらの往復を (モデルを調整せずに) 決定論的な「以前のステップ」の要約に折り畳みます。タスクの元の単語、中断、参考資料、
 * 引き渡しから尋問までの往復に妥協はありません。圧縮は [ContextLimits.target] 未満である必要があります (圧縮できない場合は、圧縮できる最小レベルを使用します)。
 * 以降のリクエスト = この圧縮の結果 + 後続の追加メッセージ。トリガー ポイントを再度通過するまでのみ追加されます (圧縮後に少なくとも 1 つ以上の [ContextLimits.margin] が追加されます)。
 * ラウンドトリップはセグメント全体を削除し、呼び出しと結果のペアリングは常に完了します。
 * コピーのみが返され、呼び出し元の完全な履歴は変更されません。タスクごとに 1 つのインスタンス。ステータスはコンテキスト サイズに応じて個別に記録されます。
 *
 * [structured]: 概要は「目標/確認された事実/進捗状況/次のステップ」([StepSummary]) として記述されます。
 * [diffScreens]: 差分画面(inlineScreen)でのアクション結果。
 */
class ContextCompressor(
    /** 呼び出されたツール名によって実行されるアクションを確認します。 */
    private val tools: ToolIndex,
    private val keepRecent: Int = 12,
    private val structured: Boolean = false,
    private val diffScreens: Boolean = false,
    private val triggerOverride: Int? = null,
) {
    /** 「実際/概算」比率: 使用量が戻るたびにスムーズに更新されます ([TokenEstimate.calibrated])。圧縮する時期とレベルを決定するだけであり、送信されたコンテンツは変更されません。 */
    private var observed: Double? = null
    /** 実測値は保守的な推定係数の下限を引き上げるためだけに使い、後続の密な JSON を過少に見積もりません。 */
    internal val scale: Double get() = observed?.coerceAtLeast(UNCALIBRATED_SCALE) ?: UNCALIBRATED_SCALE

    /** 最終圧縮: [covered] から始まる完全な履歴をカバーし、[base] (おおよその推定値 [estimate]) に圧縮されます。 [aggressive]: モデルに応じて超長距離で圧縮されます。 */
    private class View(val covered: List<Message>, val base: List<Message>, val folded: Int, val aggressive: Boolean) {
        val estimate = TokenEstimate.of(base)
    }
    private val views = HashMap<Int, View>()

    /** リクエストの送信後: [estimated] はこのリクエストの概算値 (固定オーバーヘッドを含む)、[actual] はサービス プロバイダーによって報告された入力トークンです。 */
    fun calibrate(estimated: Int, actual: Int) {
        observed = TokenEstimate.calibrated(observed, estimated, actual)
    }

    /** 新しいタスク: 圧縮状態をクリアします (キャリブレーション比を維持、同じモデル)。 */
    fun forget() = views.clear()

    private fun tokens(ms: List<Message>) = TokenEstimate.of(ms) * scale

    /** 最後の圧縮後に実際に送信されるメッセージ (圧縮されていない場合は、完全な履歴です)。呼び出し元はこれに基づいて現在のコンテキスト サイズを計算します。 */
    fun current(history: List<Message>, contextTokens: Int): List<Message> =
        sent(valid(views[contextTokens], history), history)

    /**
     * [currentTokens]: リクエスト全体のサイズ (固定オーバーヘッドを含む、[UsageMeter] を参照)。 null の場合、[current] と [fixedOverhead] を加算して全額を推定します。トリガーポイントを越えるまで圧迫は起こりません。
     * [aggressive]: モデルはコンテキストが長すぎると報告しました。見積もりは見ないでください。最新の [AGGRESSIVE_KEEP] 往復押しを 1 回だけ保持するには、これを押します (その後は追加のみ)。
     * [fixedOverhead]: 毎回送信する必要があるが、[history] には含まれていない部分 (基本的なプロンプト ワード、ツール定義) の推定 (比率は乗算されません)。
     * は、リクエスト全体を見積もり、メッセージが占めることができる量を計算する場合にのみ使用されます。トリガーポイント自体は減点されません。
     * [maxImages]: サービス プロバイダーは、一度に最大数の画像を受信できます (送信された画像は通常、プレースホルダーに置き換えられず、それを超える場合は最も古い画像に置き換えられます)。
     */
    fun compress(
        history: List<Message>,
        contextTokens: Int,
        currentTokens: Int? = null,
        aggressive: Boolean = false,
        fixedOverhead: Int = 0,
        maxImages: Int = Int.MAX_VALUE,
    ): Compressed {
        val limits = ContextLimits.of(contextTokens, triggerOverride)
        val last = views[contextTokens]
        val view = valid(last, history)
        val sent = sent(view, history)
        val size = currentTokens ?: UsageMeter.of(sent, fixedOverhead, scale)
        // ヒステリシス: 圧縮後、再度圧縮する前に少なくとも 1 つのマージンが必要になります。履歴をトリガー ポイントより下に圧縮できない場合、ステップごとに圧縮されるわけではありません。ただし、利用可能なウィンドウを超えることはなく、ステップごとに圧縮されます。
        val trigger = view?.let {
            maxOf(limits.trigger, UsageMeter.estimate(it.estimate + fixedOverhead, scale) + limits.margin).coerceAtMost(limits.usable)
        } ?: limits.trigger
        val compact = (last != null && view == null) || (aggressive && view?.aggressive != true) || size > trigger
        if (!compact) return Compressed(HistoryPruner.limitImages(sent, maxImages), view?.folded ?: 0)
        val next = compact(history, limits, fixedOverhead, aggressive)
        views[contextTokens] = next
        return Compressed(HistoryPruner.limitImages(next.base, maxImages), next.folded, rewritten = true)
    }

    /**
     * モデルは、コンテキストが長すぎることを報告します。完全な履歴から最初のレベルを再度押します (履歴は長くなり、最後に強く押した後の圧力はさらに重くなります)。
     * 押されたものが今送信したものより小さい場合は、それを置き換えて true を返します (呼び出し側はそれに応じてそれを再送信します)。同じサイズの場合は押せないことを意味するので false を返します。
     */
    fun overflow(history: List<Message>, contextTokens: Int, fixedOverhead: Int = 0): Boolean {
        val current = sent(valid(views[contextTokens], history), history)
        val next = compact(history, ContextLimits.of(contextTokens, triggerOverride), fixedOverhead, aggressive = true)
        if (next.estimate >= TokenEstimate.of(current)) return false
        views[contextTokens] = next
        return true
    }

    /** 圧縮の対象となる履歴が変更されていない場合にのみ使用します。それ以外の場合は (通常のプロセスでは起こりません)、再圧縮します。 */
    private fun valid(v: View?, history: List<Message>) = v?.takeIf { it.covered.size <= history.size && history.subList(0, it.covered.size) == it.covered }

    /** 常にコピー: 発信者の履歴は後で追加されます。 */
    private fun sent(view: View?, history: List<Message>) =
        if (view == null) history.toList() else view.base + history.subList(view.covered.size, history.size)

    /**
     * は軽いものから重いものへと 1 回圧縮され、最初のリクエストは [ContextLimits.target] 以下のリクエスト全体を圧縮するために使用されます (最も重いリクエストは圧縮できません)。
     * 最新の[keepRecent]往復前の古い結果のみを短縮→サマリーに折り込み→最新の往復だけ残し、残りを短縮して折り込みます。
     * の往復は[keepRecent]、[AGGRESSIVE_KEEP]、1回となります。モデルが長すぎると報告された場合、モデルは [AGGRESSIVE_KEEP] から始まります。
     * 最も重い第 1 レベルのリクエスト全体が依然として [ContextLimits.usable] を超える場合 (最新の結果自体が大きすぎる)、最新の結果から始めて、対応できるまで大きな結果を短縮します。
     * はメッセージの推定値を比較します。ターゲット ウィンドウと利用可能なウィンドウから最初に固定オーバーヘッド (同じ比率) が差し引かれ、今回は固定オーバーヘッドのみがカウントされます。
     */
    private fun compact(history: List<Message>, limits: ContextLimits, fixedOverhead: Int, aggressive: Boolean): View {
        val overhead = UsageMeter.estimate(fixedOverhead, scale)
        val target = limits.target - overhead
        val window = limits.usable - overhead
        val ladder = sequence {
            if (!aggressive) {
                val pruned = HistoryPruner.prune(history, tools, HistoryPruner.Cut(keepRecent))
                yield(Compressed(pruned, 0))
                yield(fold(pruned, keepRecent, false))
            }
            val last = HistoryPruner.prune(history, tools, HistoryPruner.LAST)
            for (keep in listOfNotNull(keepRecent.takeUnless { aggressive }, AGGRESSIVE_KEEP, 1).distinct()) yield(fold(last, keep, aggressive))
        }
        var chosen = Compressed(history, 0)
        for (c in ladder) {
            chosen = c
            if (tokens(c.messages) <= target) break
        }
        if (tokens(chosen.messages) > window) chosen = chosen.copy(messages = HistoryPruner.shrinkOversized(chosen.messages) { tokens(it) <= window })
        // コピー: 何もカットされない場合、追加される発信者の履歴が選択される可能性があります。
        return View(history.toList(), chosen.messages.toList(), chosen.foldedSteps, aggressive)
    }

    private fun fold(pruned: List<Message>, keep: Int, aggressive: Boolean): Compressed {
        // 往復 = アシスタント メッセージと次のアシスタント メッセージの前のメッセージ。前のものが始まり（タスクオリジナルの言葉、基準など）
        val starts = pruned.indices.filter { pruned[it].role == Role.Assistant }
        if (starts.isEmpty()) return Compressed(pruned, 0)
        val toolStarts = starts.filter { i -> pruned[i].parts.any { it is Part.ToolCall } }
        if (toolStarts.size <= keep) return Compressed(pruned, 0)
        val cut = toolStarts[toolStarts.size - keep]
        // アクションには画面が付属しています。折りたたみ境界線の前の最新の全画面表示とその後の違いは密接に関連しており、その違いを理解するのはそれらに依存します (ハード圧縮時に折りたたむと、ディストリビューターは全画面を送信します)。
        val rounds = starts.mapIndexed { k, start -> pruned.subList(start, starts.getOrElse(k + 1) { pruned.size }) }
        val screenRounds = rounds.map(::screenKind)
        val before = starts.count { it < cut }
        // 残した画面だけで差分を解釈できる場合は、圧縮境界より前の全量画面を保持しません。
        val anchor = if (aggressive || !diffScreens || selfContained(pruned.subList(cut, pruned.size))) -1
            else (0 until before).lastOrNull { screenRounds[it] == ScreenKind.FULL } ?: -1

        val out = ArrayList<Message>(pruned.subList(0, starts.first()))
        // 連続して折り畳まれたラウンドトリップごとに要約が取得され、このセクションの元の位置に配置されます。途中で予約された往復がセクションを区切ります。
        // が構造化されている場合、概要は 1 つだけあり、最初の段落に配置され、各段落のステップが順番に含まれます。
        var block: MutableList<String>? = null
        var blockAt = 0
        var folded = 0
        var steps: StepSummary? = null
        var stepsAt = 0
        fun closeBlock() {
            if (!structured) block?.takeIf { it.isNotEmpty() }?.let { out.add(blockAt, summary(it)) }
            block = null
        }
        starts.takeWhile { it < cut }.forEachIndexed { k, start ->
            val end = starts.getOrElse(k + 1) { pruned.size }
            val round = pruned.subList(start, end)
            // は各ラウンドの最初の呼び出しのみを実行し、残りはParallel_tool_call_skippedで返されるため、最初の呼び出しに基づいて保持するかどうかを判断します
            val call = round.first().parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (tools.verb(call?.name) in PINNED_VERBS || (anchor >= 0 && k >= anchor && screenRounds[k] != ScreenKind.NONE)) {
                closeBlock()
                out += round
                return@forEachIndexed
            }
            val lines = block ?: mutableListOf<String>().also { block = it; blockAt = out.size }
            out += round.filter { isPinnedContext(it) || it.author == Author.User }.map(ContextBuilder::withoutRuntimeState)
            if (call != null) {
                lines += line(k + 1, call, round)
                folded++
                if (structured) {
                    if (steps == null) stepsAt = blockAt
                    steps = (steps ?: StepSummary(goal(pruned.subList(0, starts.first())))).next(lines.last(), okResults(round), said(round))
                }
            }
        }
        closeBlock()
        steps?.let { out.add(stepsAt, notice(it.render(HEADER))) }
        out += pruned.subList(cut, pruned.size)
        return Compressed(out, folded)
    }

    private enum class ScreenKind { NONE, FULL, DIFF }

    /** このラウンドの結果に画面はありますか。observe/list_elements の完全な要素リスト、または screen.full が true の場合、全額がカウントされ、残りの画面は差分としてカウントされます。 */
    private fun screenKind(round: List<Message>): ScreenKind {
        val verbs = round.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().associate { it.id to tools.verb(it.name) }
        var kind = ScreenKind.NONE
        for (r in round.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()) {
            val obj = (if ("\"elements\":[" in r.content || "\"screen\":{" in r.content) parse(r.content) else null) ?: continue
            val screen = obj["screen"] as? JsonObject
            when {
                verbs[r.callId] in OBSERVATIONS && obj["elements"] is JsonArray -> return ScreenKind.FULL
                screen != null && screen["full"] == JsonPrimitive(true) -> return ScreenKind.FULL
                screen != null -> kind = ScreenKind.DIFF
            }
        }
        return kind
    }

    /** [kept] に省略されていない画面があり、すべての差分の base も含まれることを確認します。スマホとWebの差分列を別々に照合し、置換済みの画面は数えません。 */
    private fun selfContained(kept: List<Message>): Boolean {
        val ids = HashSet<String>()
        val bases = HashSet<String>()
        for (r in kept.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()) {
            val obj = (if ("\"snapshotId\"" in r.content) parse(r.content) else null) ?: continue
            val screens = listOfNotNull(obj.takeIf { it["elements"] is JsonArray }, obj["screen"] as? JsonObject)
            for (s in screens) {
                (s["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(ids::add)
                (s["base"] as? JsonPrimitive)?.contentOrNull?.let(bases::add)
            }
        }
        return ids.isNotEmpty() && ids.containsAll(bases)
    }

    private fun summary(lines: List<String>) = notice((listOf(HEADER, SUMMARY_REMINDER) + lines).joinToString("\n"))

    private fun notice(text: String) = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Notice, SUMMARY_SOURCE, text))), Author.Mika)

    /** 先頭のユーザーの元の単語 (コンテキスト シェルにない単語)。 */
    private fun goal(head: List<Message>): String = head.firstOrNull { it.author == Author.User }
        ?.parts?.filterIsInstance<Part.Text>()?.firstOrNull()?.text?.replace('\n', ' ')?.trim()?.let { if (it.length <= GOAL_LIMIT) it else it.take(GOAL_LIMIT) + "…" }
        .orEmpty()

    /** このラウンドのツールの結果は成功しました。 */
    private fun okResults(round: List<Message>): List<JsonObject> = round.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
        .filter { !it.isError }.mapNotNull { parse(it.content) }.filter { (it["ok"] as? JsonPrimitive)?.booleanOrNull != false }

    /** モデルがこのラウンドで言ったこと。 */
    private fun said(round: List<Message>): String? =
        round.first().parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }.trim().takeIf { it.isNotEmpty() }

    /** 折りたたまれたときにラウンドとともに保持されるコンテキスト (参考資料、スキルなど、[ContextKind.pinned] を参照)。 */
    private fun isPinnedContext(m: Message) = ContextBuilder.kindOf(m)?.pinned == true


    private fun line(step: Int, call: Part.ToolCall, round: List<Message>): String {
        val target = target(parse(call.argsJson))?.let { "(${clip(it)})" }.orEmpty()
        val result = round.firstNotNullOfOrNull { m ->
            m.parts.firstNotNullOfOrNull { (it as? Part.ToolResult)?.takeIf { r -> r.callId == call.id } }
        }
        // は画面を読み取り、スクリーンショット番号を残します。この番号は、view_past_screens レビューを折りたたんだ後も使用できます。
        // 書き込み操作結果の画面にはスクリーンショット番号も付いています。画面がない場合、アクションに関連付けられた画面番号が結果の最上位に表示されます。
        val shot = result?.let { parse(it.content) }?.let { o -> if (tools.verb(call.name) in OBSERVATIONS) o["screenshotId"] else (o["screen"] as? JsonObject)?.get("screenshotId") ?: o["screenshotId"] }
            ?.let { it as? JsonPrimitive }?.contentOrNull?.let { " #$it" }.orEmpty()
        return "Step $step ${clip(call.name)}$target$shot → ${outcome(result)}"
    }

    private fun outcome(r: Part.ToolResult?): String {
        r ?: return NO_RESULT
        val obj = parse(r.content)
        if (obj == null) {
            // は JSON ではありません: エラーが発生したときに認識できる場合はエラー コードを書き込みます
            val code = r.content.trim().takeIf { CODE.matches(it) }
            return if (r.isError) code ?: FAILED else APPLIED
        }
        fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        return when {
            // はエラー コードのみを認識します。説明テキストは長くても短くてもよく、要約は含まれません。
            r.isError || (obj["ok"] as? JsonPrimitive)?.booleanOrNull == false -> (s("error") ?: s("reason"))?.takeIf { CODE.matches(it) } ?: FAILED
            (obj["changed"] as? JsonPrimitive)?.booleanOrNull == false -> UNCHANGED
            else -> APPLIED
        }
    }

    private fun target(args: JsonObject?): String? {
        args ?: return null
        TARGET_KEYS.forEach { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it } }
        val x = (args["x"] as? JsonPrimitive)?.contentOrNull
        val y = (args["y"] as? JsonPrimitive)?.contentOrNull
        return if (x != null && y != null) "$x,$y" else null
    }

    private fun clip(s: String): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length <= TARGET_LIMIT) one else one.take(TARGET_LIMIT) + "…"
    }

    private fun parse(json: String): JsonObject? = try {
        Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    companion object {
        const val HEADER = "Earlier steps (compressed):"
        const val SUMMARY_REMINDER = "Only the latest user message is the current task, and the tools are still available; earlier user messages are for reference only."
        private const val SUMMARY_SOURCE = "earlier steps"
        const val AGGRESSIVE_KEEP = 6
        /**
         * 校正時に乗算比を推定するための実際の量はありません。大まかに見積もると、英語と JSON は小さすぎることがよくあります (トークナイザーはそれらをより小さな部分に分割します)。最初に長く押しすぎてから強く押すよりも、早めに押す方が良いです。
         */
        const val UNCALIBRATED_SCALE = 1.2
        private const val APPLIED = "applied"
        private const val UNCHANGED = "no change"
        private const val FAILED = "failed"
        private const val NO_RESULT = "no result"
        private const val TARGET_LIMIT = 20
        private const val GOAL_LIMIT = 120
        private val CODE = Regex("[a-z][a-z0-9_:]{0,63}")
        /** 提出、質問、確認の結果 (回答がツールの結果であるか、後続のユーザー メッセージであるかに関係なく) は完全に保持されます。 */
        private val OBSERVATIONS = setOf(Verb.Observe, Verb.ListElements)
        private val PINNED_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        private val TARGET_KEYS = listOf("target", "text", "package", "query", "direction", "key", "name", "ref", "seconds")
    }
}
