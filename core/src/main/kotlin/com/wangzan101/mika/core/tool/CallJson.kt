package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.observation.Place
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 呼び出しパラメータ; JSON オブジェクトではない場合、空のパラメーターとして扱われます (ツール層によって拒否されます)。 */
internal fun parseArgs(json: String): JsonObject = try {
    Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
} catch (e: SerializationException) {
    JsonObject(emptyMap())
}

/** 結果で報告される「場所」: 携帯電話上のフロントエンド アプリであり、Web ページ上のサイトです (アクションに伴う画面で報告されるものもカウントされます)。どちらも null として報告されます。 */
internal fun placeOf(content: JsonObject): Place? {
    val screen = content["screen"] as? JsonObject
    fun f(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
    (f(content, "currentApp") ?: f(screen, "currentApp"))?.let { return Place.app(it) }
    return (f(content, "currentSite") ?: f(screen, "currentSite"))?.let(Place::site)
}

/** の結果には、サイトのアクション (画面を読み取るための currentSite、Web ページを開いてページを読み取るための URL) が含まれます。 */
private val SITE_RESULT_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage)

/**
 * この呼び出し後のタスクはどこにありますか?結果は null ではありません (前の結果が使用されます)。 「場所」はモバイル アプリと Web サイトに分けられます。Web ページへの変更またはサイトの変更は、場所の変更としてカウントされます。
 * Web ページを開いてページを読み取ると、結果には URL のみが含まれます。どのサイトは URL のホストに基づいて計算されます (画面読み取りレポートの currentSite と同じアルゴリズム)。
 */
internal fun placeAfter(verb: Verb?, content: JsonObject): Place? =
    placeOf(content) ?: if (verb in SITE_RESULT_VERBS && content["ok"] == JsonPrimitive(true))
        (content["url"] as? JsonPrimitive)?.contentOrNull?.let(UrlHost::of)?.let(Place::site) else null
