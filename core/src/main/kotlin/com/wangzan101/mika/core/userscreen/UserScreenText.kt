package com.wangzan101.mika.core.userscreen

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.TreeAdequacy
import com.wangzan101.mika.core.uistate.TreeQuality
import com.wangzan101.mika.core.uistate.VisibleText
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * ユーザーの画面上の単語はどこから来たのか: まず、フロントエンド アプリのアクセシビリティ ツリーを使用します。ツリーが十分でない場合 (タスク画面の読み取りと同じ一連の判断、[TreeAdequacy])、画面上の単語を認識 (OCR)、
 * はツリーにない行のみを追加し、エラーが認められた行を削除します。各行には中心点があり、絵が見えないモデルでも左右（チャットにおける相手と自分の間の泡）を区別することができます。
 */
object UserScreenText {
    /** 1 つの画面には、最大でこの数の行と、1 行あたりこの数の単語を含めることができます。これは、文脈を圧倒することなく画面を明確に見るのに十分です。 */
    const val MAX_LINES = 120
    const val MAX_CHARS = 200

    /** ツリーだけでは明らかに不十分です。画面上の単語をもう一度読む必要があります。保護されたページは画面が真っ暗で認識できません。 */
    fun needsOcr(s: ScreenSnapshot): Boolean = TreeAdequacy.ofTree(s).quality.let { it != TreeQuality.Adequate && it != TreeQuality.Protected }

    /**
     * 画面上の文字は、同じ行上で上から下、左から右に配置されます。ツリー内の名前を持つ要素 (パスワード ボックスはカウントされません) は、[ocr] のツリーのない行と結合されます。
     * [top]/[bottom] (0 ～ 999) の先には、ステータス バーとナビゲーション バーがあります。そこで認識される単語 (時間、バッテリー) は必要ありません。 2 番目の値は、認識された単語が使用されているかどうかです。
     */
    fun lines(s: ScreenSnapshot, ocr: List<VisibleTextLine>?, top: Int = 0, bottom: Int = 999): Pair<List<ScreenLine>, Boolean> {
        val named = s.elements.filter { !it.secret && it.name.isNotBlank() }
        // 要素の中心はピクセルで、境界は認識された文字と同じです: 0 ～ 999: 0 ～ 999 に置き換え、結合して配置します。
        val tree = named.filterNot { joinsChildren(it, named) }
            .map { ScreenLine(clip(it.name), normalize(it.centerX, s.width), normalize(it.centerY, s.height)) }
        val extra = ocr?.let { VisibleText.missing(s, VisibleText.legible(it)) }.orEmpty()
            .map { ScreenLine(clip(it.text), (it.bounds[0] + it.bounds[2]) / 2, (it.bounds[1] + it.bounds[3]) / 2) }
            .filter { it.y in top..bottom }
        val all = (tree + extra).distinctBy { Triple(it.text, it.x / 20, it.y / 20) }
            .sortedWith(compareBy({ it.y / ROW }, { it.x }))
            .take(MAX_LINES)
        return all to extra.any { it in all }
    }

    /**
     * [e] その中に囲まれたいくつかの段落 (設定項目の行、メッセージのブロック全体) を接続するだけです。名前からこれらのいくつかの段落を削除すると、単語は残りません。
     * この時点で、それぞれ独自の位置を持つ内側の段落はそのままにしておきます。名前に独自の文字が含まれている場合は、それらも残してください。
     */
    private fun joinsChildren(e: UiElement, all: List<UiElement>): Boolean {
        val inner = all.filter { it !== e && inside(it.bounds, e.bounds) && area(it.bounds) < area(e.bounds) }
        if (inner.isEmpty()) return false
        var rest = e.name
        inner.map { it.name.trim() }.sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
        return rest.none { it.isLetterOrDigit() }
    }

    private fun inside(a: List<Int>, b: List<Int>) = a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    private fun area(b: List<Int>) = if (b.size == 4) (b[2] - b[0]).toLong() * (b[3] - b[1]) else 0L

    /** 同列許容値（0～999）：中央の高低差がこれ以下であれば同列とみなし、左右に合わせて配置します。 */
    private const val ROW = 12

    private fun clip(t: String): String {
        val one = t.replace('\n', ' ').trim()
        return if (one.length <= MAX_CHARS) one else one.take(MAX_CHARS) + "…"
    }
}

/** JSON で保存された 1 画面の説明 (スクリーンショットと一緒に)。読み取れない場合は null が返されます。 */
object UserScreenJson {
    fun encode(s: UserScreen): String = buildJsonObject {
        put("id", s.id); put("takenAt", s.takenAt)
        s.packageName?.let { put("package", it) }; s.appLabel?.let { put("label", it) }
        if (s.blocked) put("blocked", true)
        if (s.fromOcr) put("ocr", true)
        put("lines", JsonArray(s.lines.map { l -> buildJsonObject { put("t", l.text); put("x", l.x); put("y", l.y) } }))
    }.toString()

    fun decode(text: String): UserScreen? = runCatching {
        val o = Json.parseToJsonElement(text) as JsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        UserScreen(
            id = str("id") ?: return null,
            takenAt = (o["takenAt"] as? JsonPrimitive)?.longOrNull ?: return null,
            packageName = str("package"), appLabel = str("label"),
            blocked = (o["blocked"] as? JsonPrimitive)?.booleanOrNull == true,
            lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                ScreenLine((l["t"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    (l["x"] as? JsonPrimitive)?.intOrNull ?: 0, (l["y"] as? JsonPrimitive)?.intOrNull ?: 0)
            },
            fromOcr = (o["ocr"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()
}
