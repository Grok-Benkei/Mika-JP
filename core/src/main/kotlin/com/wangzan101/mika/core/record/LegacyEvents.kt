package com.wangzan101.mika.core.record

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.PlaceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 古い実行レコード内の名前変更されたイベントは、読み取り前に現在の書き込みメソッドに変更されます (読み取り中にのみ変更され、ファイルは移動しません)。
 * - screen_text (画面の読み取り時にモデルに与えられる要素テキスト) → 観察: 観察の読み取り、場所はその時点のサイトまたはアプリであり、番号は 0 です。
 * - screen_saved (次の画面読み取り画面を保存) → メディア。
 */
internal object LegacyEvents {
    fun upgrade(e: JsonElement): JsonElement {
        val o = e as? JsonObject ?: return e
        return when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
            "screen_text" -> buildJsonObject {
                put("type", "observed")
                o["atMs"]?.let { put("atMs", it) }
                o["step"]?.let { put("step", it) }
                put("kind", ObsKind.Read.wire)
                val site = o.str("site")
                val app = o.str("app")
                // その際、WebサイトはWebサイト、携帯電話はアプリに通報されました。
                (site?.let { PlaceKind.Site to it } ?: app?.let { PlaceKind.App to it })?.let { (k, n) ->
                    put("place", buildJsonObject { put("kind", k.wire); put("name", n) })
                    put("source", if (k == PlaceKind.Site) "web" else "phone")
                }
                put("text", (o["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().joinToString("\n"))
                o["inputs"]?.let { put("inputs", it) }
                o["focused"]?.let { put("focused", it) }
            }
            "screen_saved" -> buildJsonObject {
                put("type", "media")
                o["atMs"]?.let { put("atMs", it) }
                o["screenshotId"]?.let { put("mediaId", it) }
                o["step"]?.let { put("step", it) }
                o["app"]?.let { put("place", it) }
            }
            else -> o
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
