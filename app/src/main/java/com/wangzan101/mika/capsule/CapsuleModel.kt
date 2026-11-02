package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.ui.Exchange

/** 設定で 3 つの「サスペンド カプセル」から 1 つを選択します。名前を付けて保存し、認識できない場合（未設定、古いバージョン）、デフォルトは「常に表示」になります（ダブルクリックでいつでもこの画面を送信できます）。 */
enum class CapsuleMode {
    Off, DuringTasks, Always;

    companion object {
        val DEFAULT = Always
        fun parse(name: String?): CapsuleMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 現時点でのタスクの外観: 番号 ([TaskRun.key])、ダイアログの場所、ステータス、実行されたステップ。
 * カードには、目標 [goal] と、この質問と回答が現時点でどのようなものであるか [exchange] (手順、実行するように求められていること、結果、対話ページと同じ予測セット) も必要です。
 * [queueAhead]/[queueBehind] は、乗っ取りチームに加わる前と後で持っているアイテムの数を意味します (メイン画面では一度に 1 つのアプリのみが表示されます)。チームにいない、または不明の場合、[queueAhead]は-1、
 * このとき、引き継ぎ待ちを開始した順に計算されます。 [runId] はその実行中のレコードです (会話に記録されたラウンドには同じ番号が付いています。レコードが開始されていない場合は null です)。
 * [onScreen] は現在画面を操作しています。独自の仮想画面があります ([com.wangzan101.mika.task.TaskRun.displayId] は -1 ではありません)。
 * または内蔵ブラウザ ([com.wangzan101.mika.task.TaskRun.webOpen]) を使用します。カードはそれに応じてタスク カードまたは会話カードを選択します。
 * [steers] は、介在する単語と宛先 ([com.wangzan101.mika.task.TaskRun.steers]) です。会話カードの前後にはオリジナルの言葉が続きます。
 */
data class CapsuleRun(
    val key: String,
    val conversationId: String,
    val state: TaskState,
    val stage: Takeover.Stage = Takeover.Stage.None,
    val goal: String = "",
    val exchange: Exchange? = null,
    val queueAhead: Int = -1,
    val queueBehind: Int = 0,
    val runId: String? = null,
    val onScreen: Boolean = false,
    val steers: List<SteerNote> = emptyList(),
)

/**
 * カプセルに表示される内容を決定するすべての入力: 各タスク ([runs]、終了したばかりでステータスが最終のものを含む)、スイッチ、ミカ自身のインターフェイスがフォアグラウンドにあるかどうか、
 * 画面がオンでロックが解除されており、アクセシビリティ サービスが実行中でオーバーレイを描画でき、カードが展開され ([cardOpen])、タスクの完了後にページがバックグラウンドで実行されたままになります ([kept])。
 */
data class CapsuleInputs(
    val runs: List<CapsuleRun>,
    val mode: CapsuleMode,
    val mikaInForeground: Boolean,
    val screenInteractive: Boolean,
    val serviceAvailable: Boolean,
    val cardOpen: Boolean = false,
    val kept: KeptPage? = null,
)

/** カプセルのステータス。優先度の高いものから低いものの順に並べられています ([ordinal] 小さいものが最初に表示されます)。 [Idle]は「常に表示」の場合はタスクが存在しないアイコンです。 */
enum class CapsuleKind {
    HandedOver, NeedTakeover, NeedConfirm, NeedAnswer,
    /** では、電話機上で手を動かす必要があり (システム選択ボックスで手を選択するなど)、移動後も自動的に続行されます。 */
    NeedStep,
    Held, Running, Done, Failed, Cancelled,
    /** 実行中のタスクはなく、タスクの実行後にページがバックグラウンドで実行されています (「バックグラウンド Web ページが実行中」、緑色の点)。 */
    KeptPage,
    Idle;

    /** はあなたにそれを処理してもらいたいと考えています (カプセルがオレンジ色に変わります)。お譲りしたアプリ（[HandedOver]）は一番上のものであり、含まれておりません。 */
    val needsYou: Boolean get() = this == NeedTakeover || this == NeedConfirm || this == NeedAnswer || this == NeedStep

    /** はまだ終わっていません (実行中、待機中など)。 [KeptPage] および [Idle] はカウントされません。 */
    val active: Boolean get() = this != Done && this != Failed && this != Cancelled && this != KeptPage && this != Idle
}

sealed interface CapsuleView {
    data object Hidden : CapsuleView

    /**
     * はカプセル (または「処理中」という行) を表示します。 [text] は単語の行 (「合計 N 個」が綴られています)、[detail] は [CapsuleKind.HandedOver] の場合にのみ使用可能です。
     * はそのストリップの下にある小さな文字です。 [count] は進行中のタスクの数、[needCount] は処理する必要がある項目の数です。
     * [runKey]/[conversationId] はカプセルに記載されている内容です。通常はミカアイコン(丸と角マークにステータスが描かれます)ですが、
     * [pill] は、このテキスト行を含むカプセルに展開されます (完了したばかりの場合)。 [widthEpoch] が変化しない場合、カプセルは広くなるだけで狭くなりません。
     * が変更され (ステータスが変更されたか、何かが 1 つ変更されました)、幅がリセットされました。
     */
    data class Shown(
        val kind: CapsuleKind,
        val text: String,
        val detail: String? = null,
        val count: Int = 0,
        val needCount: Int = 0,
        val runKey: String? = null,
        val conversationId: String? = null,
        val pill: Boolean = false,
        val widthEpoch: Int = 0,
    ) : CapsuleView
}

/**
 * カプセルの純粋なロジック: 入力が変化する (または [nextChangeAt] に達する) たびに、現時点で表示されるべき内容を取得するために [update] を調整します。ルール:
 * - 表示されません: スイッチがオフになっており、アクセシビリティ サービスが利用できません。ミカ自身のインターフェイスが前面にあり、画面がオフまたはロックされています。
 * - 一度に最も重要なものだけが表示されます。優先順位については、[CapsuleKind] を参照してください。複数のアイテムがある場合は、「合計 N アイテム」と入力すると、対処する必要があるアイテムがオレンジ色に変わります。「対処する必要があるアイテムは 1 個 · 合計 2 個」。
 * - 通常はアイコンです。ランニング中などは、円と角のマークのみが変化し、1 ステップ変更しても展開されません。
 * - あなたが対処すべき何かがあり、ユーザーがそれをカード内で見ていない場合、それはカプセルに拡張し、カードが開かれるか、そのものがあなたを必要としなくなるまでそこに留まります。
 *   カードを開いたときに表示されるいくつかの内容 (同じもの、同じ待機時間) が、表示されたとおりに記録されます。それらをしまうと、それらは 1 つ (オレンジ色) に戻り、新しいものが来ると拡張されます。
 * - 完了/未完了/停止。最初に [PILL_MS] を展開し、次に結果コーナー マークで 1 つを縮小し、[LINGER_MS] を停止してから消えます (「常に表示」の場合はアイコンに戻ります)。
 *   カードに表示されているものは直接 1 つです。カードが展開されると、この期間中時計は停止します。ユーザーが見ている結果は取り除かれず、カードをしまった後も残り時間は継続します。
 *   は、消えると戻ってきません (呼び出し元の後にリストされている場合はカウントされません)。ダイアログが削除されると ([forget])、完了したタスクはすぐに消えます。
 * - タスクが存在しないため、結果が停止されます。ただし、タスクの後に残されたページがまだバックグラウンドで実行されている場合、「バックグラウンド Web ページ実行中」に展開され、停止したままになります (スイッチが「表示しない」の場合でも表示されません)。
 *   通知のみ);
 * - 同じもの、同じ状態で文を変更する場合は、文ごとに少なくとも [MIN_SENTENCE_MS] を停止してください。この期間中は最後の一文だけが残されます。状態や物事が変化するとすぐに変化します。
 * カードを選択する順序については、[cardRuns] を参照してください。
 * [reasonLabel] 引き継ぎ理由（ログインなど）を他の人が見える名前に置き換えます。認識されない場合はnullとなります。時刻は発信者によって与えられ、テストでは偽の時計が使用されます。
 */
class CapsuleModel(private val reasonLabel: (String) -> String? = { null }) {
    private class Ended(val run: CapsuleRun, val at: Long)

    /** あなたが今待っているものは 1 つです。同じもの、同じ状態、同じテイクオーバー ステージは同じものとしてカウントされます。 */
    private data class Mark(val key: String, val state: TaskState, val stage: Takeover.Stage)

    /** 各タスクが初めて出現する瞬間: 同じ順序で、時間がカードに表示されます。 */
    private val firstSeen = HashMap<String, Long>()
    private val ended = HashMap<String, Ended>()
    /** ダイアログの停止または削除後に削除される終了タスク: [ended] は、発信者がまだリストにある場合は記録されません。発信者がリストに表示されなくなると忘れられます。 */
    private val dropped = HashSet<String>()
    /** 引き継がれるのを待っているタスク、または開始された順序に従ってすでに引き継がれているタスク。メイン画面は一度に 1 つだけ引き渡し、前にあるタスクが最初に処理されます。 */
    private val takeoverSince = LinkedHashMap<String, Long>()
    /** ユーザーがカード内で見た内容を処理する必要があります。その結果、カプセルには展開されなくなります。 */
    private val seen = HashSet<Mark>()
    /** カードが展開される瞬間。展開中に終了したタスクはカウントダウンされません。 */
    private var frozenSince: Long? = null
    private var lastRuns: List<CapsuleRun> = emptyList()

    private var shownIdentity: Triple<CapsuleKind, String?, Boolean>? = null
    private var shownText: String? = null
    private var shownAt = 0L
    private var epoch = 0

    /** 入力が変化しない場合、出力は次回変化します (文が変更されるのを待ち、1 つ縮小し、終了後に消えます)。そうでない場合は null です。 */
    var nextChangeAt: Long? = null
        private set

    fun update(inputs: CapsuleInputs, now: Long): CapsuleView {
        nextChangeAt = null
        lastRuns = inputs.runs
        val keys = inputs.runs.map { it.key }.toSet()
        inputs.runs.forEach { firstSeen.getOrPut(it.key) { now } }
        if (inputs.cardOpen) {
            if (frozenSince == null) frozenSince = now
        } else frozenSince?.let { since ->
            // カードを回収します。拡張期間はカウントされません。完了したタスクは残りの時間で継続されます。
            val paused = now - since
            ended.replaceAll { _, e -> Ended(e.run, e.at + paused) }
            frozenSince = null
        }
        dropped.retainAll(keys)
        inputs.runs.filter { it.state.ended() && it.key !in dropped }.forEach { ended.getOrPut(it.key) { Ended(it, frozenSince ?: now) } }
        if (frozenSince == null) drop(ended.filterValues { now - it.at >= LINGER_MS }.keys)
        firstSeen.keys.retainAll(keys + ended.keys)
        val waiting = inputs.runs.filter { it.state is TaskState.WaitingTakeover && it.stage != Takeover.Stage.None }.map { it.key }
        takeoverSince.keys.retainAll(waiting.toSet())
        waiting.forEach { takeoverSince.getOrPut(it) { now } }
        // あなたが見たものだけが残ります。カードは展開中であり、この時点でカードを処理する必要があり、結果は表示されたものとみなされます。
        val marks = needMarks(inputs.runs) + ended.values.map { it.run.mark() }
        seen.retainAll(marks)
        if (inputs.cardOpen) seen += marks

        if (inputs.mode == CapsuleMode.Off || !inputs.serviceAvailable || inputs.mikaInForeground || !inputs.screenInteractive) {
            // は、最新の文が再度表示されると、以前のスロットリングを継続せずに、最新の文を直接表示します。
            shownIdentity = null
            shownText = null
            return CapsuleView.Hidden
        }
        val candidate = activeView(inputs.runs) ?: endedView(now) ?: keptView(inputs.kept) ?: idleView(inputs.mode) ?: run {
            shownIdentity = null
            shownText = null
            return CapsuleView.Hidden
        }
        return throttle(candidate, now)
    }

    /** ダイアログ [conversationId] 削除: 完了したタスクはカプセルとカードから直ちに削除され、進行中のタスクは以前と同様に残ります。 */
    fun forget(conversationId: String) = drop(ended.filterValues { it.run.conversationId == conversationId }.keys)

    private fun drop(keys: Set<String>) {
        ended.keys.removeAll(keys)
        dropped += keys
    }

    private fun needMarks(runs: List<CapsuleRun>): Set<Mark> =
        runs.filter { it.state != TaskState.Idle && !it.state.ended() && describe(it).kind.needsYou }.map { it.mark() }.toSet()

    private fun CapsuleRun.mark() = Mark(key, state, stage)

    private fun activeView(runs: List<CapsuleRun>): CapsuleView.Shown? {
        val active = runs.filter { it.state != TaskState.Idle && !it.state.ended() }
        if (active.isEmpty()) return null
        val described = active.map { it to describe(it) }
        // 同様に重要なことのうち、最初に乗っ取りチームの先頭にいるチームについて話しましょう。次に、最初にスタートしたチームについて話しましょう。
        val (top, line) = described.minWith(order)
        val count = active.size
        val needCount = described.count { it.second.kind.needsYou }
        var detail: String? = null
        val text = when {
            line.kind == CapsuleKind.HandedOver -> {
                val behind = spot(top)?.second ?: described.count { it.first !== top && it.second.kind == CapsuleKind.NeedTakeover }
                detail = if (behind > 0) trPlural(R.plurals.capsule_behind, behind)
                    else listOfNotNull(line.reason, tr(R.string.capsule_then_tap_right)).joinToString(" · ")
                line.text
            }
            count >= 2 && needCount > 0 -> tr(R.string.capsule_need_of_total, needCount, count)
            count >= 2 -> tr(R.string.capsule_of_total, line.text, count)
            else -> line.text
        }
        // カードでまだ見ていない場合は、カプセルを展開して駐車したままにしてください。
        val pill = line.kind.needsYou && described.any { (r, l) -> l.kind.needsYou && r.mark() !in seen }
        return CapsuleView.Shown(line.kind, text, detail, count, needCount, top.key, top.conversationId, pill = pill)
    }

    /** 最近終了したもの: 最初にカプセルに展開され、次に結果のコーナー マークでカプセルに格納されます。カードに見られたものは直接カプセルでした。カードが展開されると時計が止まりました。 */
    private fun endedView(now: Long): CapsuleView.Shown? {
        val last = ended.values.maxByOrNull { it.at } ?: return null
        val age = (frozenSince ?: now) - last.at
        val pill = age < PILL_MS && last.run.mark() !in seen
        if (frozenSince == null) later(last.at + if (pill) PILL_MS else LINGER_MS)
        val (kind, text) = endedLine(last.run.state)
        return CapsuleView.Shown(kind, text, runKey = last.run.key, conversationId = last.run.conversationId, pill = pill)
    }

    private fun endedLine(s: TaskState): Pair<CapsuleKind, String> = when (s) {
        is TaskState.Succeeded -> CapsuleKind.Done to tr(R.string.capsule_done_tap)
        is TaskState.Failed -> CapsuleKind.Failed to tr(R.string.capsule_failed_tap)
        else -> CapsuleKind.Cancelled to tr(R.string.capsule_stopped)
    }

    /**
     * カードから選択できるものがいくつかあります ([update] の最後の入力については、[CapsuleCards.of] を参照)。進行中のものは重要度の順に並べられています (同様に重要で最初に開始されるもの)。
     * の後に、消滅していない終了したものが続きます (最後に終了したものが前にあります)。ランニングなどでベルトが動き始めるタイミング。その前後には引き取り待ちのベルトが何本か並んでいる。
     */
    fun cardRuns(): List<CardRun> {
        val active = lastRuns.filter { it.state != TaskState.Idle && !it.state.ended() }
            .map { it to describe(it) }
            .sortedWith(order)
        val activeKeys = active.map { it.first.key }.toSet()
        return active.map { (r, line) ->
            val (ahead, behind) = spot(r) ?: (0 to 0)
            val timed = line.kind == CapsuleKind.Running || line.kind == CapsuleKind.Held
            CardRun(r, line.kind, line.text, startedAt = if (timed) firstSeen[r.key] else null, ahead = ahead, behind = behind)
        } + ended.values.filter { it.run.key !in activeKeys }.sortedByDescending { it.at }.map { e ->
            val (kind, text) = endedLine(e.run.state)
            CardRun(e.run, kind, text)
        }
    }

    /**
     * カード内の 1 つのものの外観 ([update] なし、順序不順): 他の場所 (ブラウザー ページ上のそのページのダイアログ パネル) に続くものが 1 つだけある場合に使用されます。
     * 完成したそうですが、テイクオーバーを待機しているキュー位置は、独自のキュー位置とともに使用されます。
     */
    fun cardRunOf(r: CapsuleRun): CardRun {
        if (r.state.ended()) {
            val (kind, text) = endedLine(r.state)
            return CardRun(r, kind, text)
        }
        val line = describe(r)
        return CardRun(r, line.kind, line.text, ahead = r.queueAhead.coerceAtLeast(0), behind = r.queueBehind)
    }

    /** 最も重要なことは最初です。同様に重要なことは、引き継ぎチームの最初の人が最初であり、その後に開始順序に従います。 */
    private val order = compareBy<Pair<CapsuleRun, Line>>(
        { it.second.kind.ordinal }, { spot(it.first)?.first ?: Int.MAX_VALUE }, { firstSeen[it.first.key] ?: 0L },
    )

    /** の前後にいくつかの項目があります。実際のキュー位置がある場合はそれを使用し、ない場合は開始を押して引き継ぎシーケンスを待ちます。そうでない場合は null になります。 */
    private fun spot(r: CapsuleRun): Pair<Int, Int>? {
        if (r.queueAhead >= 0) return r.queueAhead to r.queueBehind
        val queue = takeoverSince.keys.toList()
        val i = queue.indexOf(r.key)
        return if (i < 0) null else i to queue.size - 1 - i
    }

    /** ページはバックグラウンドで実行されたままになっています。カプセルに展開され、元の会話について話しています。 */
    private fun keptView(kept: KeptPage?): CapsuleView.Shown? =
        kept?.let { CapsuleView.Shown(CapsuleKind.KeptPage, KEPT, conversationId = it.conversationId, pill = true) }

    private fun idleView(mode: CapsuleMode): CapsuleView.Shown? =
        if (mode == CapsuleMode.Always) CapsuleView.Shown(CapsuleKind.Idle, "") else null

    /**
     * 同じ内容および同じ状況の文を変更します。[MIN_SENTENCE_MS] は前の文より小さいため、前の文を最初に保持し、その時点での最新の文に置き換えます。
     * 伸縮もセクション変更とみなされ、カプセル幅が再設定されます。
     */
    private fun throttle(v: CapsuleView.Shown, now: Long): CapsuleView.Shown {
        val identity = Triple(v.kind, v.runKey, v.pill)
        if (identity != shownIdentity) {
            shownIdentity = identity
            shownText = v.text
            shownAt = now
            epoch++
            return v.copy(widthEpoch = epoch)
        }
        if (v.text != shownText) {
            if (now - shownAt >= MIN_SENTENCE_MS) {
                shownText = v.text
                shownAt = now
            } else {
                later(shownAt + MIN_SENTENCE_MS)
            }
        }
        return v.copy(text = shownText ?: v.text, widthEpoch = epoch)
    }

    private fun later(at: Long) {
        nextChangeAt = nextChangeAt?.coerceAtMost(at) ?: at
    }

    private class Line(val kind: CapsuleKind, val text: String, val reason: String? = null)

    private fun describe(r: CapsuleRun): Line = when (val s = r.state) {
        is TaskState.WaitingTakeover -> when (r.stage) {
            Takeover.Stage.HandedOver -> Line(CapsuleKind.HandedOver, HANDED_OVER, reasonLabel(s.reason))
            Takeover.Stage.Waiting -> Line(
                CapsuleKind.NeedTakeover,
                tr(R.string.capsule_needs_you, reasonLabel(s.reason) ?: oneLine(s.message).ifEmpty { tr(R.string.capsule_handle_it) }),
            )
            // は差し戻された短いセクションであり、まだサイクルが下がっていません。
            Takeover.Stage.None -> Line(CapsuleKind.Running, CONTINUING)
        }
        is TaskState.WaitingConfirmation -> Line(CapsuleKind.NeedConfirm, tr(R.string.capsule_needs_ok, ConfirmationCopy.actionLabel(s.request.action)))
        is TaskState.WaitingInput -> Line(CapsuleKind.NeedAnswer, tr(R.string.capsule_asks, oneLine(s.prompt)))
        is TaskState.Held -> if (s.reason.needsYou) Line(CapsuleKind.NeedStep, tr(R.string.capsule_needs_you, oneLine(s.reason.why).ifEmpty { tr(R.string.capsule_handle_it) }))
        else Line(CapsuleKind.Held, oneLine(s.reason.why).let { if (it.isEmpty()) tr(R.string.capsule_hold) else tr(R.string.capsule_hold_why, it) })
        is TaskState.Running -> Line(CapsuleKind.Running, runningText(s))
        else -> Line(CapsuleKind.Running, THINKING)
    }

    companion object {
        const val MIN_SENTENCE_MS = 1_500L
        /** 手術完了後、カプセルに膨らむまでどのくらいの時間がかかりますか？ */
        const val PILL_MS = 4_000L
        /** 終了後、停止するまでの時間はどのくらいかかりますか（拡張部分とコーナーマークのある引っ込み部分）。 */
        const val LINGER_MS = 10_000L
        val HANDED_OVER: String get() = tr(R.string.capsule_you_handle)
        val THINKING: String get() = tr(R.string.capsule_thinking)
        val PREPARING: String get() = tr(R.string.runtime_preparing)
        val CONTINUING: String get() = tr(R.string.capsule_continuing)
        val KEPT: String get() = tr(R.string.kept_capsule)

        /** 現在何をしていますか: 再生、バッチ実行、書き込みの進行状況。それ以外の場合、最初にモデルを使用して呼び出しで記述し、次に呼び出しでインテントを使用する場合、固定されたステートメントは存在しません。 */
        fun runningText(s: TaskState.Running): String {
            s.progress?.let { return progressText(it) }
            val said = listOfNotNull(s.narration, s.reason).map(::oneLine).firstOrNull { it.isNotEmpty() }
            return said ?: if (s.step == 0) PREPARING else THINKING
        }

        fun progressText(p: RunProgress): String = when (p) {
            is RunProgress.Replay -> tr(R.string.capsule_replay, p.step, p.total)
            is RunProgress.Batch -> trPlural(R.plurals.capsule_batch, p.steps)
            is RunProgress.WaitingForApp -> tr(R.string.progress_waiting_app, p.app)
        }

        /** モデルが書いた文字を1行に潰す。 */
        private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

        private fun TaskState.ended() = this is TaskState.Succeeded || this is TaskState.Failed || this == TaskState.Cancelled
    }
}

/**
 * カプセルの透明度: アイコンに [IDLE_MS] 触れなければ [BALL] まで薄くなる（輪郭とバッジは実色のままで、UI 側が管轄する）;
 * 触れるか状態が変わればすぐに戻る（呼び出し側はその時点から計り直す）。カプセルへ展開したとき、対応してほしいとき、「ユーザーへの引き継ぎ」の項目では薄くならない。
 */
object CapsuleFade {
    const val IDLE_MS = 3_000L
    const val BALL = 0.4f

    /** [idleFor] は、最後に触れるか状態が変わってからどれだけ経ったか。 */
    fun alpha(kind: CapsuleKind, needCount: Int, pill: Boolean, idleFor: Long): Float = when {
        pill || kind.needsYou || needCount > 0 || kind == CapsuleKind.HandedOver -> 1f
        idleFor < IDLE_MS -> 1f
        else -> BALL
    }
}
