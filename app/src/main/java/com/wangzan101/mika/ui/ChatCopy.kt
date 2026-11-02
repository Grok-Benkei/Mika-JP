package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural

/** ダイアログ ページのステータスに従って計算されたテキスト。界面には触れず、単回測定で直接測定してください。 */
object ChatCopy {
    /** 期間別の空の会話型挨拶。 */
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> tr(R.string.chat_greeting_morning)
        in 11..12 -> tr(R.string.chat_greeting_noon)
        in 13..17 -> tr(R.string.chat_greeting_afternoon)
        in 18..22 -> tr(R.string.chat_greeting_evening)
        else -> tr(R.string.chat_greeting_night)
    }

    val EMPTY_PROMPT get() = tr(R.string.chat_empty_prompt)

    /** 空のダイアログの例。クリックして入力ボックスに入力すると、ユーザーは送信する前に入力ボックスを変更します。 [kind] は、どの回線アイコンに一致するかを決定します。 */
    enum class ExampleKind { App, Web, File, Schedule, Device }
    data class Example(val kind: ExampleKind, val text: String)

    val examples get() = listOf(
        Example(ExampleKind.App, tr(R.string.chat_example_message)),
        Example(ExampleKind.App, tr(R.string.chat_example_group_summary)),
        Example(ExampleKind.Web, tr(R.string.chat_example_jobs)),
        Example(ExampleKind.App, tr(R.string.chat_example_music)),
        Example(ExampleKind.Device, tr(R.string.chat_example_alarms)),
        Example(ExampleKind.App, tr(R.string.chat_example_creator)),
    )

    /** 会話ページの上部バーのタイトル: 常にアプリ名です。サブタイトルには現在何が行われているかが記載されています。 */
    const val APP_TITLE = "Mika"

    /** ダイアログの名前: 最初の文 (最初の行のみが取得され、添付ファイルの行はカウントされません。画面のみが送信される場合、それは「現在の画面」です)。空のダイアログは空です。サイドバーで会話を呼び出すために使用します。 */
    fun title(exchanges: List<Exchange>): String =
        exchanges.firstOrNull { it.goal.isNotBlank() }?.goal?.let(::plainTitle).orEmpty()

    /**
     * タイトルとしてユーザーの言葉を記述する方法: Web ページの説明 ([AttachedPage])、引用 ([QuoteMessage])、添付ファイルの行 (添付ファイルのみを送信する場合は「写真」、「契約書.pdf」など)、画面の行 (画面のみを送信する場合は「現在の画面」) を削除します。
     * は最初の行のみを取得します。
     */
    fun plainTitle(text: String): String =
        ScreenCopy.plain(AttachmentMessage.plain(QuoteMessage.words(AttachedPage.words(text)))).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * ブランチのタイトル: ソース タイトル (または最初の文) の後にローカリゼーション サフィックスを追加します。合計の長さは [max] ワードを超えてはなりません。
     * はソースのタイトルを切り取り、サフィックスを残します。コードポイントごとにカットされ、絵文字は半分にカットされません。
     */
    fun branchTitle(base: String, suffix: String, max: Int = 24): String {
        val room = (max - suffix.length).coerceAtLeast(0)
        var end = 0
        var taken = 0
        while (end < base.length && taken < room) {
            end += Character.charCount(base.codePointAt(end))
            taken++
        }
        return base.substring(0, end).trimEnd() + suffix
    }

    enum class Tone { Accent, Warn, Ok }

    /** 一番上の列のサブタイトル: この会話では 1 ページがバックグラウンドで実行されたままになります (タスクは実行されていません)。 */
    val KEPT_PAGE: String get() = tr(R.string.kept_chat_sub)

    /** トップバーのサブタイトル: 何をしていますか (アクセントカラー)、または待っています (オレンジ色)。実行中のタスクがない場合は null。 [place] は、動作しているアプリまたは Web サイトです。 */
    fun subtitle(ex: Exchange?, place: String?): Pair<String, Tone>? = when (ex?.status) {
        null -> null
        ExchangeStatus.Working -> (if (place.isNullOrBlank()) tr(R.string.chat_working) else tr(R.string.chat_working_on, place)) to Tone.Accent
        ExchangeStatus.WaitingUnlock -> tr(R.string.chat_wait_unlock) to Tone.Warn
        is ExchangeStatus.Queued -> tr(R.string.chat_queued) to Tone.Accent
        is ExchangeStatus.NeedsTakeover, is ExchangeStatus.NeedsInput,
        is ExchangeStatus.NeedsConfirmation, is ExchangeStatus.Paused -> tr(R.string.chat_waiting_for_you) to Tone.Warn
        else -> null
    }

    /** 進行状況ライン: 実行中は現時点での進行状況、停止して待機している場合は「一時停止」になります。 */
    fun progressLine(ex: Exchange): String =
        if (paused(ex.status)) tr(R.string.chat_paused) else liveLine(ex) ?: tr(R.string.chat_working)

    /** タスクは停止して待機します (処理、回答、同意、ロック解除、[続行] をクリックする必要があります)。 */
    fun paused(status: ExchangeStatus): Boolean =
        status is ExchangeStatus.NeedsTakeover || status is ExchangeStatus.NeedsInput ||
            status is ExchangeStatus.NeedsConfirmation || status is ExchangeStatus.Paused || status == ExchangeStatus.WaitingUnlock

    /** 進行状況ラインの右側の時間:「0:24」「12:05」「1:02:03」。 */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** 実行中は、最新のステップのみがリストされます。前回の収穫ラインは「n 歩先」です。クリックするとすべてが表示されます。 */
    const val VISIBLE_STEPS = 5

    fun earlierSteps(n: Int) = trPlural(R.plurals.chat_earlier_steps, n)

    /** 携帯電話をバックグラウンドで操作しているときの手順の下の小さな文字の行。 */
    val BACKGROUND_NOTE get() = tr(R.string.chat_background_note)

    /**
     * アクションの結果 (レコード内のエフェクト コード → 人々が見るための文)。エラーコードは開発においてのみ意味を持ちますが、
     * は、人が見ると必ず「成功しません」と言います。実行中のプロセス ページでも同じステートメントが使用されます。
     */
    fun effectLabel(effect: String?): String = when (effect) {
        "changed", "done", "place_changed" -> tr(R.string.result_effective)
        "small_change" -> tr(R.string.effect_small_change)
        "unchanged", "no_effect" -> tr(R.string.result_unchanged)
        "completed" -> tr(R.string.result_completed)
        null, "" -> ""
        else -> tr(R.string.step_failed)
    }

    /** ステップをクリックすると、それぞれの内容に 1 行が表示されます: 何が行われたか・何が行われたか・結果 (結果が空の場合は書き込まないでください)。 */
    fun actionLine(a: StepAction): String =
        listOfNotNull(a.label, a.target?.takeIf(String::isNotBlank), effectLabel(a.effect).takeIf(String::isNotBlank)).joinToString(" · ")

    fun placeholder(mode: ComposerMode, hasHistory: Boolean): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.chat_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.chat_placeholder_answer)
        ComposerMode.Idle -> if (hasHistory) tr(R.string.chat_placeholder_more) else EMPTY_PROMPT
    }

    /** は、話すときに灰色の単語を押し続けることができます (前面に小さなマイクが付いています)。長押しして話すことができ、クリックして入力できることを人々に伝えます。タスクの実行中に彼らが言うことは、中断することです。 */
    fun voicePlaceholder(mode: ComposerMode): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.voice_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.voice_placeholder_answer)
        ComposerMode.Idle -> tr(R.string.voice_placeholder_idle)
    }
}
