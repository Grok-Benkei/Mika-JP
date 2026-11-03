package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** モデルに表示される携帯電話のウィンドウ レベル。ノードは完全に保持され、空のフィールドとデフォルト状態のみが省略されます。 */
object UiTreeJson {
    fun windows(s: ScreenSnapshot): JsonArray = buildJsonArray {
        for (w in s.windows) add(buildJsonObject {
            put("id", w.id)
            put("type", w.type)
            put("layer", w.layer)
            w.title?.let { put("title", it) }
            w.packageName?.let { put("packageName", it) }
            if (w.active) put("active", true)
            if (w.focused) put("focused", true)
            put("nodes", JsonArray(w.nodes.map(::node)))
        })
    }

    private fun node(n: UiTreeNode): JsonObject = JsonObject(shallow(n) +
        (if (n.children.isEmpty()) emptyMap() else mapOf("children" to JsonArray(n.children.map(::node)))))

    private fun shallow(n: UiTreeNode): JsonObject = buildJsonObject {
        put("role", n.role)
        n.ref?.let { put("ref", it) }
        n.text?.let { put("text", it) }
        n.description?.let { put("description", it) }
        n.hintText?.let { put("hintText", it) }
        n.resourceId?.let { put("resourceId", it) }
        put("bounds", JsonArray(n.bounds.map(::JsonPrimitive)))
        if (n.clickable) put("clickable", true)
        if (n.editable) put("editable", true)
        if (n.scrollable) put("scrollable", true)
        if (n.checkable) put("checked", n.checked)
        if (n.selected) put("selected", true)
        if (!n.enabled) put("enabled", false)
        if (n.focused) put("focused", true)
        if (n.secret) put("secret", true)
        if (n.wheel) put("wheel", true)
    }

    /** パスはウィンドウとサブノードのシリアル番号に従って配置されます。同じパス上の属性の変更はそのノードにのみ送信され、新しいノードはサブツリー全体に送信されます。 */
    fun changes(before: ScreenSnapshot, current: ScreenSnapshot): JsonObject {
        val old = index(before.windows)
        val now = index(current.windows)
        val updated = buildJsonArray {
            for ((path, n) in now) {
                val previous = old[path]
                if (previous == null && parent(path) in now && parent(path) !in old) continue // 新しい親ノードは完全なサブツリーをもたらしました
                if (previous == null || shallow(previous) != shallow(n)) add(buildJsonObject {
                    put("path", path)
                    put("node", if (previous == null) node(n) else shallow(n))
                })
            }
        }
        val gone = old.keys.filterTo(LinkedHashSet()) { it !in now }
        val removed = buildJsonArray { for (path in gone) if (parent(path) !in gone) add(JsonPrimitive(path)) }
        return buildJsonObject {
            put("updated", updated)
            put("removed", removed)
        }
    }

    fun sameWindows(a: ScreenSnapshot, b: ScreenSnapshot): Boolean =
        a.windows.map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) } ==
            b.windows.map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) }

    private fun index(windows: List<UiWindowTree>): LinkedHashMap<String, UiTreeNode> = LinkedHashMap<String, UiTreeNode>().also { out ->
        fun walk(prefix: String, nodes: List<UiTreeNode>) {
            nodes.forEachIndexed { i, node ->
                val path = "$prefix/$i"
                out[path] = node
                walk(path, node.children)
            }
        }
        windows.forEach { walk("w${it.id}", it.nodes) }
    }

    private fun parent(path: String): String = path.substringBeforeLast('/', "")
}
