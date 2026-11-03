package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/**
 * ルート内の要素を識別する方法: 番号は 1 つの画面読み取り内でのみ有効です。これを、同じタイプのロール、名前、リソース ID、位置 (0 ～ 999)、およびシリアル番号に置き換えます。
 * [name] 空の場合は、名前で認識されないことを意味します。 `{1}` の形式の場合、名前がこの元のスピーチのスロットから取得されたことを意味します。
 * [uniqueAtRecord]: 録画中にこれらの機能を押すと、その画面上で 1 つの機能のみが表示されます。
 */
@Serializable
data class Selector(
    val role: String,
    val name: String,
    val resourceId: String?,
    val bounds: List<Int>,
    val ordinal: Int,
    val uniqueAtRecord: Boolean = false,
)

/** 1 つの画面のアンカー ポイント: フロントエンド パッケージ名 + 最大 [RouteAnchors.MAX] の安定したテキスト。 */
@Serializable
data class Anchors(val packageName: String?, val texts: List<String>)

/** [argsJson] 番号が削除され、入力テキストのスロットは `{1}` として書き込まれます。 */
@Serializable
data class RouteStep(val tool: String, val argsJson: String, val target: Selector?, val before: Anchors, val after: Anchors)

/** 実績のある方法。 [template] は、元の文のスロット値を `{n}` に置き換えた後の文です。 */
@Serializable
data class Route(
    val id: String,
    val template: String,
    val slots: Int,
    val packages: List<String>,
    val appVersions: Map<String, String>,
    val steps: List<RouteStep>,
    val successes: Int = 0,
    val consecutiveMisses: Int = 0,
    val missStep: Int? = null,
    val disabled: Boolean = false,
    val lastUsedMs: Long,
)

/** ルートのサイズの上限: ステップ数とドロップされたバイト数。 */
const val MAX_ROUTE_STEPS = 60
const val MAX_ROUTE_BYTES = 64 * 1024

/**
 * 名前変更前に記録されたルート: 手順内の古いツール名は、ルートを記録できるツールに置き換えられます (複数の画面で使用できる動詞、再生できるセットを取得します)。
 * 現在の名前は変更されません。認識できないものは残ります。再生中に「再生できないアクション」を押すと停止します。
 */
fun Route.withCurrentToolNames(legacy: LegacyNames, tools: ToolIndex): Route {
    if (steps.all { tools[it.tool] != null }) return this
    return copy(steps = steps.map { st ->
        if (tools[st.tool] != null) st else legacy.resolve(st.tool) { tools[it]?.routable == true }?.let { st.copy(tool = it) } ?: st
    })
}

object RouteCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(r: Route): String = json.encodeToString(Route.serializer(), r)
    fun decode(s: String): Route = json.decodeFromString(Route.serializer(), s)
}

/** 画面読み取りからセレクターとアンカーポイントを取得します。 */
object RouteAnchors {
    const val MAX = 3
    private const val MAX_LEN = 12
    /** 数値、時間、金額は毎回変更される可能性があるため、アンカー ポイントとして使用しないでください。 */
    private val VOLATILE = Regex("[0-9０-９¥￥$€£%]")
    private val LIST_ROLES = setOf("list", "grid", "recycler", "scroll")

    fun stable(text: String): Boolean {
        val t = text.trim()
        return t.length in 1..MAX_LEN && !VOLATILE.containsMatchIn(t)
    }

    private fun container(e: UiElement) = e.scrollable || e.role.lowercase() in LIST_ROLES

    private fun inside(e: UiElement, c: UiElement) = e !== c && e.bounds.size == 4 && c.bounds.size == 4 &&
        e.bounds[0] >= c.bounds[0] && e.bounds[1] >= c.bounds[1] && e.bounds[2] <= c.bounds[2] && e.bounds[3] <= c.bounds[3]

    /**
     * アンカーポイントとして使用できるテキストは画面上の順序で重複排除され、リソースIDを持つテキストが優先されます。リストおよびスクロール可能な領域内の項目はコンテンツ (連絡先、メッセージ、製品)、
     * 入力ボックス内の単語がユーザーによって書き込まれた場合、それらはカウントされません。ページの固定フレーム (タイトル バー、ラベル、ボタン) のみが取得されます。
     */
    fun texts(s: ScreenSnapshot): List<String> {
        val boxes = s.elements.filter(::container)
        return s.elements
            .filter { e -> !e.editable && !container(e) && boxes.none { inside(e, it) } && stable(e.name) }
            .sortedBy { it.resourceId == null }
            .map { it.name.trim() }.distinct()
    }

    fun before(s: ScreenSnapshot?, target: UiElement?): Anchors {
        if (s == null) return Anchors(null, emptyList())
        val self = target?.name?.trim()
        return Anchors(s.packageName, texts(s).filter { it != self }.take(MAX))
    }

    /** アクションの後に表示される新しいテキスト (アクションの前には画面に表示されませんでした)。 */
    fun after(before: ScreenSnapshot?, after: ScreenSnapshot): Anchors {
        val old = before?.let(::texts)?.toSet().orEmpty()
        return Anchors(after.packageName, texts(after).filter { it !in old }.take(MAX))
    }

    /**
     * セレクター内の名前: 入力ボックスにはプロンプトのみが残ります (そうでない場合は空になります)。これは、インターフェースがユーザーによって書かれた単語ではないことを意味します。
     * 他の要素の名前は、アンカー ポイントと同じくらい安定している必要があります (短く、数字なし)。そうでない場合、名前は保存されず、ロール、リソース ID、シリアル番号によってのみ識別されます。
     */
    fun storedName(e: UiElement): String = when {
        e.editable -> if (e.hint && stable(e.name)) e.name.trim() else ""
        stable(e.name) -> e.name.trim()
        else -> ""
    }

    fun selector(s: ScreenSnapshot, e: UiElement): Selector {
        val name = storedName(e)
        // 保存された特性によると、名前が保存されていない場合は、キャラクターおよびリソース ID とのみ比較されます。
        fun same(x: UiElement) = x.role == e.role && x.resourceId == e.resourceId && (name.isEmpty() || x.name.trim() == e.name.trim())
        val ordinal = s.elements.takeWhile { it !== e }.count(::same)
        return Selector(e.role, name, e.resourceId, e.bounds, ordinal, uniqueAtRecord = s.elements.count(::same) == 1)
    }

    /** には、点の最小要素 (0 ～ 999) が含まれます。エネルギーポイントは同じ時間で最初に取得されます。 */
    fun elementAt(s: ScreenSnapshot, x: Int, y: Int): UiElement? = s.elements
        .filter { it.bounds.size == 4 && x in it.bounds[0]..it.bounds[2] && y in it.bounds[1]..it.bounds[3] }
        .minWithOrNull(compareBy<UiElement> { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }.thenBy { !it.clickable })
}

/** 有効な書き込み操作: OK、インターフェイスが変更されているか、入力リードバックが一貫しています。アプリを起動してリンクを開くと成功したとみなされます (ポートはターゲットがフォアグラウンドになるまで待機します)。 */
fun effectiveWrite(verb: Verb?, result: JsonObject): Boolean {
    if (result["ok"] != JsonPrimitive(true)) return false
    return result["changed"] == JsonPrimitive(true) || result["verification"] == JsonPrimitive("match") ||
        (verb in OPENS && result["changed"] != JsonPrimitive(false))
}

/** アプリを開いてリンクを開きます。成功とは目標に到達することを意味します。 */
internal val OPENS = setOf(Verb.LaunchApp, Verb.OpenLink)
