package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import com.wangzan101.mika.ui.StatusCopy

/**
 * カード内の 1 つの内容: [kind]/[line] はカプセル上の内容と一致しています。 [startedAt] は走行時等に存在します（時刻を表示）。
 * [ahead]/[behind] は乗っ取りチームの中でその前にあり、その後ろにはさらにいくつかあります (メイン画面で一度に引き渡せるアプリは 1 つだけです)。
 */
data class CardRun(
    val run: CapsuleRun,
    val kind: CapsuleKind,
    val line: String,
    val startedAt: Long? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
)

/**
 * カードには現時点で何が表示されますか: カードには 1 つのことだけが記載されており、リスト ([CapsuleCards.of] の選択方法) は記載されていません。あなたが今アプリやWebページで操作しているのはタスクカード([Detail])です。
 * それ以外の場合は、そのダイアログのダイアログ カード ([Chat])。タスクがない場合（スイッチが「常に表示」）、タスクのない対話カードでもあります。
 */
sealed interface CardView {
    /** タスク カード: 小さな画面、手順、やるべきこと、ボタン。 */
    data class Detail(val run: CardRun, val content: CardContent) : CardView

    /**
     * ダイアログ カード: [conversationId] そのダイアログの前後関係 ([ChatThread] を参照)。 [run] とは、それがどこにあるのかという会話です。[content] は、現時点でどのように見えるかです。
     * (回答または同意を求められる場合、ボタンは会話の下に固定されます)。タスクがない場合、それはアイドル カードです。ユーザーが現在参加している会話 ([current]) が続行されます。記録がない場合は最新のものから継続します。
     * ([title] はタイトルです); [fresh] は新しい会話です。会話がない場合、[conversationId] は null となり、新しい会話のみを開くことができます。
     */
    data class Chat(
        val conversationId: String?,
        val run: CardRun? = null,
        val content: CardContent? = null,
        val title: String? = null,
        val fresh: Boolean = false,
        /**
         * ユーザーは現在アプリでこの会話に参加しています ([CapsuleIdle] を参照): 他の会話で終了し、まだハングしているカードを取得しないでください。
         * 「最近の会話を続ける」の一番下の行は誤りです。
         */
        val current: Boolean = false,
    ) : CardView {
        /** どの会話がカードにリストされているか、および送信された場合にどの会話が入力されるか: アイドル状態のカードに新しい会話がある場合、それは null になります (発信者によって新しい会話が開かれます)。 */
        val target: String? get() = if (fresh) null else conversationId
    }

    /** バックグラウンドで実行されたままのページ: リアルタイム画面、自動的に閉じるまでにかかる時間、どの会話からのものか、「会話を開く」と「Web ページを閉じる」。入力ボックスはありません。 */
    data class Kept(val page: KeptPage) : CardView
}

/** カードの下部にある入力ボックスに言葉を入力してください。実行中のタスクに口を挟む、ミカの質問に答える、同じ会話を続ける、手が空いたら話し始めます。 */
enum class CardInputKind { Steer, Answer, Continue, Start }

data class CardInput(val hint: String, val kind: CardInputKind)

/** 詳細では、ステータスに応じて内容が表示されます。ボタンはタスク サービスの既存のアクションに変換されます。 */
sealed interface CardContent {
    /** 作業中: 完了したステップ ([CapsuleCards.STEPS] ステップまで)、現在最後のステップが実行中です。入力ボックスの右側で停止します。 */
    data class Working(val steps: List<String>) : CardContent

    /**
     * はあなたに引き継いでもらいたいです: [need] は「あなたが必要です:...」という文で、[body] はその理由です (モデルに書かれている場合は、支払い時に製品、金額、住所を追加してください)。
     * が他のテイクオーバーの後に順位付けされている場合、[canTakeOver] は false となり、[queueLine] は順序を示します。
     */
    data class Takeover(val need: String, val body: String, val queueLine: String?, val canTakeOver: Boolean) : CardContent

    /** では、支出操作に同意する必要があります (同意できるのは 1 回だけです): 会話ページの確認カードと同じ一連の単語と番号 [key]。 */
    data class Confirm(val title: String, val body: String, val key: Int) : CardContent

    /** ミカはあなたに尋ねました: [options] を与えると、ボタンとしてリストされます。選択肢がない場合は、「Mika」に答えを入力してください（カードへの入力は次に行われます）。 */
    data class Question(val prompt: String, val options: List<String>) : CardContent

    /** およびその他の条件: 停止する理由。 [resumable]の場合は「続行」を指定してください。 */
    data class Held(val why: String, val resumable: Boolean) : CardContent

    /** 完了 / 未完了 / 停止: 答えはダイアログ カードにあります。 */
    data class Result(val kind: CapsuleKind) : CardContent
}

/** カード コンテンツの純粋なロジック: タスク カードまたはダイアログ カードのどの項目を選択するか、各状態に何を入れるか、どの手順を実行するか。 */
object CapsuleCards {
    /** 作業中に実行する最大ステップ数 (メモリを制限するため): カード内の部分は小さな画面と同じ高さです。上にスクロールすると、配置できない古いステップが表示されます。 */
    const val STEPS = 50

    val QUEUED_AHEAD: String get() = tr(R.string.card_queued_ahead)

    /**
     * を展開するとどの項目が表示されますか (項目は 1 つだけで、リストやカードの切り替えはありません。同時に他の項目がある場合は、Mika の会話ページに移動して確認してください):
     * まず、カードに送信されたばかりの会話のタスク ([follow]) (進行中のものが優先されます)。これにより、ユーザーはカード内の回答を確認できます。
     * それ以外の場合は、[runs] の順序で最初の項目を取得します。[CapsuleModel.cardRuns] は、最初に処理 (引き継ぎ、同意、回答) できるようにキューに入れられ、次に進行中の項目を取得します。
     * は、終了したばかりでまだ消えていない最後のものです。何も残っていない場合、タスクがバックグラウンドでまだ実行されている後に残ったページ ([kept]) がそのカードになります。
     * は [idle] ではなくなりました (null の場合は閉じられます)。選択した項目がタスクカードであるかダイアログカードであるかは、[taskCard]に表示されます。
     *
     * [idle] ユーザーが現在会話中 ([CardView.Chat.current])、終了してまだ保留中の他の会話はカードを取得しません。
     * それ以外の場合、会話ページで新しい会話を開いた場合でも、カプセルをクリックすると前の会話の結果カードが表示されます。
     */
    fun of(runs: List<CardRun>, follow: String? = null, idle: CardView.Chat? = null, kept: KeptPage? = null): CardView? {
        val pick = follow?.let { f ->
            runs.firstOrNull { it.run.conversationId == f && it.kind.active } ?: runs.firstOrNull { it.run.conversationId == f }
        } ?: runs.firstOrNull()
        // は、「他の会話が終了し、まだ保留中」という項目のみを拒否します。項目がなくなっても、残っているページはそのカードのままです (「保持」セクションを参照)
        val elsewhere = idle?.current == true && pick != null && !pick.kind.active && pick.run.conversationId != idle.conversationId
        if (elsewhere) return idle
        pick ?: return kept?.let(CardView::Kept) ?: idle
        return if (taskCard(pick)) CardView.Detail(pick, content(pick))
            else CardView.Chat(pick.run.conversationId, pick, content(pick))
    }

    /**
     * 現在のステータスを押します。プロセスは押さないでください。プロセスはまだ終わっておらず、現在アプリまたは Web ページを操作しているタスク カード ([CapsuleRun.onScreen])。
     * その他（純粋なチャット、終了したもの、アプリを操作して取引完了後に電話に応じたものも含む）はすべて会話カードです。
     */
    fun taskCard(r: CardRun): Boolean = r.kind.active && r.run.onScreen

    val HINT_STEER: String get() = tr(R.string.chat_placeholder_busy)
    val HINT_ANSWER: String get() = tr(R.string.card_hint_answer)
    val HINT_CONTINUE: String get() = tr(R.string.card_hint_continue)
    val HINT_START: String get() = tr(R.string.chat_empty_prompt)

    /** カードの下部に入力ボックスはありますか?誰に渡せばいいですか？私があなたにそれを引き継いでほしい、あるいは同意してほしいと思っても、誰もいません（最初にその問題に対処してください）。無料カードが始まりです。 */
    fun input(card: CardView): CardInput? = when (card) {
        is CardView.Chat -> card.content?.let(::inputFor) ?: CardInput(HINT_START, CardInputKind.Start).takeIf { card.run == null }
        is CardView.Detail -> inputFor(card.content)
        is CardView.Kept -> null
    }

    private fun inputFor(content: CardContent): CardInput? = when (content) {
        is CardContent.Working, is CardContent.Held -> CardInput(HINT_STEER, CardInputKind.Steer)
        is CardContent.Question -> CardInput(HINT_ANSWER, CardInputKind.Answer)
        is CardContent.Result -> CardInput(HINT_CONTINUE, CardInputKind.Continue)
        is CardContent.Takeover, is CardContent.Confirm -> null
    }

    /**
     * カード入力ボックスにどのダイアログが入力されているか (スクリーンショットとともに): タスク カードとタスクを含むダイアログ カードは、そのものが配置されているダイアログです。アイドルカードはそれに続くダイアログです。
     * 新しい会話が選択されている場合、またはまだ会話がない場合は null になります (新しい会話は発信者によって開かれます)。
     */
    fun conversationOf(card: CardView): String? = when (card) {
        is CardView.Detail -> card.run.run.conversationId
        is CardView.Chat -> card.run?.run?.conversationId ?: card.target
        is CardView.Kept -> card.page.conversationId
    }

    /** タスクがまだ進行中の場合 (作業中、ミカがあなたに尋ねるなど)、入力ボックスの右側にあるストップ キーでどの項目を停止する必要があります。それ以外の場合は null です。 */
    fun stopKey(card: CardView): String? {
        val (run, content) = when (card) {
            is CardView.Detail -> card.run to card.content
            is CardView.Chat -> (card.run ?: return null) to (card.content ?: return null)
            is CardView.Kept -> return null
        }
        val live = content is CardContent.Working || content is CardContent.Question || content is CardContent.Held
        return run.run.key.takeIf { live }
    }

    fun content(r: CardRun): CardContent {
        val ex = r.run.exchange
        return when (val s = r.run.state) {
            is TaskState.WaitingTakeover -> CardContent.Takeover(
                need = r.line,
                body = listOfNotNull(s.message.trim().ifEmpty { null }, StatusCopy.paymentLines(s.item, s.amount, s.address)).joinToString("\n"),
                queueLine = when {
                    r.ahead > 0 -> QUEUED_AHEAD
                    r.behind > 0 -> trPlural(R.plurals.capsule_behind, r.behind)
                    else -> null
                },
                canTakeOver = r.ahead == 0 && r.kind == CapsuleKind.NeedTakeover,
            )
            is TaskState.WaitingConfirmation -> (ex?.status as? ExchangeStatus.NeedsConfirmation)
                ?.let { CardContent.Confirm(it.title, it.body, it.key) }
                // この Q&A が開始される前 (フォローを開始したばかり)、アプリ名の代わりにパッケージ名が使用されていました。
                ?: CardContent.Confirm(
                    ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, s.request.app),
                    ConfirmationNotifier.requestKey(s),
                )
            is TaskState.WaitingInput -> CardContent.Question(s.prompt.trim(), s.options.orEmpty().filter(String::isNotBlank))
            is TaskState.Held -> CardContent.Held(s.reason.why.trim().ifEmpty { tr(R.string.capsule_hold) }, s.reason.resumable)
            is TaskState.Succeeded -> CardContent.Result(CapsuleKind.Done)
            is TaskState.Failed -> CardContent.Result(CapsuleKind.Failed)
            TaskState.Cancelled -> CardContent.Result(CapsuleKind.Cancelled)
            else -> CardContent.Working(steps(ex, r.line))
        }
    }

    /** 最新の [STEPS] ステップ (古い順): 実際に行ったこと (ダイアログ ページのステップと同じこと)、現在行っている文は最後のステップとは異なります。異なる場合はそれに準じます。 */
    fun steps(ex: Exchange?, line: String): List<String> {
        val done = ex?.steps.orEmpty().map { it.text }
        val all = if (line.isNotBlank() && done.lastOrNull() != line) done + line else done
        return all.takeLast(STEPS)
    }

    /** カードの上部にある小さな文字には、これがどのようなステータスにあるかが示されています。 */
    fun status(kind: CapsuleKind): String = when (kind) {
        CapsuleKind.HandedOver -> tr(R.string.capsule_handed_over)
        CapsuleKind.NeedTakeover -> tr(R.string.takeover_title)
        CapsuleKind.NeedConfirm -> tr(R.string.chat_wait_approval)
        CapsuleKind.NeedAnswer -> tr(R.string.chat_wait_answer)
        CapsuleKind.NeedStep -> tr(R.string.takeover_title)
        CapsuleKind.Held -> tr(R.string.capsule_hold)
        CapsuleKind.Running -> tr(R.string.capsule_running)
        CapsuleKind.Done -> tr(R.string.capsule_done)
        CapsuleKind.Failed -> tr(R.string.capsule_failed)
        CapsuleKind.Cancelled -> tr(R.string.capsule_stopped)
        CapsuleKind.KeptPage -> tr(R.string.kept_capsule)
        CapsuleKind.Idle -> "Mika"
    }

    /** この種のコンテンツの隣に小さな仮想画面イメージを配置するかどうか (実際のイメージも必要です): 作業中、引き継ぎを求めるとき、およびその他の条件。 */
    fun showsScreen(content: CardContent): Boolean =
        content is CardContent.Working || content is CardContent.Takeover || content is CardContent.Held
}
