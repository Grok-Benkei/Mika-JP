package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 差額テキストの比率が全額を超える場合、全額が発行されます。差額はあまり節約されず、読みにくくなります。 */
private const val MAX_DIFF_RATIO = 0.5
private const val DEFAULT_MAX_ELEMENTS = 200

/**
 * 書き込み操作結果の `screen` フィールドに与えられる 2 つの画面の差 (安定した数値で整列):
 * `{"snapshotId","base","currentApp","full":false,"added":[元素],"removed":[{ref,name,role}],"changed":[元素+"was"],"unchanged":N}`。
 * にベースがない場合、[baseVisible] が false (モデルは差異が依存する画面読み取り値を表示できなくなります)、フロントエンド アプリが変更された場合、または差異テキストが全額の半分を超えている場合、全額が送信されます。
 * `{"snapshotId","currentApp","full":true,"elements":[…],"truncated"}`。
 * 差分は、2 つの画面の完全な要素リストに基づいて計算されます。出力中に追加/変更された要素の合計数は [maxElements] を超えず、超過部分は切り捨てられてマークされます。
 * 携帯電話のスナップショットにウィンドウ ツリーがある場合、完全な結果にはツリーが含まれ、差分の結果には TreeChanges が含まれます。呼び出し側は、要素の完全な数を携帯電話に渡します。
 */
object InterfaceUpdate {
    fun between(base: ModelSnapshot?, current: ModelSnapshot, baseVisible: Boolean, maxElements: Int = DEFAULT_MAX_ELEMENTS): JsonObject {
        val (all, _) = ScreenJson.elements(current.screen, Int.MAX_VALUE)
        fun full() = buildJsonObject {
            put("snapshotId", current.snapshotId)
            put("currentApp", current.port.packageName)
            put("full", true)
            flags(current)
            put("elements", JsonArray(all.take(maxElements)))
            put("truncated", all.size > maxElements)
            if (current.screen.windows.isNotEmpty()) put("tree", UiTreeJson.windows(current.screen))
        }
        if (base == null || !baseVisible || base.port.packageName != current.port.packageName) return full()
        if (current.screen.windows.isNotEmpty() && !UiTreeJson.sameWindows(base.screen, current.screen)) return full()
        val before = ScreenJson.elements(base.screen, Int.MAX_VALUE).first.associateBy(::ref)
        val now = all.associateBy(::ref)
        val added = all.filter { ref(it) !in before }
        val removed = before.filter { it.key !in now }.values.map { e ->
            val o = e as JsonObject
            buildJsonObject { put("ref", ref(e)); o["name"]?.let { put("name", it) }; o["role"]?.let { put("role", it) } }
        }
        val changed = all.mapNotNull { e ->
            val old = before[ref(e)] ?: return@mapNotNull null
            if (old == e) return@mapNotNull null
            val was = (old as JsonObject)["name"]
            if (was != (e as JsonObject)["name"]) JsonObject(e + ("was" to (was ?: JsonPrimitive("")))) else e
        }
        val diffChars = JsonArray(added).toString().length + JsonArray(removed).toString().length + JsonArray(changed).toString().length
        if (diffChars > all.toString().length * MAX_DIFF_RATIO) return full()
        val treeChanges = if (current.screen.windows.isEmpty()) null else UiTreeJson.changes(base.screen, current.screen)
        if (treeChanges != null && treeChanges.toString().length > UiTreeJson.windows(current.screen).toString().length * MAX_DIFF_RATIO) return full()
        val shownAdded = added.take(maxElements)
        val shownChanged = changed.take((maxElements - shownAdded.size).coerceAtLeast(0))
        return buildJsonObject {
            put("snapshotId", current.snapshotId)
            put("base", base.snapshotId)
            put("currentApp", current.port.packageName)
            put("full", false)
            flags(current)
            put("added", JsonArray(shownAdded))
            put("removed", JsonArray(removed))
            put("changed", JsonArray(shownChanged))
            put("unchanged", all.size - added.size - changed.size)
            if (shownAdded.size + shownChanged.size < added.size + changed.size) put("truncated", true)
            treeChanges?.let { put("treeChanges", it) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.flags(s: ModelSnapshot) {
        if (s.port.changedDuringRead) put("screenChangedDuringRead", true)
    }

    private fun ref(e: JsonElement) = ((e as JsonObject)["ref"] as JsonPrimitive).content
}
