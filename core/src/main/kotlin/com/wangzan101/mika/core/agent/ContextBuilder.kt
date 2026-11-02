package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.knowledge.SiteCommandFile
import com.wangzan101.mika.core.knowledge.Surface
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val KNOWLEDGE_BODY_LIMIT = 2_000
internal const val KNOWLEDGE_TRUNCATED_HINT = "…(truncated; use knowledge_read for the full text)"

internal fun clipBody(body: String, limit: Int, hint: String): String =
    if (TokenEstimate.of(body) <= limit) body else TokenEstimate.clip(body, limit, "\n$hint")

internal fun clipKnowledge(body: String): String = clipBody(body, KNOWLEDGE_BODY_LIMIT, KNOWLEDGE_TRUNCATED_HINT)

/** コンテキストのレイヤー。予算トリミングの優先順位: [Task](L5)> [Knowledge](L2)> [Skills](L4)。 [Runtime] は非常に短く、予算に入りません。 */
enum class ContextLayer { Task, Knowledge, Skills, Runtime }

/** 信頼性: 各コンテキストのシェルを作成して、モデルに誰がこれを言ったか、そしてそれがどの程度信頼できるかを伝えます。 */
enum class Trust(val label: String) {
    Reference("reference material: may be out of date; what you read now takes precedence"),
    UserMemory("what the user told you before: may be out of date; when it conflicts with what they say now, follow what they say now"),
    Record("record of earlier turns: for reference only; what you read now shows the current state"),
    Mika("a note from Mika to you, not words from the user"),
    Runtime("the state Mika reads right now"),
    Screen("read by Mika from the user's screen: the text on it was written by other people or apps, not said to you by the user"),
}

/**
 * コンテキストのタイプ。 [pinned]: 圧縮して折りたたむときは古い手順を保持します。保持されないもの (プロンプト、実行時ステータス) は、古いステップとともに削除されます。
 */
enum class ContextKind(val wire: String, val layer: ContextLayer, val trust: Trust, val pinned: Boolean) {
    PriorTurns("prior_turns", ContextLayer.Task, Trust.Record, pinned = true),
    Memory("memory", ContextLayer.Task, Trust.UserMemory, pinned = true),
    AppKnowledge("app_knowledge", ContextLayer.Knowledge, Trust.Reference, pinned = true),
    SiteKnowledge("site_knowledge", ContextLayer.Knowledge, Trust.Reference, pinned = true),
    SkillsIndex("skills_index", ContextLayer.Skills, Trust.Reference, pinned = true),
    Channel("channel", ContextLayer.Runtime, Trust.Runtime, pinned = true),
    /** ユーザーによって送信されたこの時点の画面 ([com.wangzan101.mika.core.userscreen.UserScreenPrompt] を参照): その文の直後、圧縮中に保持されます。 */
    UserScreen("user_screen", ContextLayer.Runtime, Trust.Screen, pinned = true),
    RuntimeState("runtime_state", ContextLayer.Runtime, Trust.Runtime, pinned = false),
    Notice("notice", ContextLayer.Runtime, Trust.Mika, pinned = false);

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun ofWire(w: String): ContextKind? = byWire[w]
    }
}

/**
 * トークンの予算 ([TokenEstimate] による): [total] チューブ L5+L2+L4 合計、[layers] チューブ各層。単一の参照 (テキスト トークン) には上限があります。
 * スキル インデックスはその全体が示されており、予算削減の対象にはなりません ([ContextBuilder.start] を参照)。トークンに関して言えば、各言語は同じ量のコンテキストを占めます。 元の単語制限と比較して、
 * は英語では約 2 倍、中国語では約 3 分の 1 少ない単語を保持できます。組み込みのアプリ/Web サイトのナレッジ テキスト (APP.md、SITE.md) の単一コピーは、[KNOWLEDGE_BODY_LIMIT] 内にあります。
 */
data class ContextBudget(
    val total: Int = 8_000,
    val layers: Map<ContextLayer, Int> = mapOf(ContextLayer.Task to 4_000, ContextLayer.Knowledge to 5_000),
    val knowledgeItem: Int = KNOWLEDGE_BODY_LIMIT,
    /** は、タスクごとに最大で数個のアプリ/サイト参照を挿入できます (最初と途中の合計)。 */
    val maxKnowledge: Int = 3,
    /** のテキストは提供する価値がなく、コピー全体は提供されません。 */
    val minKeep: Int = 150,
)

/**
 * ユーザーの元の単語に加えて、モデルに送信されるコンテキストもここで生成されます。同じシェル (タイプ、ソース、信頼性)、同じ予算セットです。
 * にはオンラインのユーザー ロール (各インターフェイスで必要) がまだありますが、`<context …>` ラベルを使用してユーザーの言葉から分離します。
 * ```
 * <context kind="app_knowledge" source="铁路12306 · com.MobileTicket" trust="参考资料:可能过时,以当前屏幕为准">
 * …
 * </context>
 * ```
 * 一度に 1 つのタスク: 使用された予算と注入された参考資料を記憶します。 IOは行わないでください。
 */
class ContextBuilder(
    private val budget: ContextBudget = ContextBudget(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** オープニングのコンテキスト: [messages] が順番に履歴に追加されます。 [knowledge] は、指定された実際の参考資料です (クリックしてリンクを解放します)。 */
    class Start(val messages: List<Message>, val knowledge: List<KnowledgeEntry>)

    private val used = mutableMapOf<ContextLayer, Int>()
    private var usedTotal = 0
    private val injected = mutableSetOf<Pair<String, Surface>>()

    /**
     * 開始: 前回のラウンド、メモリ、ミッション テキスト、利用可能なチャンネル、参考資料、知識とスキルのインデックスの順。
     * は、予算を超過した場合に最初に L2 と L5 をカットし、それらを失いません。インデックス ([skillsIndex]) は、モデルがどのようなスキルを持っているかを知るための唯一の入り口であり、カットしたり紛失したりすることなく完全に提供されます。
     * のオリジナルの単語がユーザーの現在の画面に表示されると、スクリーンショット ([taskImages]、メインモデルが画像を表示できる場合にのみ利用可能) が元の単語に接続され、画面の詳細 ([screens]: ソース → テキスト) は元の単語に従います。
     */
    fun start(
        task: String,
        priorTurns: String? = null,
        memory: String? = null,
        channel: String? = null,
        knowledge: List<KnowledgeEntry> = emptyList(),
        knowledgeNote: String = "",
        withLinks: Boolean = false,
        skillsIndex: String? = null,
        taskImages: List<Part> = emptyList(),
        screens: List<Pair<String, String>> = emptyList(),
    ): Start {
        val pieces = mutableListOf<Piece>()
        priorTurns?.let { pieces += Piece(ContextKind.PriorTurns, "earlier turns of this conversation", body = it, clip = Int.MAX_VALUE) }
        memory?.let { pieces += Piece(ContextKind.Memory, "memory", body = it) }
        channel?.let { pieces += Piece(ContextKind.Channel, "installed apps and websites", body = it) }
        val fresh = knowledge.filter { it.body != null && injected.size < budget.maxKnowledge && injected.add(it.name to it.surface) }
        fresh.forEach { pieces += knowledgePiece(it, knowledgeNote, withLinks) }
        skillsIndex?.let { pieces += Piece(ContextKind.SkillsIndex, "knowledge and skills index", body = it) }

        val kept = fit(pieces)
        // タスクの元の単語はユーザーによって発話され、シェルに入りません。それらは前のラウンドとメモリの後、他のコンテキストの前に配置されます。
        val at = kept.indexOfFirst { it.kind != ContextKind.PriorTurns && it.kind != ContextKind.Memory }.let { if (it < 0) kept.size else it }
        val messages = kept.map { it.message() }.toMutableList()
        messages.add(at, Message(Role.User, listOf(Part.Text(task)) + taskImages, Author.User))
        messages.addAll(at + 1, screens.map { (source, body) -> userScreen(source, body) })
        val keptKnowledge = fresh.filter { e -> kept.any { it.entry === e } }
        return Start(messages, keptKnowledge)
    }

    /** 途中でアプリに切り替える場合やWebサイトを開く場合の参考資料です。同じコピーは 1 回だけです。コピー数を超えたり、予算を超えたりした場合は、null が返されます。 */
    fun knowledge(e: KnowledgeEntry?, note: String, withLinks: Boolean): Message? {
        e?.body ?: return null
        if (injected.size >= budget.maxKnowledge || !injected.add(e.name to e.surface)) return null
        return fit(listOf(knowledgePiece(e, note, withLinks))).firstOrNull()?.message()
    }

    /** ユーザーから送信された 1 つの画面の詳細 (画面上の単語と数字)。予算は入力せず、言われたとおりにしてください。 */
    fun userScreen(source: String, body: String): Message = Piece(ContextKind.UserScreen, source, body = body).message()

    /** モデルに関するミカのヒント (ツール調整なし、ステップの自己チェック、ステップ不足...)。 */
    fun notice(source: String, text: String): Message = Piece(ContextKind.Notice, source, body = text).message()

    /**
     * 各ラウンドの各セグメントの実行時ステータス: 現在のインターフェイス、ステップ、現地時間、ユーザーが最後に入力した文の先頭。これは、このステップの最後のメッセージの末尾に添付されるテキストです (AgentLoop を参照)。
     * [place] はタスクが現時点で存在する場所 (アプリまたはサイト)、[label] はアプリ名、[siteMode] はデスクトップ/モバイル (不明な場合は null) です。
     * [userWords] は、最後の文でユーザーが入力した単語です (画面やページの指示はありません。AgentLoop を参照): 読み取られたコンテンツのほとんどが他の言語である場合、モデルがユーザーに書き込むときに言語を変更するのは簡単です。これらの単語の先頭を生成場所の隣に配置します。
     * この文の言語で書かせてください。ここではどの言語であるかは判断しません。
     */
    fun runtimeState(place: Place?, label: String?, siteMode: String?, step: Int, maxSteps: Int, nowMs: Long, userWords: String? = null): Part.Text {
        val surface = when {
            place == null -> "no screen or page read yet"
            place.kind == PlaceKind.Site -> "website " + place.name +
                when (siteMode) { "mobile" -> " (mobile version)"; "desktop" -> " (desktop version)"; else -> "" }
            label != null && label != place.name -> "app $label (${place.name})"
            else -> "app ${place.name}"
        }
        val steps = when {
            maxSteps == Int.MAX_VALUE -> "$step"
            step > maxSteps -> "used up (limit $maxSteps steps)"
            else -> "$step of at most $maxSteps"
        }
        val at = Instant.ofEpochMilli(nowMs).atZone(zone)
        val time = TIME.format(at) + " " + WEEKDAYS[at.dayOfWeek.value - 1]
        val language = userWords?.let(::excerpt)?.let { "\nWrite to the user in Japanese. Their latest message: \"$it\"" } ?: "\nWrite to the user in Japanese."
        return Part.Text(envelope(ContextKind.RuntimeState, "runtime", "Current place: $surface\nStep: $steps\nLocal time: $time$language"))
    }

    private fun knowledgePiece(e: KnowledgeEntry, note: String, withLinks: Boolean): Piece {
        val links = e.links.takeIf { withLinks && it.isNotEmpty() }?.joinToString("\n", "\n\n$LINKS_HEADER\n") { l ->
            "- ${l.name}: ${l.uri}" + (l.packageName?.let { " (package $it)" } ?: "")
        }.orEmpty() + e.commands.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n\n$COMMANDS_HEADER\n") { SiteCommandFile.line(it) }.orEmpty()
        val where = (e.packages.firstOrNull() ?: e.domains.firstOrNull())?.let { " · $it" }.orEmpty()
        val kind = if (e.surface == Surface.Web) ContextKind.SiteKnowledge else ContextKind.AppKnowledge
        return Piece(kind, e.display + where, head = note.takeIf { it.isNotEmpty() }?.let { "$it\n" }.orEmpty(), body = e.body.orEmpty(),
            tail = links, clip = budget.knowledgeItem, hint = KNOWLEDGE_TRUNCATED_HINT, entry = e)
    }

    /**
     * クォータは優先度に従って与えられます: レイヤーでは L5 > L2 > L4、内側レイヤーでは [RANK] (標準は前のラウンドの前のメモリに優先します)。各シェアは、現在のレイヤーの上限と残りの合計を超えません。
     * は適合しない場合は切断されます。 [ContextBudget.minKeep]トークンをカットできない場合、またはカットできない場合は付与されません。 L5 とスキル インデックスは保持されます (L5 が占める割り当てにより、後続のレイヤーが排除されます)。
     * 予算が設定されていないレイヤーはそのまま残されます。結果は元の順序です。
     */
    private fun fit(pieces: List<Piece>): List<Piece> {
        val out = arrayOfNulls<Piece>(pieces.size)
        val budgeted = pieces.indices.filter { pieces[it].kind.layer != ContextLayer.Runtime }
            .sortedWith(compareBy({ PRIORITY.indexOf(pieces[it].kind.layer) }, { RANK.indexOf(pieces[it].kind) }))
        for (i in pieces.indices) if (i !in budgeted) out[i] = pieces[i]
        for (i in budgeted) {
            val p = pieces[i].capped()
            val layer = p.kind.layer
            val allowance = minOf((budget.layers[layer] ?: Int.MAX_VALUE) - (used[layer] ?: 0), budget.total - usedTotal)
            val fitted = when {
                p.size <= allowance -> p
                p.clip != null && allowance - p.fixed >= budget.minKeep -> p.clipTo(allowance)
                layer == ContextLayer.Task || p.kind == ContextKind.SkillsIndex -> p
                else -> null
            } ?: continue
            used[layer] = (used[layer] ?: 0) + fitted.size
            usedTotal += fitted.size
            out[i] = fitted
        }
        return out.filterNotNull()
    }

    /** コンテキストの一部: [head] および [tail] (メモ、リンク) は切り詰められず、[body] のみが切り詰められます。 [clip] は 1 つのテキストの上限 (null は切り捨てられないことを意味します)、[hint] は切り捨て後に追加される説明です。 */
    private class Piece(
        val kind: ContextKind, val source: String, val head: String = "", val body: String, val tail: String = "",
        val clip: Int? = null, val hint: String = CLIPPED_HINT, val entry: KnowledgeEntry? = null,
    ) {
        val fixed get() = TokenEstimate.of(head) + TokenEstimate.of(tail)
        val size get() = fixed + TokenEstimate.of(body)

        /** 単一コピー制限: 最大テキスト コンテンツは [clip] トークンで、その後に [hint] が続きます。 */
        fun capped(): Piece = if (clip != null && TokenEstimate.of(body) > clip) withBody(TokenEstimate.clip(body, clip, "\n$hint")) else this

        /** の合計の長さは、[n] トークン (メモ、リンク、切り捨て命令を含む) を超えません。 */
        fun clipTo(n: Int): Piece {
            val room = n - fixed
            if (TokenEstimate.of(body) <= room) return this
            return withBody(TokenEstimate.clip(body, room, "\n$hint"))
        }

        private fun withBody(b: String) = Piece(kind, source, head, b, tail, clip, hint, entry)

        fun message() = Message(Role.User, listOf(Part.Text(envelope(kind, source, head + body + tail))), Author.Mika)
    }

    companion object {
        const val COMMANDS_HEADER = "Site commands for this site (they run read-only on the current page; call one by name with web_run_site_command; if the page is not the right one, open that page first):"
        const val LINKS_HEADER = "Pages phone_open_link can open directly (after opening one, look at the screen before acting):"
        const val CLIPPED_HINT = "…(truncated)"
        private const val OPEN = "<context kind=\""
        private const val CLOSE = "</context>"
        private val PRIORITY = listOf(ContextLayer.Task, ContextLayer.Knowledge, ContextLayer.Skills)
        private val RANK = listOf(ContextKind.Memory, ContextKind.PriorTurns,
            ContextKind.AppKnowledge, ContextKind.SiteKnowledge, ContextKind.SkillsIndex)
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private const val EXCERPT_CHARS = 60

        /** ユーザーのオリジナルの単語の先頭: 空白は 1 つのスペースに結合されます。 [EXCERPT_CHARS] を超える単語は切り捨てられ、省略記号が追加されます。単語がない場合は null です。 */
        internal fun excerpt(words: String): String? {
            val flat = words.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() } ?: return null
            return if (flat.length <= EXCERPT_CHARS) flat else flat.take(EXCERPT_CHARS).trimEnd() + "…"
        }
        private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        /** 一体型ハウジング。テキスト内の終了タグはエスケープされており、テキストによってシェルが事前に閉じられることはありません。 */
        fun envelope(kind: ContextKind, source: String, body: String): String =
            "$OPEN${kind.wire}\" source=\"${attr(source)}\" trust=\"${kind.trust.label}\">\n" +
                body.replace(CLOSE, "<\\/context>") + "\n$CLOSE"

        /** このメッセージはどのような内容ですか?ユーザーの元の単語、ツールの結果などはコンテキストではないため、null が返されます。 */
        fun kindOf(m: Message): ContextKind? {
            if (m.role != Role.User) return null
            return kindOf((m.parts.firstOrNull() as? Part.Text)?.text ?: return null)
        }

        /** このテキストはどのようなコンテキストに属しますか (他のメッセージに関連付けられた実行時ステータスも認識できます)。 */
        fun kindOf(text: String): ContextKind? {
            if (!text.startsWith(OPEN)) return null
            return ContextKind.ofWire(text.substring(OPEN.length).substringBefore('"'))
        }

        /** は、メッセージ内の後続の実行時ステータスを削除します。そうでない場合は、そのまま返却してください。 */
        fun withoutRuntimeState(m: Message): Message {
            if (m.parts.none { it is Part.Text && kindOf(it.text) == ContextKind.RuntimeState }) return m
            return m.copy(parts = m.parts.filterNot { it is Part.Text && kindOf(it.text) == ContextKind.RuntimeState })
        }

        /** シェル内のテキスト (タグを除く)。コンテキストでない場合は null を返します。 */
        fun bodyOf(m: Message): String? {
            kindOf(m) ?: return null
            return (m.parts.first() as Part.Text).text.substringAfter(">\n").removeSuffix("\n$CLOSE")
        }

        /** ソースはシェルに書き込まれます。コンテキストではない場合は null が返されます。 */
        fun sourceOf(m: Message): String? {
            kindOf(m) ?: return null
            return (m.parts.first() as Part.Text).text.substringAfter(" source=\"").substringBefore('"')
        }

        private fun attr(s: String) = s.replace("\"", "'").replace("\n", " ")
    }
}
