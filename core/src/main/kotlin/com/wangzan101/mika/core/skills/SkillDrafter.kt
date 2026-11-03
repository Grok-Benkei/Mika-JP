package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.verify.AnswerSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.net.UrlHost

/**
 * 製図スキルのすべての入力: [events] はこの実行のイベント、[toolNames] はミカの現在のツール名、
 * [tools] 名前による検索ツールによって実行されるアクション。
 */
/** [answer] はユーザーに与えられる最終回答であり、[sources] はコードによって見つかった回答ソースです ([AnswerSource] を参照)。 */
data class DraftInput(
    val runId: String, val goal: String, val answer: String, val sources: List<AnswerSource>, val events: List<RunEvent>,
    val toolNames: Set<String>, val tools: ToolIndex,
)

/** 物事を行うためのアクション (携帯電話、Web ページ、サンドボックス)。画面の読み取り、スキルの確認、フィニッシュなどは歩数としてカウントされません。 */
private val ACTION_VERBS = setOf(Verb.Tap, Verb.Scroll, Verb.Swipe, Verb.TypeText, Verb.ClearText, Verb.Back, Verb.PressKey, Verb.LaunchApp, Verb.ShareText,
    Verb.Navigate, Verb.Upload, Verb.Tabs, Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile)

/** をドラフトする価値があるとき。切り替えモードと評価モードは呼び出し側の裁量に任されます。 */
object Eligibility {
    const val MIN_ACTIONS = 5

    /** null = ドラフト可能。それ以外の場合は、ドラフトしない理由になります。 [done]: この操作は完了しました ([com.wangzan101.mika.core.agent.LearnGate])。 */
    fun check(events: List<RunEvent>, followedPersonalSkill: String?, tools: ToolIndex, done: Boolean): String? {
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        return when {
            ended?.outcome != "succeeded" -> "not_succeeded"
            !done -> "not_done"
            // 今回、ユーザーはミカに自分の希望に従ってスキルを書き留めるように依頼し、新たなドラフトを開始することはありません。
            events.any { it is RunEvent.ToolResult && tools.verb(it.tool) == Verb.SkillSave && it.resultJson.contains("\"ok\":true") } -> "skill_saved"
            // 間違ったアクションはステップとしてカウントされません（結果は切り捨てられる可能性があります。元のテキストを参照してください）
            events.count { it is RunEvent.ToolResult && tools.verb(it.tool) in ACTION_VERBS && !it.resultJson.contains("\"ok\":false") } < MIN_ACTIONS -> "too_few_actions"
            else -> interrupted(events) ?: "followed_personal_skill".takeIf { followedPersonalSkill != null }
        }
    }

    /** このプロセスには、Mika が自分で実行しなかった部分 (テイクオーバーのスキップ、ユーザーによる完了、確認への同意なし) があり、そこから何も学べません。理由を返します。それ以外の場合は null を返します。 */
    fun interrupted(events: List<RunEvent>): String? = when {
        events.any { it is RunEvent.Takeover && it.outcome == "skipped" } -> "takeover_skipped"
        // ユーザーが自分で完了しました: ミカはそのルートを歩いたことはありません
        events.any { it is RunEvent.Takeover && it.outcome == "user_finished" } -> "takeover_user_finished"
        events.any { it is RunEvent.Confirmation && it.decision in DECLINED } -> "confirmation_declined"
        else -> null
    }

    private val DECLINED = setOf("declined", "declined_before")
}

/**
 * 1 行のアクション リスト: ツール、ターゲット要素名、アプリまたは Web サイト ([where]、Web サイトは「Web サイトのホスト名」と記述されます)、有効かどうか、およびエラー コードのみ。
 * [typed]: このステップではテキストを入力します。
 */
data class ActionLine(
    val step: Int, val tool: String, val target: String?, val where: String?, val changed: Boolean?, val error: String?,
    val typed: Boolean = false,
)

/** 実行中のイベントから合理化されたアクション リストを取得します。画面の全テキストは取得されません。 `type_text` のテキストはフェッチされません (`<入力内容>` にレンダリングされます)。 */
object ActionDigest {
    private val ELEMENT = Regex("\"ref\":\"(@e\\d+)\",\"role\":\"[^\"]*\",\"name\":(\"(?:[^\"\\\\]|\\\\.)*\")")
    private val CURRENT_APP = Regex("\"currentApp\":\"([^\"]+)\"")
    private val CURRENT_SITE = Regex("\"currentSite\":\"([^\"]+)\"")
    private val READS = setOf(Verb.Observe, Verb.ListElements)

    fun of(events: List<RunEvent>, tools: ToolIndex, maxLines: Int = 60): List<ActionLine> {
        val args = HashMap<Int, JsonObject>()
        var names = emptyMap<String, String>()
        // 携帯電話にはどのアプリがあり、Web ページにはどのサイトがありますか?今のところonWebはどちら側ですか?
        var app: String? = null
        var site: String? = null
        var onWeb = false
        val out = mutableListOf<ActionLine>()
        for (e in events) {
            when (e) {
                is RunEvent.ModelReply -> e.argsJson?.let(::parse)?.let { args[e.step] = it }
                is RunEvent.Observed -> if (e.kind == ObsKind.Read.wire) when (e.place?.kind) {
                    PlaceKind.Site -> { site = e.place.name; onWeb = true }
                    PlaceKind.Page -> UrlHost.of(e.place.name)?.let { site = it; onWeb = true }
                    PlaceKind.App -> { app = e.place.name; onWeb = false }
                    else -> Unit
                }
                is RunEvent.ToolResult -> {
                    // 画面読み取り結果が不完全な JSON に切り詰められる可能性があります。 ref→name は各要素のテキストから取得され、ターゲットに名前を付けるためにのみ使用されます。
                    val verb = tools.verb(e.tool)
                    if (verb in READS) {
                        names = ELEMENT.findAll(e.resultJson).associate { m -> m.groupValues[1] to unquote(m.groupValues[2]) }
                        CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                    } else {
                        // アクション結果に含まれる画面もどこに報告されますか?
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                            ?: CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                    }
                    if (verb !in ACTION_VERBS || out.size >= maxLines) continue
                    val a = args[e.step]
                    fun arg(k: String) = (a?.get(k) as? JsonPrimitive)?.contentOrNull
                    if (verb == Verb.LaunchApp || verb == Verb.ShareText) arg("package")?.let { app = it; onWeb = false }
                    if (verb == Verb.Navigate) arg("url")?.let(UrlHost::of)?.let { site = it; onWeb = true }
                    val target = when (verb) {
                        // はテキストではなく、共有されたアプリのみを記憶します。個人情報が含まれる可能性があります。
                        Verb.LaunchApp, Verb.ShareText -> arg("package")
                        Verb.PressKey -> arg("key") ?: arg("keys")
                        // サイトのみを記憶し、URL は記憶しません。クエリ文字列に個人情報が含まれる可能性があります。
                        Verb.Navigate -> arg("url")?.let(UrlHost::of)
                        Verb.Tabs -> arg("action")
                        // コマンド、パス、アップロードされたファイルには個人情報が含まれている可能性があります: 覚えていないでください
                        Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile, Verb.Upload -> null
                        else -> arg("ref")?.let { names[it] }?.takeIf { it.isNotBlank() }
                    }
                    val result = parse(e.resultJson)
                    val error = (result?.get("error") as? JsonPrimitive)?.contentOrNull
                    val where = if (onWeb) site?.let { "ウェブサイト $it" } else app
                    out += ActionLine(e.step, e.tool, target, where, e.changed, error, typed = verb == Verb.TypeText)
                }
                else -> Unit
            }
        }
        return out
    }

    /** 画面を読み取るアクションからターゲットが取得されません。 */

    fun render(lines: List<ActionLine>): String = lines.joinToString("\n") { l ->
        buildString {
            append(l.step).append(". ").append(l.tool)
            l.target?.let { append(" 「").append(it).append("」") }
            if (l.typed) append(" 入力 <入力内容>")
            l.where?.let { append(" 場所: ").append(it) }
            when {
                l.error != null -> append(" エラー:").append(l.error)
                l.changed == true -> append(" 操作を反映")
                l.changed == false -> append(" 画面に変化なし")
            }
        }
    }

    private fun parse(s: String): JsonObject? = try {
        Json.parseToJsonElement(s) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun unquote(s: String) = try {
        (Json.parseToJsonElement(s) as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) {
        ""
    }
}

sealed interface DraftResult {
    /**
     * [isUpdateOf]: モデルは既に個人スキルを備えている同種のものとして判断され、その更新としてドラフトされます。
     * [replacesPending]: 確定草案と判断されるのはその草案の番号と同じで、新規作成せずに元の場所に保存して置き換えます。
     */
    data class Draft(val skill: PersonalSkill, val isUpdateOf: String?, val replacesPending: String? = null) : DraftResult
    data class Dropped(val reason: String) : DraftResult
}

/**
 * 1 回のモデル呼び出し (ツールなし) で、完了したタスクがスキルドラフトに書き込まれ、コードによって検証されます。ただし、ユーザーの邪魔をせずに破棄されます。
 * は、ターゲット、最終回答、回答のソース (どのステップがどこで読み取られたか)、およびアクション リストをモデルに送信します。ターゲット内の値と入力テキストは、読み取られた元のテキストを除き、プレースホルダーに置き換えられます。
 * にも既存の個人スキルが記載されています（ドラフトと併せて確認）：今回はどれと同じタイプのものです。モデルは先頭に`updates: <名字>`を書き込みます。コードは、名前が true であることのみをチェックします。
 */
class SkillDrafter(private val model: ChatModel) {
    suspend fun draft(input: DraftInput, existing: PersonalSkills, catalog: GuideCatalog): DraftResult {
        // 今回の値（ターゲット内の引用符内に今回入力した文字列と数字）がプレースホルダーに置き換えられ、次回ターゲットを変更する際にスキルを使用できるようになります。
        // 画面上の答えや言葉はそのまま授受され、中のページやボタン名はまさにメソッドそのものです。
        val literals = SkillRedactor.literals(input.goal, emptyList()) + SkillRedactor.textLiterals(typedTexts(input.events, input.tools))
        val keepDigits = catalogDigits(catalog)
        // 既存のスキル名はそのまま記載しています（変更すると番号が不一致になります）。それらはユーザー自身のスキルであり、今回の価値観ではありません。
        val request = SkillRedactor.scrubNumbers(SkillRedactor.redact(request(input), literals), keepDigits) + "\n" + existingSkills(existing)
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.Mika)), emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DraftResult.Dropped("model_error")
        }
        val redacted = SkillRedactor.redact(stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }), literals)
        // まずはそのまま確認してください、下書きにパスワードやカード番号などが書かれているということは、下書きが迷走しており、番号を書き換えても受け付けられません。
        if (SensitiveFilter.hit(redacted) != null) return DraftResult.Dropped("sensitive")
        // どのコピーを更新しますか?数字を変更する前に取得してください。スキル名の数字は名前の一部です。
        val updates = (FrontMatter.parse(redacted).first["updates"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val md = PersonalSkills.withoutField(SkillRedactor.scrubNumbers(redacted, keepDigits), "updates")

        validate(md, input.toolNames, input.tools.nameOf(Verb.RunBundledScript), catalog.runnableScripts())?.let { return DraftResult.Dropped(it) }
        val meta = FrontMatter.parse(md).first
        val name = meta["name"] as String

        // モデルは、確認されるドラフトを参照します。そのドラフトを置き換えます (既存のスキルの更新である場合、新しいドラフトはそのドラフトを更新します)。
        // は、更新として受け入れられた (使用中、試用中、または非アクティブ化された) ものを指します。名前が一致しない場合（コピーが存在せず、組み込みスキルである場合）、カウントされず、新しいコピーとして扱われます。
        val pending = updates?.let { u -> existing.drafts().entries.firstOrNull { it.value.name == u } }
        val updateOf = if (pending != null) pending.value.replacesDraftOf else updates?.takeIf { existing.get(it) != null }
        // 新しくドラフトされたものは、組み込みスキル、既存の個人スキル、またはドラフトを上書きできません。ドラフトを更新するときは、常に更新されるドラフトの名前を使用します (承認するときに上書きします。ヘッダー名は一致する必要があります)。
        if (pending == null && updateOf == null &&
            (existing.get(name) != null || existing.pending().any { it.name == name } || catalog.items.any { it.name == name }))
            return DraftResult.Dropped("name_taken")
        val finalName = pending?.value?.name ?: updateOf ?: name
        // がどの実行に由来するかはコードで記述されます。リクエスト内の数値はプレースホルダーに置き換えられ、モデルは実際の実行番号をコピーできません。
        val finalMd = PersonalSkills.withField(PersonalSkills.withName(md, finalName), "learned_from", input.runId)

        val skill = PersonalSkill(
            name = finalName, markdown = finalMd, status = SkillStatus.pending, learnedFrom = input.runId, replacesDraftOf = updateOf,
            createdAt = input.events.lastOrNull()?.atMs ?: 0L, outcomes = emptyList(),
            baseHash = updateOf?.let { name -> existing.get(name)?.markdown?.let { PersonalSkills.fingerprint(it) } },
        )
        return DraftResult.Draft(skill, updateOf, pending?.key)
    }

    /** 既存の個人スキル (最初に承認されたもの、最後に確認されるドラフト、名前順に配置)、コピーごとに 1 行: 名前、表示名、説明。 */
    private fun existingSkills(existing: PersonalSkills): String {
        val lines = (existing.list().sortedBy { it.name } + existing.pending().sortedBy { it.name }).map { s ->
            val meta = FrontMatter.parse(s.markdown).first
            "- ${s.name}(${meta["display"] as? String ?: s.name}):${meta["description"] as? String ?: ""}"
        }
        return "保存済みの個人スキル（確認待ちの下書きを含む）:" + if (lines.isEmpty()) "なし" else "\n" + lines.joinToString("\n")
    }


    /** Type_text は今回入力したテキストです (レコード内のパスワード ボックスは *** に置き換えられています)。 */
    private fun typedTexts(events: List<RunEvent>, tools: ToolIndex): List<String> = events.filterIsInstance<RunEvent.ModelReply>()
        .filter { tools.verb(it.tool) == Verb.TypeText }
        .mapNotNull { e ->
            val o = try { Json.parseToJsonElement(e.argsJson ?: return@mapNotNull null) as? JsonObject } catch (x: Exception) { null }
            (o?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it != "***" }
        }

    /** 組み込みスキル名、表示名、およびエイリアスの番号 (鉄道アプリの名前など) はドラフトに保持されます。 */
    private fun catalogDigits(catalog: GuideCatalog): Set<String> =
        catalog.items.filter { it.kind != PERSONAL_KIND }.flatMap { listOf(it.name, it.display) + it.aliases }
            .flatMap { Regex("\\d{2,}").findAll(SkillRedactor.norm(it)).map { m -> m.value }.toList() }.toSet()

    private fun request(input: DraftInput): String = buildString {
        appendLine("実行ID:${input.runId}")
        appendLine("目標:${input.goal}")
        appendLine("最終回答:${input.answer}")
        if (input.sources.isNotEmpty()) {
            appendLine("回答の出典:")
            input.sources.forEach { s -> appendLine("- ステップ ${s.step} ${s.kind} ${s.place.orEmpty()}" + s.title?.let { "「$it」" }.orEmpty()) }
        }
        appendLine("操作一覧:")
        appendLine(ActionDigest.render(ActionDigest.of(input.events, input.tools)))
        append("利用可能なツール:").append(input.toolNames.sorted().joinToString(", "))
    }

    companion object {
        /** モデル応答を囲むコード ブロック フェンスが削除されます。 */
        fun stripFence(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.substringAfter('\n').removeSuffix("```").trim()
        }

        const val MAX_CHARS = 4000

        /**
         * スキルドキュメントの形式とセキュリティ検証 (作成された草案とユーザーによって改訂され承認された全文はすべてこのレベルに合格します)。 null を返す場合は、それ以外の場合は理由を返します。
         * には正式な名前、説明、5 つの完全なセクションがあり、文字数は [MAX_CHARS] 以下で、バックティック内のツール名はすべて [toolNames] であり、機密フィルタリングはヒットしません。
         * スキル スクリプトを実行するツール ([scriptTool]) について言及する場合、記事内に少なくとも 1 つのスクリプト パスが必要であり、そのすべてが [runnableScripts] (リストで実行可能としてマークされている) に存在する必要があります。
         */
        fun validate(md: String, toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String> = emptySet()): String? {
            val (meta, body) = FrontMatter.parse(md)
            val name = meta["name"] as? String
            if (name == null || !PersonalSkills.NAME.matches(name)) return "invalid_name"
            if ((meta["description"] as? String).isNullOrBlank()) return "missing_description"
            val headings = body.lines().filter { it.startsWith("## ") }
            fun hasSections(sections: List<String>) = sections.all { s -> headings.any { it.removePrefix("## ").trim().startsWith(s) } }
            if (!hasSections(SECTIONS) && !hasSections(LEGACY_SECTIONS)) return "missing_section"
            if (md.length > MAX_CHARS) return "too_long"
            if (TOOL_MENTION.findAll(md).any { it.groupValues[1] !in toolNames }) return "unknown_tool"
            if (scriptTool != null && TOOL_MENTION.findAll(md).any { it.groupValues[1] == scriptTool }) {
                val scripts = SCRIPT_PATH.findAll(md).map { it.value }.toList()
                fun runnable(path: String) = runnableScripts.any { it == path || it.endsWith("/$path") }
                if (scripts.isEmpty() || !scripts.all(::runnable)) return "unrunnable_script"
            }
            if (SensitiveFilter.hit(md) != null) return "sensitive"
            return null
        }
        val SECTIONS = listOf("開始条件", "手順", "注意点", "完了の確認", "ユーザーに引き継ぐ条件")
        private val LEGACY_SECTIONS = listOf("入口", "步骤", "容易出错的地方", "怎样算办成", "什么时候停下交给用户")
        /** バックティックで囲まれたツール名の形式の識別子。 */
        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /** 記事内のスキル スクリプト パス (`scripts/extract_pdf.py` や `skills/documents/scripts/extract_pdf.py` など)。 */
        private val SCRIPT_PATH = Regex("[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\\.(?:py|mjs|js|sh)(?![A-Za-z0-9_])")

        val PROMPT: String by lazy { Prompts.role("skill_draft") }
    }
}
