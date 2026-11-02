package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.observation.ObsForm
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/**
 * 圧縮時に古いコンテンツを削除します。古いインターフェイスはもはや現状ではなく、古い大きな結果は位置ごとに再読み込みでき、それらを残すことはコンテキストを拡張するだけです。
 * は、[ContextCompressor] が圧縮されている場合にのみ呼び出されます (通常送信されるメッセージはそのまま送信されます)。コピーのみが返され、呼び出し元によって保存された完全な履歴は変更されません。
 * 呼び出しは結果の ID とペアになり、他のメッセージはそのまま残ります。
 *
 * 切断線 ([Cut]) を押します。線の後の往復はそのままで、線の前の線は結果で観察されるように短縮されます ([ObsForm]、[BY_FORM] を参照)。
 * インターフェイスのステータス (読み取りインターフェイス、アクションによって戻されたインターフェイス、レビュー用の要素テキスト) はすべて [OMITTED] に置き換えられます。ページのコンテンツ、コマンド出力、ファイル、および応答は、結果が大きい場合にのみ使用されます。
 * ([BIG_RESULT_TOKENS] トークンを超える) の場合、長いフィールドを [OMITTED_READ] に置き換えます。ユーザーの発言（記憶）や参考資料はカットされません。観察しなくても結果は行動によって認識されます。
 * 短縮された結果には、終了 (OK、エラー、理由) と、証拠台帳を参照する観察番号 ([OBS_IDS]) が保持されます。行が切断される前の最新の完全なインターフェイス。その後のアクションと違いは、それを通じて理解する必要があります。
 * 行の前の画像はプレースホルダーに置き換えられます (インターフェース画面は戻ることができる [olderImage]、読み取られた画像ファイルは [OMITTED_IMAGE])。ユーザーの言葉や写真は切り取られません。
 *
 * サービスプロバイダーには、撮影時間の明確な上限があります ([limitImages])。制限を超える場合、プレースホルダーは最も古いアシスタント画像から置き換えられます。
 */
object HistoryPruner {
    const val OMITTED = "(screen read earlier, omitted; read it again if needed)"
    const val OMITTED_IMAGE = "(old image omitted)"

    /** は、以前のインターフェイス画面のプレースホルダーです。 */
    fun olderImage(imageId: Int) = "(earlier screen image omitted; to see it again use view_past_screens with ids [$imageId])"
    const val OMITTED_READ = "(content read earlier, omitted; read it again with nextOffset, path and so on if needed)"
    /** は、短縮結果内の証拠台帳の観察番号を参照します。 */
    const val OBS_IDS = "obsIds"
    /**
     * この多くのトークンを超えるツールの結果は、「大きな結果」とみなされます。ツールの結果はほとんどが英語と JSON (トークンごとに約 4 文字) です。
     * は、元の 4000 文字行に対応するトークンの数を受け取り、英語の結果は変わりません。中国語の結果は実際の職業に基づいて計算され、元の結果よりも早く計算されます。
     */
    const val BIG_RESULT_TOKENS = 1_000

    /** 線を短くする前にさまざまな観察。 */
    private enum class Shrink { Interface, IfBig, Never }

    /** 短縮方法は観察された形式に従って決定されます。インターフェイスのステータスはすべて短縮され、ページ、コマンド出力、ファイル、応答、およびファクトはサイズが大きい場合にのみ短縮され​​、ユーザーの指示に従って短縮されません。 */
    private val BY_FORM = mapOf(
        ObsForm.Interface to Shrink.Interface,
        ObsForm.Page to Shrink.IfBig, ObsForm.Command to Shrink.IfBig, ObsForm.File to Shrink.IfBig,
        ObsForm.Reply to Shrink.IfBig, ObsForm.Fact to Shrink.IfBig, ObsForm.Image to Shrink.IfBig,
        ObsForm.UserSaid to Shrink.Never,
    )

    /** モデルの参考資料: サイズに合わせてカットされていません。 */
    private val REFERENCE_VERBS = setOf(Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.MemorySearch)
    /** 観察結果はありません。インターフェイスの読み取りアクションは、インターフェイスのステータスに応じて依然として短縮されています。 */
    private val INTERFACE_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Diagnose)
    private const val LONG_TEXT = 300
    /** 全量の UI 読み取り（振り返り結果の中の要素テキストも同様に古くなる; 振り返り自体は読み取り1回とは数えない）。 */
    private val FULL_VERBS = setOf(Verb.Observe, Verb.ListElements)
    private const val SCREEN = "screen"
    /** 短縮するときも必ず残す結末フィールド。 */
    private val OUTCOME = setOf("ok", "error", "message", "recoverable", "exitCode")

    /** 切り取りの線: 直近 [window] 回のツール往復はそのまま、それより前は短縮する。 */
    data class Cut(val window: Int) {
        init { require(window >= 1) { "need window >= 1" } }

        /** 合計 [rounds] 回の往復があるとき、線より前に何回あるか。 */
        fun before(rounds: Int): Int = (rounds - window).coerceAtLeast(0)
    }

    /** を超えています。最新の往復のみがそのまま残ります。 */
    val LAST = Cut(1)

    /** [tools]: 呼び出されたツール名によって実行されるアクションを確認します。 */
    fun prune(input: List<Message>, tools: ToolIndex, cut: Cut): List<Message> {
        val starts = input.indices.filter { i -> input[i].role == Role.Assistant && input[i].parts.any { it is Part.ToolCall } }
        val k = cut.before(starts.size)
        if (k == 0) return input
        val line = starts[k]
        val names = callVerbs(input, tools)
        val screens = screenResults(input)
        // ライン以前の最新の完全なインターフェイス
        val anchor = (0 until line).lastOrNull { i ->
            input[i].parts.any { it is Part.ToolResult && (names[it.callId] in FULL_VERBS || screens[it.callId] == true) }
        }
        // ユーザーの言葉 (写真とともに) は切り取られません
        return input.mapIndexed { i, m -> if (i >= line || i == anchor || m.author == Author.User) m else shrinkMessage(m, names, screens) }
    }

    /**
     * を最大まで押しても放せない場合の結論: 最新のものから始めて、ツール結果の長いフィールド (種類を問わず) を [BIG_RESULT_TOKENS] を超えるトークンで [OMITTED_READ] に置き換えます。
     * 終了フィールドと観測番号は、[fits] まで残ります。ユーザーの言葉は動かない。
     */
    fun shrinkOversized(messages: List<Message>, fits: (List<Message>) -> Boolean): List<Message> {
        val out = messages.toMutableList()
        for (i in out.indices.reversed()) {
            if (fits(out)) break
            val m = out[i]
            if (m.author == Author.User || m.parts.none { it is Part.ToolResult && TokenEstimate.of(it.content) > BIG_RESULT_TOKENS }) continue
            out[i] = m.copy(parts = m.parts.map { p ->
                if (p is Part.ToolResult && TokenEstimate.of(p.content) > BIG_RESULT_TOKENS) withObsIds(p, shrinkRead(p.content)) else p
            })
        }
        return out
    }

    /** [maxImages]: サービスプロバイダーが確認した 1 つのリクエスト画像の上限。この値を超えると、プレースホルダーは最初のアシスタント画面から置き換えられ、ユーザーの元の言葉と添付ファイルはそのまま残ります。 */
    fun limitImages(messages: List<Message>, maxImages: Int): List<Message> {
        require(maxImages >= 0)
        var excess = messages.sumOf { m -> m.parts.count { it is Part.Image } } - maxImages
        if (excess <= 0) return messages
        return messages.map { m ->
            if (m.author == Author.User || excess <= 0) m else {
                val parts = m.parts.map { p ->
                    if (p is Part.Image && excess > 0) {
                        excess--
                        Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                    } else p
                }
                if (parts == m.parts) m else m.copy(parts = parts)
            }
        }
    }

    private fun shrinkMessage(m: Message, names: Map<String, Verb?>, screens: Map<String, Boolean>): Message {
        val parts = m.parts.map { p ->
            when (p) {
                is Part.Image -> Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                is Part.ToolResult -> shrinkResult(p, names[p.callId], screens)
                else -> p
            }
        }
        return if (parts == m.parts) m else m.copy(parts = parts)
    }

    private fun shrinkResult(p: Part.ToolResult, verb: Verb?, screens: Map<String, Boolean>): Part.ToolResult {
        val ways = p.reads.mapNotNull { BY_FORM[it.form] }.toSet()
        val content = when {
            Shrink.Never in ways || verb in REFERENCE_VERBS -> p.content
            p.callId in screens -> shrinkScreen(p.content)
            Shrink.Interface in ways || verb in INTERFACE_VERBS -> shrinkInterface(p.content)
            verb == Verb.ViewPastScreens -> shrinkRecall(p.content)
            TokenEstimate.of(p.content) > BIG_RESULT_TOKENS -> shrinkRead(p.content)
            else -> p.content
        }
        return if (content == p.content) p else withObsIds(p, content)
    }

    /** 短縮された結果は、証拠台帳内の観察を参照します。 */
    private fun withObsIds(p: Part.ToolResult, content: String): Part.ToolResult {
        val ids = p.reads.map { it.obsId }.distinct()
        val withIds = if (ids.isEmpty()) content else parse(content)?.let { o ->
            JsonObject(o + (OBS_IDS to JsonArray(ids.map(::JsonPrimitive)))).toString()
        } ?: content
        return p.copy(content = withIds)
    }

    /** の長いフィールド (長い文字列、大きな配列/オブジェクト) は [OMITTED_READ] に置き換えられ、最後のフィールドは保持されます。 JSON ではない段落全体が置き換えられます。 */
    private fun shrinkRead(content: String): String {
        val obj = parse(content) ?: return OMITTED_READ
        return JsonObject(obj.mapValues { (k, v) ->
            val long = if (v is JsonPrimitive) v.isString && v.content.length > LONG_TEXT else v.toString().length > LONG_TEXT
            if (long && k !in OUTCOME) JsonPrimitive(OMITTED_READ) else v
        }).toString()
    }

    private fun callVerbs(messages: List<Message>, tools: ToolIndex): Map<String, Verb?> {
        val verbs = HashMap<String, Verb?>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { verbs[it.id] = tools.verb(it.name) } }
        return verbs
    }

    /** 画面オブジェクトを使用したツールの結果: callId → 画面がいっぱいです。 */
    private fun screenResults(messages: List<Message>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        messages.forEach { m ->
            m.parts.filterIsInstance<Part.ToolResult>().filter { "\"$SCREEN\":{" in it.content }.forEach { r ->
                (parse(r.content)?.get(SCREEN) as? JsonObject)?.let { out[r.callId] = it["full"] == JsonPrimitive(true) }
            }
        }
        return out
    }

    private fun shrinkRecall(content: String): String {
        val obj = parse(content) ?: return content
        val shots = obj["screenshots"] as? JsonArray ?: return content
        val small = JsonArray(shots.map { e ->
            val o = e as? JsonObject
            if (o == null || "elementsText" !in o) e else JsonObject(o + ("elementsText" to JsonPrimitive(OMITTED)))
        })
        return JsonObject(obj + ("screenshots" to small)).toString()
    }

    private fun shrinkScreen(content: String): String {
        val obj = parse(content) ?: return content
        return JsonObject(obj + (SCREEN to JsonPrimitive(OMITTED))).toString()
    }

    private fun parse(content: String): JsonObject? = try {
        Json.parseToJsonElement(content) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    /** インターフェイスのステータス: 要素テーブルとロング フィールドは [OMITTED] に置き換えられ、終了フィールドは保持されます。 */
    private fun shrinkInterface(content: String): String {
        val obj = parse(content) ?: return content
        val small = obj.mapValues { (k, v) ->
            val long = v is JsonPrimitive && v.isString && v.content.length > LONG_TEXT
            if (k == "elements" || (long && k !in OUTCOME)) JsonPrimitive(OMITTED) else v
        }
        return JsonObject(small).toString()
    }
}
