package com.wangzan101.mika.core.guard

import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 画面読み取り、画像表示、および診断画面のカテゴリ: ガードはパラメーターではなくツールに基づいて繰り返されます。 */
internal val DIAGNOSTIC_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Screenshot, Verb.Describe, Verb.Diagnose, Verb.ViewPastScreens)
/** は、変更されない読み取り専用ページの一種です。ページが変更されない場合に繰り返し使用して読み取り、[OperationGuards.read] によって管理されます。 */
internal val PAGE_READ_VERBS = DIAGNOSTIC_VERBS + setOf(Verb.ReadPage, Verb.EvaluateJs, Verb.SiteCommand)

/** ツール結果のフィンガープリント (毎回変更される画面読み取り番号を除く)。 */
internal fun resultFingerprint(content: JsonObject): Long {
    val text = JsonObject(content - "snapshotId" - "guard" - "advice").toString()
    var h = -0x340d631b7bdddcdbL
    for (b in text.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
    return h
}

/** 画面フィンガープリント: 要素リスト テキスト用の 64 ビット FNV-1a。純テキストモデルのスクリーンショットがなくても「画面は変化していない」と判断できます。 */
internal fun elementsHash(elements: JsonArray?): Long? {
    elements ?: return null
    // には番号付けが含まれていません。安定した番号付けがオンになっている場合、同じ画面上で同じ名前を持つ繰り返し要素には読み取られるたびに新しい番号が付けられるため、画面の変更とみなされるべきではありません。
    val text = JsonArray(elements.map { e -> (e as? JsonObject)?.let { JsonObject(it - "ref") } ?: e }).toString()
    var h = -0x340d631b7bdddcdbL
    for (b in text.toByteArray(Charsets.UTF_8)) {
        h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
    }
    return h
}
