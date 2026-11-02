package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import com.wangzan101.mika.core.uistate.normalize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 要素の融合: ページ スクリプトの要素テーブル (表示されている列とカバーされていない列) と DevTools アクセシビリティ ツリーが、backendNodeId に基づいて 1 つの要素リストに結合されます。純粋な機能。
 * - 名前、役割、無効/フォーカス、その他のステータスとアクセス ツリー、位置、表示、オクルージョン、ページ スクリプトの両面があります。アクセシビリティ ツリー内の無視されたノードは考慮されません。
 *   名前に値が入力された入力ボックスとドロップダウン ボックスはページ スクリプトを保持します。ファイル コントロールと埋め込みページ プレースホルダー ロールはページ スクリプトを保持します。
 * - アクセシビリティ ツリーのみにあります: インタラクティブ文字のみが受け入れられます。ページ スクリプトで読み取れないクロスオリジン フレームワークでは (「埋め込みページ」プレースホルダーのみがリストされます)、
 *   フレームがプレースホルダーの表示範囲に切り取られ、プレースホルダーの後ろに挿入されます。ページ スクリプトで読み取ることができるドキュメント (閉じられたシャドウ ルートなど) では、閉じる前に先頭でヒット検出を確認する必要があります。
 * - ページ スクリプトのみが持っています: そのままにしておきます。要素自体にクリック リスナーがアタッチされたプレーン テキスト/画像は、クリック可能にアップグレードされます。
 * シーケンス: ドキュメント シーケンス。 [Input.budget] を超えると、最初に情報のないものが失われ、次にプレーン テキスト、最後にインタラクティブなものが失われます。紛失個数は省略して記載されます。
 * 番号は @e1... の順に並べ替えられます。 [Result.pageRefs] は新しい番号 → ページ スクリプト番号テーブルの番号です (アクセシビリティ ツリーの要素のみが番号ではありません)。
 */
object ElementFusion {
    const val DEFAULT_BUDGET = 400
    /** アクセシビリティ ツリーだけが最大で非常に多くの要素を持ちます (それぞれに 1 つのフレーム削除と 1 つのヒット検出が必要です)。 */
    const val MAX_CANDIDATES = 60
    /** は、最大でこの数のページ スクリプト要素のリスナーをチェックできます。 */
    const val MAX_LISTENER_PROBES = 40
    private const val NAME_CHARS = 80

    /**
     * アクセシビリティ ツリー内のノード。 [id]/[parent] はこのフレームのツリー内のノード番号、[order] はこのフレーム内のツリーの深度優先度です。
     * [states] は、真のブール/3 状態の属性名 (無効、フォーカス、読み取り専用、編集可能、チェック済み...) です。
     */
    data class AxNode(
        val id: String,
        val parent: String?,
        val frameId: String,
        val order: Int,
        val backendId: Int?,
        val role: String,
        val name: String,
        val ignored: Boolean = false,
        val states: Set<String> = emptySet(),
    )

    /** トップレベルのビューポート CSS ピクセルの四角形。 */
    data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    data class Frame(val id: String, val parent: String?)

    enum class Origin { PAGE, BOTH, AX }

    /**
     * [page] は PageTree の結果 (ビュー ピクセル) であり、[pageIds] はそれに添え字が揃えられています。 [owners] は、フレーム要素の backendNodeId → その中のドキュメントの FrameId です。
     * [boxes]/[onTop] アクセシビリティ ツリーのみを持つ候補ノード (backendNodeId による) を提供します: ボックス (トップレベルのビューポート座標に置き換えられます) とヒット検出結果。
     * [listeners] は、クリック リスナーがアタッチされた要素です。 [scale] は CSS ピクセル → ビュー ピクセルの倍数です。
     */
    class Input(
        val page: List<UiElement>,
        val pageIds: List<Int?>,
        val owners: Map<Int, String> = emptyMap(),
        val frames: List<Frame> = emptyList(),
        val ax: List<AxNode> = emptyList(),
        val boxes: Map<Int, Box> = emptyMap(),
        val onTop: Map<Int, Boolean> = emptyMap(),
        val listeners: Set<Int> = emptySet(),
        val scale: Double = 1.0,
        val width: Int,
        val height: Int,
        val budget: Int = DEFAULT_BUDGET,
    )

    class Result(
        val elements: List<UiElement>,
        val pageRefs: Map<String, String>,
        val backendIds: Map<String, Int>,
        val origins: Map<String, Origin>,
        /** アクセシビリティ ツリー内の要素が配置されているフレームのプレースホルダー要素のみ (新しい番号 → プレースホルダーの backendNodeId)。メインドキュメントでは空です。 */
        val frameOwners: Map<String, Int>,
        val omitted: Int,
    )

    /** 候補ノード: [placeholder] は、ページ スクリプト要素テーブルに配置されるクロスソース フレームワークのプレースホルダー添字であり、ページ スクリプトによって読み取られるドキュメントでは null です。 */
    class Candidate(val node: AxNode, val placeholder: Int?, val ownerId: Int?)

    // ---------- 役割 ----------

    private val CLICK_ROLES = setOf(
        "link", "button", "checkbox", "radio", "combobox", "switch", "tab", "menuitem", "option", "slider",
        "menuitemcheckbox", "menuitemradio", "treeitem", "listbox",
    )
    private val EDIT_ROLES = setOf("textbox")
    /** ページ スクリプトによって与えられるロールでは、アクセシビリティの変更は許可されていません。アップロードにはファイルが必要で、フレームはクロスオリジン フレームワークのプレースホルダーです。 */
    private val KEEP_PAGE_ROLES = setOf("file", "frame")
    private val NON_INTERACTIVE_ROLES = setOf("heading", "image")

    /** アクセシビリティ ツリーの役割 → ページ スクリプトと同じ単語のセット。セマンティクスのないもの (ジェネリック、StaticText...) は null です。 */
    fun role(ax: String): String? = when (ax) {
        "searchbox", "spinbutton" -> "textbox"
        "img" -> "image"
        "PopUpButton" -> "combobox"
        "DisclosureTriangle", "ToggleButton" -> "button"
        "Iframe", "IframePresentational" -> "frame"
        in CLICK_ROLES, in EDIT_ROLES, in NON_INTERACTIVE_ROLES -> ax
        else -> null
    }

    private fun interactive(role: String?) = role != null && (role in CLICK_ROLES || role in EDIT_ROLES)

    // ---------- ステップ 1: フレーム埋めの候補 ----------

    /** アクセシビリティ ツリーには、対話型で追加する価値のあるノードのみがあります。これらは、ページ スクリプトでは読み取れないクロスオリジン フレームワークで 1 位にランクされています。 */
    fun candidates(input: Input): List<Candidate> {
        val matched = input.pageIds.filterNotNull().toSet()
        val byFrame = input.ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        val pageIndex = input.pageIds.withIndex().filter { it.value != null }.associate { it.value!! to it.index }
        val behind = placeholders(input)
        val out = mutableListOf<Candidate>()
        for (n in input.ax) {
            val id = n.backendId ?: continue
            if (n.ignored || id in matched || !interactive(role(n.role))) continue
            val ph = behind[n.frameId]
            if (ph == null) {
                // 読み取り可能なドキュメントでは、ページ スクリプトによってリストされた外側のレイヤーにインタラクティブな要素があります。ページ スクリプトは外側のレイヤーを認識しますが、リストしません。それらのほとんどは覆われているか、外層の一部のみです。
                val nodes = byFrame[n.frameId].orEmpty()
                var p = n.parent?.let(nodes::get)
                var covered = false
                while (p != null) {
                    val pi = p.backendId?.let(pageIndex::get)
                    if (pi != null && input.page[pi].let { it.clickable || it.editable }) { covered = true; break }
                    p = p.parent?.let(nodes::get)
                }
                if (covered) continue
            }
            out += Candidate(n, ph?.first, ph?.second)
        }
        return out.sortedBy { if (it.placeholder != null) 0 else 1 }.take(MAX_CANDIDATES)
    }

    /** FrameId → (ページ スクリプト要素テーブル内のプレースホルダーの添字、プレースホルダーの backendNodeId): プレースホルダー フレーム自体とその内部のフレーム。 */
    private fun placeholders(input: Input): Map<String, Pair<Int, Int>> {
        val direct = HashMap<String, Pair<Int, Int>>()
        input.pageIds.forEachIndexed { i, id ->
            if (id != null && input.page[i].role == "frame") input.owners[id]?.let { direct[it] = i to id }
        }
        if (direct.isEmpty()) return direct
        val parent = input.frames.associate { it.id to it.parent }
        val out = HashMap<String, Pair<Int, Int>>()
        for (f in input.frames.map { it.id } + direct.keys) {
            var cur: String? = f
            var hops = 0
            while (cur != null && hops++ < 32) {
                direct[cur]?.let { out[f] = it }
                if (out.containsKey(f)) break
                cur = parent[cur]
            }
        }
        return out
    }

    /** このフレームのバリアフリー ツリー内のノードの子孫 (それ自体を含む) に対応する backendNodeId: これらのノードに該当するヒット検出は、ヒットしたものとしてカウントされます。 */
    fun subtreeIds(ax: List<AxNode>, node: AxNode): Set<Int> {
        val children = ax.filter { it.frameId == node.frameId }.groupBy { it.parent }
        val out = HashSet<Int>()
        val stack = ArrayDeque(listOf(node))
        while (stack.isNotEmpty() && out.size < 500) {
            val n = stack.removeLast()
            n.backendId?.let(out::add)
            children[n.id]?.let(stack::addAll)
        }
        return out
    }

    /** 候補ノードの表示範囲 (CSS ピクセル): クロスソース フレームの表示部分はプレースホルダーで、残りはビューポートです。当たり判定点はボックスとの交点の中心となります。 */
    fun clip(input: Input, c: Candidate): Box {
        val k = input.scale.takeIf { it > 0 } ?: 1.0
        val r = c.placeholder?.let { rectOf(input.page[it], input.width, input.height) }
            ?: Rect(0.0, 0.0, input.width.toDouble(), input.height.toDouble())
        return Box(r.l / k, r.t / k, r.r / k, r.b / k)
    }

    /** リスナー用にチェックされるページ スクリプト要素の添え字: クリックできないプレーン テキストと画像、およびアクセシビリティ ツリーがインタラクティブであることを示していません。 */
    fun listenerProbes(input: Input): List<Int> {
        val axById = axByBackend(input.ax)
        return input.page.indices.filter { i ->
            val e = input.page[i]
            val id = input.pageIds.getOrNull(i) ?: return@filter false
            !e.clickable && !e.editable && (e.role == "text" || e.role == "image") &&
                !interactive(axById[id]?.takeIf { !it.ignored }?.let { role(it.role) })
        }.take(MAX_LISTENER_PROBES)
    }

    private fun axByBackend(ax: List<AxNode>): Map<Int, AxNode> {
        val m = HashMap<Int, AxNode>()
        for (n in ax) {
            val id = n.backendId ?: continue
            val old = m[id]
            if (old == null || (old.ignored && !n.ignored)) m[id] = n
        }
        return m
    }

    // ---------- ステップ 2: マージ ----------

    fun fuse(input: Input): Result {
        val axById = axByBackend(input.ax)
        val merged = input.page.mapIndexed { i, e ->
            val id = input.pageIds.getOrNull(i)
            Item(merge(e, id?.let(axById::get), id != null && id in input.listeners), id, e.ref, if (id?.let(axById::get) != null) Origin.BOTH else Origin.PAGE, null)
        }
        val extra = axOnly(input)
        // 挿入: クロスソース フレーム内の挿入はプレースホルダーの後に続きます。読み取られたドキュメント内の要素は同じフレームに従い、アクセシビリティ ツリーの順序はその前に位置合わせされた最後の要素の後にあります。
        val pageOrder = HashMap<Int, Pair<String, Int>>()
        input.pageIds.forEachIndexed { i, id -> id?.let(axById::get)?.let { pageOrder[i] = it.frameId to it.order } }
        val after = extra.groupBy { c ->
            c.placeholder ?: pageOrder.entries.filter { it.value.first == c.node.frameId && it.value.second < c.node.order }
                .maxByOrNull { it.value.second }?.key ?: (input.page.size - 1)
        }
        val all = ArrayList<Item>()
        if (input.page.isEmpty()) extra.forEach { all += it.item }
        merged.forEachIndexed { i, item ->
            all += item
            after[i]?.sortedBy { it.node.order }?.forEach { all += it.item }
        }
        val kept = budget(all, input.budget)
        val elements = ArrayList<UiElement>(kept.size)
        val pageRefs = HashMap<String, String>()
        val ids = HashMap<String, Int>()
        val origins = HashMap<String, Origin>()
        val owners = HashMap<String, Int>()
        kept.forEachIndexed { i, it ->
            val ref = "@e${i + 1}"
            elements += it.element.copy(ref = ref)
            it.pageRef?.let { r -> pageRefs[ref] = r }
            it.backendId?.let { b -> ids[ref] = b }
            it.ownerId?.let { o -> owners[ref] = o }
            origins[ref] = it.origin
        }
        return Result(elements, pageRefs, ids, origins, owners, all.size - kept.size)
    }

    private class Item(val element: UiElement, val backendId: Int?, val pageRef: String?, val origin: Origin, val ownerId: Int?)

    private class Extra(val node: AxNode, val placeholder: Int?, val item: Item)

    private fun merge(e: UiElement, ax: AxNode?, listener: Boolean): UiElement {
        val heard = listener && !e.clickable && !e.editable && (e.role == "text" || e.role == "image")
        val base = if (heard) e.copy(role = if (e.role == "text") "button" else e.role, clickable = true) else e
        if (ax == null || ax.ignored) return base
        val axRole = role(ax.role)
        val roleOut = when {
            base.role in KEEP_PAGE_ROLES || axRole == null || axRole == "frame" -> base.role
            interactive(axRole) -> axRole
            base.role == "text" -> axRole
            else -> base.role
        }
        val axName = cut(ax.name)
        // 入力ボックス、ドロップダウン ボックス、およびスライダーの名前に値があります (パスワード ボックスには、入力された人数のみが表示されます): ページ スクリプトを保持します。
        val valued = base.editable || base.role in setOf("textbox", "combobox", "slider") || roleOut in EDIT_ROLES
        val name = when {
            valued -> base.name.ifBlank { axName }
            axName.isNotBlank() -> axName
            else -> base.name
        }
        val disabled = "disabled" in ax.states
        val readonly = "readonly" in ax.states
        return base.copy(
            role = roleOut,
            name = name,
            clickable = !disabled && (base.clickable || roleOut in CLICK_ROLES),
            editable = !disabled && !readonly && (base.editable || (roleOut in EDIT_ROLES && "editable" in ax.states)),
            focused = base.focused || "focused" in ax.states,
            states = base.states + ax.states.filter { it in UiStates.ALL },
        )
    }

    private fun axOnly(input: Input): List<Extra> {
        val w = input.width
        val h = input.height
        val view = Rect(0.0, 0.0, w.toDouble(), h.toDouble())
        val pageRects = input.page.map { rectOf(it, w, h) }
        val out = ArrayList<Extra>()
        val keptRects = ArrayList<Pair<Extra, Rect>>()
        val nodesByFrame = input.ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        for (c in candidates(input)) {
            val id = c.node.backendId ?: continue
            val box = input.boxes[id] ?: continue
            val top = input.onTop[id]
            // 読むことができるドキュメント内: ページ スクリプトにはリストされておらず、ヒット検出が先頭に記載されています。クロスソース フレームワークでは、ヒット検出ではカバーされているため、収集されないと表示されます。
            if (c.placeholder == null && top != true) continue
            if (c.placeholder != null && top == false) continue
            val clip = c.placeholder?.let { pageRects[it] } ?: view
            val raw = Rect(box.l * input.scale, box.t * input.scale, box.r * input.scale, box.b * input.scale)
            val vis = raw.intersect(clip)?.intersect(view) ?: continue
            if (vis.r - vis.l < 1 || vis.b - vis.t < 1) continue
            val roleOut = role(c.node.role) ?: continue
            val name = cut(c.node.name)
            // ノード番号がページ スクリプト内で一致しませんが、実際には同じ要素です (同じ名前、またはインタラクティブでほとんど重複しています): 重複する列はありません
            val dup = input.page.indices.any { i ->
                val e = input.page[i]
                if (input.pageIds[i] != null || e.role == "frame") return@any false
                val r = pageRects[i]
                r.contains(vis.cx, vis.cy) && (e.name.equals(name, ignoreCase = true) || ((e.clickable || e.editable) && r.iou(vis) > 0.5))
            }
            if (dup) continue
            // アクセシビリティ ツリーにネストされた同じ名前のインタラクティブ ノード (リンク内のボタン)、キャラクターと同じ名前、ほぼ同じフレーム: 外側のレイヤー/最初のレイヤーのみが残る
            val nested = keptRects.any { (k, r) ->
                val sameName = k.item.element.name.equals(name, ignoreCase = true)
                (sameName || name.isBlank()) && isAncestor(nodesByFrame[c.node.frameId].orEmpty(), k.node, c.node) ||
                    (sameName && k.item.element.role == roleOut && r.iou(vis) > 0.8)
            }
            if (nested) continue
            val disabled = "disabled" in c.node.states
            val l = vis.l.roundToInt()
            val t = vis.t.roundToInt()
            val r = vis.r.roundToInt()
            val b = vis.b.roundToInt()
            val el = UiElement(
                ref = "",
                role = roleOut,
                name = name,
                centerX = ((l + r) / 2).coerceIn(0, max(0, w - 1)),
                centerY = ((t + b) / 2).coerceIn(0, max(0, h - 1)),
                bounds = listOf(normalize(l, w), normalize(t, h), normalize(r, w), normalize(b, h)),
                resourceId = null,
                clickable = !disabled && roleOut in CLICK_ROLES,
                editable = !disabled && "readonly" !in c.node.states && roleOut in EDIT_ROLES,
                scrollable = false,
                focused = "focused" in c.node.states,
                states = c.node.states.filter { it in UiStates.ALL }.toSet(),
                // アクセシビリティ ツリーには入力ボックスのみが表示されます (クロスソース フレームワークと画面読み上げスクリプトはリストされません): パスワード ボックスかどうかを見分けることができないため、パスワード ボックスとしてカウントされます。
                secret = !disabled && "readonly" !in c.node.states && roleOut in EDIT_ROLES,
            )
            val x = Extra(c.node, c.placeholder, Item(el, id, null, Origin.AX, c.ownerId))
            out += x
            keptRects += x to vis
        }
        return out
    }

    private fun isAncestor(nodes: Map<String, AxNode>, outer: AxNode, inner: AxNode): Boolean {
        if (outer.frameId != inner.frameId) return false
        var p = inner.parent?.let(nodes::get)
        var hops = 0
        while (p != null && hops++ < 256) {
            if (p.id == outer.id) return true
            p = p.parent?.let(nodes::get)
        }
        return false
    }

    /** 予算を超えた場合、「情報なし→プレーンテキスト等の非インタラクティブ→インタラクティブ」の順序は後ろから前に破棄され、残ったものは元の順序のままになります。 */
    private fun budget(all: List<Item>, budget: Int): List<Item> {
        if (all.size <= budget) return all
        fun tier(e: UiElement) = when {
            !e.meaningful -> 0
            !(e.clickable || e.editable || e.scrollable) -> 1
            else -> 2
        }
        val drop = HashSet<Int>()
        var need = all.size - budget.coerceAtLeast(0)
        for (t in 0..2) {
            for (i in all.indices.reversed()) {
                if (need == 0) break
                if (tier(all[i].element) == t) { drop += i; need-- }
            }
        }
        return all.filterIndexed { i, _ -> i !in drop }
    }

    private fun cut(s: String): String {
        val c = s.replace(Regex("\\s+"), " ").trim()
        return if (c.length > NAME_CHARS) c.substring(0, NAME_CHARS) else c
    }

    private data class Rect(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        fun intersect(o: Rect): Rect? {
            val nl = max(l, o.l); val nt = max(t, o.t); val nr = min(r, o.r); val nb = min(b, o.b)
            return if (nr > nl && nb > nt) Rect(nl, nt, nr, nb) else null
        }
        fun contains(x: Double, y: Double) = x >= l && x <= r && y >= t && y <= b
        private fun area() = max(0.0, r - l) * max(0.0, b - t)
        fun iou(o: Rect): Double {
            val i = intersect(o)?.area() ?: return 0.0
            val u = area() + o.area() - i
            return if (u <= 0) 0.0 else i / u
        }
    }

    // は 0 ～ 999 を正規化して表示ピクセルに戻します。誤差は 1 ピクセル未満です
    private fun rectOf(e: UiElement, w: Int, h: Int): Rect {
        fun dx(v: Int) = v * (w - 1).coerceAtLeast(1) / 999.0
        fun dy(v: Int) = v * (h - 1).coerceAtLeast(1) / 999.0
        return Rect(dx(e.bounds[0]), dy(e.bounds[1]), dx(e.bounds[2]), dy(e.bounds[3]))
    }
}
