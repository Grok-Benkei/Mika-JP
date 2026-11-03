package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.ui.StatusCopy
import com.wangzan101.mika.ui.answerParts
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.ui.steerLabel
import com.wangzan101.mika.ui.steersAroundDraft

/**
 * 対話カードの1つ。 [key] は同じ会話内で変更されません (実行中のレコード番号がある場合はクリックし、ない場合は会話内のこのラウンドの位置をクリックします)。
 * 以前のラウンドをロードすると、進行中のラウンドがダイアログに記録され、リストはそのまま残ります。
 */
sealed interface ThreadItem {
    val key: String

    /** あなたが言ったこと (元の単語とタスク中に挿入された単語): 右側の小さなバブル。 [note] は、挿入された単語 (「受信、次のステップの処理」) の下の行の方向です。そうでない場合は、null になります。 */
    data class User(override val key: String, val text: String, val note: String? = null) : ThreadItem

    /** 小さな文字の行: アプリまたは Web ページを操作するときの一連の操作の結果 (「✓ WeChat · 完了」) の行、または純粋なチャットが失敗し、停止し、まだ検討中である場合の行。 */
    data class Note(override val key: String, val text: String, val kind: CapsuleKind) : ThreadItem

    /**
     * ミカの回答の全文 (カード内にフォーマットされたオリジナルのマークダウン)。 [streaming] はまだ書き込み中です。
     * [cut] は、中断によって中断されたセクションです (会話ページと同じ明るい色で、次の行「新しいメッセージによって中断されました」)。
     */
    data class Answer(override val key: String, val text: String, val streaming: Boolean = false, val cut: Boolean = false) : ThreadItem
}

/** は対話カードの前後であり、古いカードが一番上、新しいカードが一番下になります。 [hasEarlier] はロードされていない以前のラウンドです。 [title] は対話のタイトル (最初の文) です。 */
data class ChatThread(val items: List<ThreadItem>, val hasEarlier: Boolean = false, val title: String? = null) {
    /** 開くときにどの行で停止するか: あなたが言った最新の文を表示領域の一番上に置き、この質問と回答から読み始めます。言った言葉がない場合は、最初から始めてください。 */
    val anchor: Int get() = items.indexOfLast { it is ThreadItem.User }.coerceAtLeast(0)
}

/** は、操作したアプリや Web サイトとともに、一連の会話として記録されています ([places]、実行記録から読み取られます。純粋なチャットで記録がクリアされている場合は空です)。 */
data class PastTurn(val turn: Turn, val places: List<String> = emptyList())

/**
 * 会話カードの前後の並べ方（純粋ロジック）：会話に収録されているラウンド（会話ページ[com.wangzan101.mika.chat.ConversationStore]と同じ）が最初、
 * まだ録音していない場合は、この会話の現時点でのタスクが続きます。アプリが使用されたラウンドでは、一行と答えだけが残され、手順、写真、ソースは残されません。
 * まず、最も近い [PAGE] ホイールのみを配置し、一番上に転がしてから、[PAGE] ホイールを前方に漕ぎます。
 */
object CapsuleThreads {
    const val PAGE = 10

    /**
     * [past] は、このダイアログで記録されたすべてのラウンドであり、最後の [shown] ラウンドをランク​​付けします。 [live] はまだ記録されていないタスクです ([liveTail] を参照)。
     * [livePlaces] は、運営しているアプリと Web サイトを提供します。 [summarizing] は、停止後も書き込まれているいくつかの実行 (実行番号) です。
     */
    fun build(
        past: List<PastTurn>, shown: Int, live: List<CardRun>, summarizing: Set<String> = emptySet(),
        livePlaces: (CardRun) -> List<String> = { emptyList() },
    ): ChatThread {
        val from = (past.size - shown).coerceAtLeast(0)
        val items = (from until past.size).flatMap { i -> pastItems(i, past[i], summarizing) } +
            live.flatMap { liveItems(it, livePlaces(it), summarizing) }
        val title = past.firstOrNull()?.turn?.userText ?: live.firstOrNull()?.run?.goal
        return ChatThread(items, hasEarlier = from > 0, title = title?.takeIf(String::isNotBlank))
    }

    /**
     * カード内の会話 ([conversationId]) は、会話のタスクとしてまだ記録されていません。記録されたラウンドには同じ実行レコード番号 ([recorded]) があり、記録されたものを使用します。
     * 録画が開始される前に終了した場合（キュー内でキャンセルされた場合）、録画されたもののみが表示されます。完了したものが最初に表示され (最初に終了したものが先頭に表示されます)、進行中のものが最後に表示されます。
     */
    fun liveTail(runs: List<CardRun>, conversationId: String, recorded: Set<String>): List<CardRun> {
        val mine = runs.filter { r ->
            r.run.conversationId == conversationId && (r.run.runId?.let { it !in recorded } ?: r.kind.active)
        }
        val (active, ended) = mine.partition { it.kind.active }
        return ended.reversed() + active
    }

    /** 一連のメモ: あなたが言ったこと（および中断した内容）、アプリを操作したときのセリフ、答えや操作ができなかった理由。停止されたものは停止後に追加された説明です。 */
    fun pastItems(index: Int, t: PastTurn, summarizing: Set<String> = emptySet()): List<ThreadItem> {
        val turn = t.turn
        val base = turn.runId?.let { "r$it" } ?: "p$index"
        val kind = when (turn.status) {
            "succeeded", "unverified" -> CapsuleKind.Done
            "cancelled" -> CapsuleKind.Cancelled
            else -> CapsuleKind.Failed
        }
        val answer = when (kind) {
            CapsuleKind.Done -> answerParts(turn.outcome).first
            CapsuleKind.Failed -> turn.outcome
            else -> Conversation.stopSummary(turn).orEmpty()
        }
        // 元の単語の後に挿入された単語が続きます。間投詞の前に完了し、間投詞によって中断された返信は前にランクされます (会話ページと同じ)
        val users = listOfNotNull(turn.userText.takeIf(String::isNotBlank)?.let { ThreadItem.User("$base-u0", it) }) +
            turn.steers.withIndex().filter { it.value.isNotBlank() }.flatMap { (i, text) ->
                listOfNotNull(
                    turn.finishedReplies.getOrNull(i)?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-f${i + 1}", it) },
                    turn.interruptedReplies.getOrNull(i)?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-c${i + 1}", it, cut = true) },
                    ThreadItem.User("$base-u${i + 1}", text),
                )
            }
        return users + ended(base, kind, t.places, answer, pending = kind == CapsuleKind.Cancelled && turn.runId in summarizing)
    }

    /** 会話の中でまだ書き留められていないことが 1 つあります。会話が進むにつれて答えを書き留め、あなたが答えて質問に同意するのを待って、書き留めたとおりに終了します。 */
    fun liveItems(r: CardRun, places: List<String>, summarizing: Set<String> = emptySet()): List<ThreadItem> {
        val run = r.run
        val base = run.runId?.let { "r$it" } ?: "l${run.key}"
        // 元の単語と、その後にある単語 (宛先は会話ページと同じステートメント)。間投詞の前に完了し、間投詞によって中断された応答は、間投詞の前にランクされます。
        // まだモデルに渡されていない介入は、書かれている回答の後ろでキューに入れられます (会話ページと同じ、steersAroundDraft を参照)
        fun steer(n: SteerNote) = listOfNotNull(
            n.finished?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-f${n.id}", it) },
            n.interrupted?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-c${n.id}", it, cut = true) },
            ThreadItem.User("$base-s${n.id}", n.text, steerLabel(n.state)),
        )
        val (joined, waiting) = steersAroundDraft(run.steers)
        val user = listOfNotNull(run.goal.takeIf(String::isNotBlank)?.let { ThreadItem.User("$base-u0", it) }) + joined.flatMap(::steer)
        val rest = when (val s = run.state) {
            is TaskState.Succeeded -> ended(base, CapsuleKind.Done, places, s.answer)
            is TaskState.Failed -> ended(base, CapsuleKind.Failed, places, s.reason)
            TaskState.Cancelled -> ended(base, CapsuleKind.Cancelled, places, "", pending = run.runId in summarizing)
            else -> {
                val note = if (places.isNotEmpty()) ThreadItem.Note("$base-n", appLine(places, r.kind), r.kind) else null
                val answer = when (val c = CapsuleCards.content(r)) {
                    is CardContent.Question -> ThreadItem.Answer("$base-a", c.prompt)
                    is CardContent.Confirm -> ThreadItem.Answer("$base-a", c.title + "\n" + c.body)
                    is CardContent.Takeover -> ThreadItem.Answer("$base-a", listOf(c.need, c.body).filter(String::isNotBlank).joinToString("\n"))
                    is CardContent.Held -> ThreadItem.Note("$base-a", c.why, r.kind)
                    else -> run.exchange?.draft?.takeIf(String::isNotBlank)
                        ?.let { ThreadItem.Answer("$base-a", it, streaming = true) }
                        // まだ言葉を書いていません: 今何をしているのか教えてください (このセリフはアプリ内ですでに言われています)
                        ?: ThreadItem.Note("$base-a", r.line, r.kind).takeIf { note == null && r.line.isNotBlank() }
                }
                listOfNotNull(note, answer)
            }
        }
        return user + rest + waiting.flatMap(::steer)
    }

    /**
     * 最終ラウンド：アプリ操作時のセリフ（純粋なチャットは未完了・停止時のセリフのみ）、その後回答全文。
     * [pending] 停止後の説明がまだ書かれており、その行の下に「これまでの内容を整理して…」とあります。
     */
    private fun ended(base: String, kind: CapsuleKind, places: List<String>, answer: String, pending: Boolean = false): List<ThreadItem> {
        val line = when {
            places.isNotEmpty() -> appLine(places, kind)
            kind == CapsuleKind.Failed -> CapsuleCards.status(kind)
            kind == CapsuleKind.Cancelled -> CapsuleCards.status(kind)
            else -> null
        }
        val text = answer.trim()
        return listOfNotNull(
            line?.let { ThreadItem.Note("$base-n", it, kind) },
            if (pending && text.isEmpty()) ThreadItem.Note("$base-s", StatusCopy.STOP_SUMMARIZING, kind) else null,
            text.takeIf(String::isNotEmpty)?.let { ThreadItem.Answer("$base-a", it) },
        )
    }

    /** アプリが操作されたラウンドの収穫の行: "✓ WeChat、淘宝 · 完了" "12306 · 未完了";最大 2 つの場所をリストできます。 */
    fun appLine(places: List<String>, kind: CapsuleKind): String {
        val where = places.take(2).joinToString(tr(R.string.list_sep)) + if (places.size > 2) tr(R.string.and_more) else ""
        val mark = if (kind == CapsuleKind.Done) "✓ " else ""
        return "$mark$where · ${CapsuleCards.status(kind)}"
    }

    /** 今回のラウンドで実際に運用したアプリやWebサイト（活動の対象はパッケージ名または「Webサイトのドメイン名」）から、重複したものを順番に削除します。パッケージ名はアプリ名に置き換えられます。 */
    fun places(acts: List<RunEvent.Activity>, appLabel: (String) -> String): List<String> =
        acts.mapNotNull { a ->
            val t = a.target?.trim().orEmpty()
            val sitePrefix = SITE_PREFIXES.firstOrNull { t.startsWith(it) }
            when {
                sitePrefix != null -> t.removePrefix(sitePrefix).trim().ifEmpty { null }
                PACKAGE_NAME_RE.matches(t) -> appLabel(t)
                else -> null
            }
        }.distinct()

    /** 日本語版のサイト記録と、旧バージョンの中国語の接頭辞を読み取る。 */
    private val SITE_PREFIXES = listOf("ウェブサイト ", "网站 ")
}
