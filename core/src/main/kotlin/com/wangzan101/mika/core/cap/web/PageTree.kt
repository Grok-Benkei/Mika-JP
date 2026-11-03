package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.max
import kotlin.math.roundToInt
import com.wangzan101.mika.core.uistate.WebInfo

/**
 * ページのスクリプト出力 → ScreenSnapshot。入力は次の形式です
 * `{"els":[{"r":"button","n":"搜索","x":120,"y":40,"w":80,"h":32,"id":"go","c":1,"e":0,"s":0}],"vw":412,"vh":915,"dpr":2.625,"trunc":false,"mut":17}`、
 * 座標は CSS ピクセルです。中心は WebView ピクセル (最初に viewportW / vw によって変換され、次に dpr によって変換) に置き換えられ、ビューポート内にクランプされます。境界は、携帯電話と同じ座標系であるビューポートに従って 0 ～ 999 に正規化されます。
 * にはオプションのタグもあります。 `ph`=name は、入力されたコンテンツではなく入力ボックスのプロンプト (プレースホルダー) であり、`f`= は入力フォーカスがあります。ファイル選択コントロールの`r`は「ファイル」です。
 * ref 配列位置による番号付け: 壊れたエントリも、ページ スクリプトの番号付けテーブルと一致する番号を占めます。
 * 空のリストへの不正な入力、例外はスローされません: 画面読み取りの失敗によりラウンド全体がクラッシュすることはありません。
 */
object PageTree {
    fun parse(json: String, viewportW: Int, viewportH: Int, snapshotId: String, info: WebInfo): ScreenSnapshot {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val cssW = root.num("vw")?.takeIf { it > 0 }
        val scale = cssW?.takeIf { viewportW > 0 }?.let { viewportW / it }
            ?: root.num("dpr")?.takeIf { it > 0 }
            ?: 1.0
        val elements = (root?.get("els") as? JsonArray).orEmpty()
            .mapIndexedNotNull { i, e -> (e as? JsonObject)?.let { element(i + 1, it, scale, viewportW, viewportH) } }
        return ScreenSnapshot(
            snapshotId = snapshotId,
            packageName = null,
            elements = elements,
            width = viewportW,
            height = viewportH,
            web = info.copy(truncated = root.flag("trunc")),
        )
    }

    private fun element(n: Int, o: JsonObject, scale: Double, w: Int, h: Int): UiElement {
        val l = px(o.num("x"), scale)
        val t = px(o.num("y"), scale)
        val r = px((o.num("x") ?: 0.0) + (o.num("w") ?: 0.0), scale)
        val b = px((o.num("y") ?: 0.0) + (o.num("h") ?: 0.0), scale)
        val id = o.str("id")?.takeIf { it.isNotBlank() }
        return UiElement(
            ref = "@e$n",
            role = o.str("r") ?: "text",
            name = o.str("n") ?: "",
            centerX = ((l + r) / 2).coerceIn(0, max(0, w - 1)),
            centerY = ((t + b) / 2).coerceIn(0, max(0, h - 1)),
            bounds = listOf(normalize(l, w), normalize(t, h), normalize(r, w), normalize(b, h)),
            resourceId = id?.let { if (it.startsWith("#")) it else "#$it" },
            clickable = o.flag("c"),
            editable = o.flag("e"),
            scrollable = o.flag("s"),
            hint = o.flag("ph"),
            focused = o.flag("f"),
            secret = o.flag("pw"),
            placeholder = o.str("ph")?.takeIf { it.isNotBlank() },
            searchField = o.flag("sr"),
            keyShortcuts = o.str("k")?.split(' ')?.filter(String::isNotBlank)?.take(MAX_SHORTCUTS).orEmpty(),
        )
    }

    /** ページに示されている数値は、巨大な値または無限である可能性があります。最初に固定してから四捨五入してください。丸めがオーバーフローして中心点が地平線に達しないようにしてください。 */
    private fun px(css: Double?, scale: Double): Int = ((css ?: 0.0) * scale).coerceIn(-PX_LIMIT, PX_LIMIT).roundToInt()

    private const val PX_LIMIT = 1e6
    private const val MAX_SHORTCUTS = 8

    private fun JsonObject?.num(key: String): Double? =
        (this?.get(key) as? JsonPrimitive)?.doubleOrNull?.let { if (it.isFinite()) it.coerceIn(-PX_LIMIT, PX_LIMIT) else 0.0 }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 0/1、true/falseを認識します。 */
    private fun JsonObject?.flag(key: String): Boolean {
        val p = this?.get(key) as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: ((p.intOrNull ?: 0) != 0)
    }
}
