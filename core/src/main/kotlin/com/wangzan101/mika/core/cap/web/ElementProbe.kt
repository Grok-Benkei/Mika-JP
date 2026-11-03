package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.cap.web.cdp.CdpEvaluationException
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * は、画面の読み取り時に DevTools セッションを通じてページ スクリプトの要素テーブルにアクセシビリティ情報を追加し、マージのためにそれを [ElementFusion] に渡します。
 *
 * ノード番号の変更方法: ページスクリプトは元々番号テーブルに従って要素参照を保持しており、テーブルをそのまま引き継がせます(一度Runtime.evaluateが配列オブジェクトを取得し、
 * は一度 Runtime.getProperties を実行して各要素のオブジェクト参照を削除し、同時に DOM.describeNode を backendNodeId に置き換えます。
 * はページにタグを書き込まず (ページと独自の変更カウントをトリガーしません)、DOM.getDocument 全体を取得しません (大きなページで数 MB)。
 * は、ヒットするために座標に依存しません (通常、ヒットはボタン内のテキストのサブ要素であるため、さらに調べる必要があります)。同時に N 個の descriptionNode が発行され、ラウンドトリップは 1 ラウンドとしてのみカウントされます。
 *
 * の後: Page.getFrameTree → 各フレーム Accessibility.getFullAXTree(frameId) (クロスソース フレームとメイン ドキュメントは同じターゲット内にあり、フレームごとにフェッチされます);
 * アクセシビリティ ツリーには候補のみがあります: DOM.getBoxModel ボックスの選択、最上部で確認された DOM.getNodeForLocation。
 * クリックできないテキストと画像: DOMDebugger.getEventListeners クリック リスナーがあるかどうかを確認します。
 * プロセス全体には時間制限があります [timeoutMs]。タイムアウトとエラーの場合は [Outcome.Skipped] が返され、呼び出し元はページ スクリプトの結果を使用します。
 */
class ElementProbe(private val session: CdpSession, private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    // 1 回の呼び出しの上限が緩和されます。特定のステップがタイムアウトになった後に不完全なデータが表示されるのではなく、最初に合計時間制限に達し、呼び出し全体が放棄されます。
    private val callTimeoutMs = timeoutMs + 1_000
    sealed interface Outcome {
        class Fused(val result: ElementFusion.Result, val anchors: Map<String, Anchor>, val stats: String) : Outcome
        class Skipped(val reason: String) : Outcome
    }

    /**
     * は、アクセシビリティ ツリーの要素にすぎません。クリックする前にもう一度検索してください: [hits] はそれ自体とその子孫のノード番号です (これらをヒットすると、クリックしたとカウントされます)。
     * [ownerId] は、フレーム要素のノード番号です (フレームがフレーム内の座標に従って与えられている場合は、変換に使用します)。
     */
    data class Anchor(val backendId: Int, val hits: Set<Int>, val ownerId: Int?)

    /**
     * (トップ ビューポート CSS ピクセル) によって検出された位置: [box] フレーム全体 ([x]、[y]) は、表示範囲内のフレームの一部の中心です (ここをクリック)。
     * [onTop] ヒット検出結果、検出されなかった場合は null。
     */
    data class Placed(val box: ElementFusion.Box, val onTop: Boolean?, val x: Double = box.cx, val y: Double = box.cy)

    private class Ref(val objectId: String?, val backendId: Int?, val frameId: String?)

    private class Skip(message: String) : Exception(message)

    /** [refNodes] は、ページのメイン環境で評価され、この数値テーブル内の要素の配列を返す式です。 [scale] は CSS ピクセル→ビューピクセルです。 */
    suspend fun enrich(page: ScreenSnapshot, refNodes: String, scale: Double): Outcome {
        val started = System.nanoTime()
        return try {
            withTimeout(timeoutMs) { run(page, refNodes, scale, started) }
        } catch (e: TimeoutCancellationException) {
            Outcome.Skipped("timeout after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Skipped(e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(RELEASE_TIMEOUT_MS) {
                    runCatching { session.send("Runtime.releaseObjectGroup", buildJsonObject { put("objectGroup", GROUP) }, timeoutMs = RELEASE_TIMEOUT_MS) }
                }
            }
        }
    }

    private suspend fun run(page: ScreenSnapshot, refNodes: String, scale: Double, started: Long): Outcome = coroutineScope {
        val frames = async { frameTree() }
        val mainAx = async { frames.await().firstOrNull()?.let { axTree(it.id) }.orEmpty() }
        val refs = refs(refNodes, page.elements.size)
        val tIds = ms(started)
        val frameList = frames.await()
        val owners = HashMap<Int, String>()
        refs.forEach { r -> if (r.backendId != null && r.frameId != null) owners[r.backendId] = r.frameId }
        // descriptionNode はフレーム番号を与えません。代わりに、フレーム要素ごとにフレーム要素を問い合わせます。
        val known = owners.values.toSet()
        frameList.drop(1).filter { it.id !in known }.take(MAX_FRAMES).map { f ->
            async { frameOwner(f.id)?.let { it to f.id } }
        }.awaitAll().filterNotNull().forEach { (id, f) -> owners[id] = f }
        val pageIds = refs.map { it.backendId }
        val base = ElementFusion.Input(page.elements, pageIds, owners, frameList, emptyList(), scale = scale, width = page.width, height = page.height)
        val wanted = framesToRead(base, frameList)
        val ax = mainAx.await() + wanted.map { async { axTree(it) } }.awaitAll().flatten()
        val tAx = ms(started)
        val withAx = ElementFusion.Input(page.elements, pageIds, owners, frameList, ax, scale = scale, width = page.width, height = page.height)
        val candidates = ElementFusion.candidates(withAx)
        val anchors = candidates.mapNotNull { c ->
            c.node.backendId?.let { it to Anchor(it, ElementFusion.subtreeIds(ax, c.node), c.ownerId) }
        }.toMap()
        val placed = candidates.map { c ->
            async { c.node.backendId?.let { id -> place(anchors.getValue(id), ElementFusion.clip(withAx, c))?.let { id to it } } }
        }
        val heard = ElementFusion.listenerProbes(withAx).map { i ->
            async { refs[i].objectId?.let { o -> if (listens(o)) refs[i].backendId else null } }
        }
        val boxes = placed.awaitAll().filterNotNull()
        val listeners = heard.awaitAll().filterNotNull().toSet()
        val input = ElementFusion.Input(
            page.elements, pageIds, owners, frameList, ax,
            boxes = boxes.associate { it.first to it.second.box },
            onTop = boxes.mapNotNull { (id, p) -> p.onTop?.let { id to it } }.toMap(),
            listeners = listeners, scale = scale, width = page.width, height = page.height,
        )
        val result = ElementFusion.fuse(input)
        val refAnchors = result.backendIds.filterKeys { result.origins[it] == ElementFusion.Origin.AX }
            .mapNotNull { (ref, id) -> anchors[id]?.let { ref to it } }.toMap()
        val stats = "els=${page.elements.size} matched=${pageIds.count { it != null }} frames=${frameList.size}/${wanted.size + 1} " +
            "ax=${ax.size} candidates=${candidates.size} added=${refAnchors.size} listeners=${listeners.size} omitted=${result.omitted} " +
            "ms(ids=$tIds ax=$tAx total=${ms(started)})"
        Outcome.Fused(result, refAnchors, stats)
    }

    /** マスター ドキュメントとクロスソース プレースホルダー内のフレームが [MAX_FRAMES] まで優先されます (メイン ドキュメントを除く)。 */
    private fun framesToRead(input: ElementFusion.Input, frames: List<ElementFusion.Frame>): List<String> {
        val behind = HashSet<String>()
        input.pageIds.forEachIndexed { i, id -> if (id != null && input.page[i].role == "frame") input.owners[id]?.let(behind::add) }
        val parent = frames.associate { it.id to it.parent }
        fun underPlaceholder(f: String): Boolean {
            var cur: String? = f
            var hops = 0
            while (cur != null && hops++ < 32) {
                if (cur in behind) return true
                cur = parent[cur]
            }
            return false
        }
        val rest = frames.drop(1).map { it.id }
        return (rest.filter(::underPlaceholder) + rest.filterNot(::underPlaceholder)).take(MAX_FRAMES)
    }

    private suspend fun refs(expression: String, expected: Int): List<Ref> = coroutineScope {
        val r = session.send(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", false)
                put("objectGroup", GROUP)
                put("silent", true)
            },
            timeoutMs = callTimeoutMs,
        )
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
        val arr = (r["result"] as? JsonObject)?.str("objectId") ?: throw Skip("page script has no ref table")
        val props = session.send(
            "Runtime.getProperties",
            buildJsonObject { put("objectId", arr); put("ownProperties", true) },
            timeoutMs = callTimeoutMs,
        )["result"] as? JsonArray ?: throw Skip("ref table unreadable")
        val byIndex = HashMap<Int, String?>()
        for (p in props) {
            val o = p as? JsonObject ?: continue
            val i = o.str("name")?.toIntOrNull() ?: continue
            byIndex[i] = (o["value"] as? JsonObject)?.takeIf { it.str("subtype") == "node" }?.str("objectId")
        }
        if (byIndex.size != expected) throw Skip("ref table has ${byIndex.size} entries, page tree has $expected")
        (0 until expected).map { i ->
            async {
                val oid = byIndex[i] ?: return@async Ref(null, null, null)
                val node = runCatching {
                    session.send("DOM.describeNode", buildJsonObject { put("objectId", oid) }, timeoutMs = callTimeoutMs)["node"] as? JsonObject
                }.getOrNull()
                Ref(oid, node?.int("backendNodeId"), node?.str("frameId"))
            }
        }.awaitAll()
    }

    private suspend fun frameTree(): List<ElementFusion.Frame> {
        val tree = session.send("Page.getFrameTree", timeoutMs = callTimeoutMs)["frameTree"] as? JsonObject ?: return emptyList()
        return parseFrameTree(tree)
    }

    private suspend fun frameOwner(frameId: String): Int? = runCatching {
        session.send("DOM.getFrameOwner", buildJsonObject { put("frameId", frameId) }, timeoutMs = callTimeoutMs).int("backendNodeId")
    }.getOrNull()

    private suspend fun axTree(frameId: String): List<ElementFusion.AxNode> = runCatching {
        val nodes = session.send("Accessibility.getFullAXTree", buildJsonObject { put("frameId", frameId) }, timeoutMs = callTimeoutMs)["nodes"] as? JsonArray
        parseAxTree(nodes.orEmpty().mapNotNull { it as? JsonObject }, frameId)
    }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    private suspend fun listens(objectId: String): Boolean = runCatching {
        val l = session.send("DOMDebugger.getEventListeners", buildJsonObject { put("objectId", objectId) }, timeoutMs = callTimeoutMs)["listeners"] as? JsonArray
        l.orEmpty().any { (it as? JsonObject)?.str("type") in POINTER_EVENTS }
    }.getOrElse { if (it is CancellationException) throw it else false }

    /**
     * フレームを選択し、ヒット検出を実行します ([clip] でフレームの表示部分の中心をクリックします)。トップレベルビューポート通りに座標がヒットせずフレーム内にある場合、
     * 「フレーム内の座標 + フレーム コンテンツ領域の左上隅」を押して再試行してください。カーネルのバージョンが異なると、フレーム内のノードに異なる座標系が与えられる場合があります。
     */
    suspend fun place(anchor: Anchor, clip: ElementFusion.Box? = null): Placed? {
        val box = boxOf(anchor.backendId) ?: return null
        val at = visibleCenter(box, clip)
        if (anchor.hits.isEmpty()) return Placed(box, null, at?.first ?: box.cx, at?.second ?: box.cy)
        val hit = at?.let { hitAt(it) }
        if (at != null && hit != null && hit in anchor.hits) return Placed(box, true, at.first, at.second)
        val owner = anchor.ownerId?.let { boxOf(it, content = true) }
        if (owner != null) {
            val shifted = ElementFusion.Box(box.l + owner.l, box.t + owner.t, box.r + owner.l, box.b + owner.t)
            val at2 = visibleCenter(shifted, clip)
            val again = at2?.let { hitAt(it) }
            if (at2 != null && again != null && again in anchor.hits) return Placed(shifted, true, at2.first, at2.second)
        }
        return Placed(box, if (hit == null) null else false, at?.first ?: box.cx, at?.second ?: box.cy)
    }

    /**
     * クリックする前に、アクセシビリティ ツリーでのみ使用できる要素を再検索します。まずビューポートにスクロールし (スクロール ホイールではなく DOM コマンド)、次にフレームを削除します。
     * フレーム内で、フレーム コンテンツ領域の表示部分の中心をクリックします。要素は null ではなくなりました。
     */
    suspend fun locate(anchor: Anchor): Placed? = withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
        runCatching { session.send("DOM.scrollIntoViewIfNeeded", buildJsonObject { put("backendNodeId", anchor.backendId) }, timeoutMs = LOCATE_TIMEOUT_MS) }
            .onFailure { if (it is CancellationException) throw it }
        runCatching { place(anchor, anchor.ownerId?.let { boxOf(it, content = true) }) }.getOrElse { if (it is CancellationException) throw it else null }
    }

    private suspend fun boxOf(backendId: Int, content: Boolean = false): ElementFusion.Box? = runCatching {
        val model = session.send("DOM.getBoxModel", buildJsonObject { put("backendNodeId", backendId) }, timeoutMs = callTimeoutMs)["model"] as? JsonObject
        quadBox(model?.get(if (content) "content" else "border") as? JsonArray)
    }.getOrElse { if (it is CancellationException) throw it else null }

    private fun visibleCenter(box: ElementFusion.Box, clip: ElementFusion.Box?): Pair<Double, Double>? {
        val l = max(box.l, clip?.l ?: box.l)
        val t = max(box.t, clip?.t ?: box.t)
        val r = min(box.r, clip?.r ?: box.r)
        val b = min(box.b, clip?.b ?: box.b)
        return if (r <= l || b <= t) null else (l + r) / 2 to (t + b) / 2
    }

    private suspend fun hitAt(at: Pair<Double, Double>): Int? {
        return runCatching {
            session.send(
                "DOM.getNodeForLocation",
                buildJsonObject {
                    put("x", at.first.roundToInt())
                    put("y", at.second.roundToInt())
                    put("includeUserAgentShadowDOM", false)
                },
                timeoutMs = callTimeoutMs,
            ).int("backendNodeId")
        }.getOrElse { if (it is CancellationException) throw it else null }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_500L
        const val LOCATE_TIMEOUT_MS = 1_000L
        private const val RELEASE_TIMEOUT_MS = 300L
        /** アクセシビリティ ツリーは、メイン ドキュメントに加えて、最大でこの数のフレームを読み取ることができます。 */
        const val MAX_FRAMES = 12
        private const val GROUP = "mika-elements"
        private val POINTER_EVENTS = setOf("click", "mousedown", "mouseup", "pointerdown", "pointerup", "touchstart", "touchend")

        /** `Page.getFrameTree` の FrameTree → 予約済みのフレーム リスト。メインのドキュメントが前面にあります。 */
        fun parseFrameTree(tree: JsonObject): List<ElementFusion.Frame> {
            val out = ArrayList<ElementFusion.Frame>()
            fun walk(t: JsonObject, parent: String?, depth: Int) {
                if (depth > 32) return
                val f = t["frame"] as? JsonObject ?: return
                val id = f.str("id") ?: return
                out += ElementFusion.Frame(id, f.str("parentId") ?: parent)
                (t["childFrames"] as? JsonArray).orEmpty().forEach { c -> (c as? JsonObject)?.let { walk(it, id, depth + 1) } }
            }
            walk(tree, null, 0)
            return out
        }

        /** `Accessibility.getFullAXTree` → [ElementFusion.AxNode] のノードの順序は、childIds の深さに従って並べ替えられます (返される順序には依存しません)。 */
        fun parseAxTree(nodes: List<JsonObject>, frameId: String): List<ElementFusion.AxNode> {
            val byId = LinkedHashMap<String, JsonObject>()
            for (n in nodes) n.str("nodeId")?.let { byId[it] = n }
            val parentOf = HashMap<String, String>()
            for ((id, n) in byId) {
                n.str("parentId")?.let { parentOf[id] = it }
                (n["childIds"] as? JsonArray).orEmpty().forEach { c -> (c as? JsonPrimitive)?.contentOrNull?.let { parentOf.putIfAbsent(it, id) } }
            }
            val order = LinkedHashMap<String, Int>()
            val roots = byId.keys.filter { parentOf[it]?.let(byId::containsKey) != true }
            for (root in roots) {
                val stack = ArrayDeque(listOf(root))
                while (stack.isNotEmpty()) {
                    val id = stack.removeLast()
                    if (id in order || id !in byId) continue
                    order[id] = order.size
                    val kids = (byId.getValue(id)["childIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    for (k in kids.asReversed()) stack.addLast(k)
                }
            }
            return order.keys.map { id ->
                val n = byId.getValue(id)
                ElementFusion.AxNode(
                    id = id,
                    parent = parentOf[id],
                    frameId = n.str("frameId") ?: frameId,
                    order = order.getValue(id),
                    backendId = n.int("backendDOMNodeId"),
                    role = ((n["role"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    name = ((n["name"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    ignored = (n["ignored"] as? JsonPrimitive)?.booleanOrNull == true,
                    states = states(n["properties"] as? JsonArray),
                )
            }
        }

        // ブール値は true、スリーステートは true/混合、シンボルは false ではありません。
        private fun states(props: JsonArray?): Set<String> {
            val out = HashSet<String>()
            for (p in props.orEmpty()) {
                val o = p as? JsonObject ?: continue
                val name = o.str("name") ?: continue
                val v = (o["value"] as? JsonObject)?.get("value") as? JsonPrimitive ?: continue
                val on = v.booleanOrNull ?: v.contentOrNull?.let { it.isNotEmpty() && it != "false" && it != "undefined" } ?: false
                if (on) out += name
            }
            return out
        }

        /** 4 つの頂点 (x1, y1,…,x4,y4)→ 囲む長方形。 */
        fun quadBox(q: JsonArray?): ElementFusion.Box? {
            val v = q?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
            if (v.size < 8) return null
            val xs = listOf(v[0], v[2], v[4], v[6])
            val ys = listOf(v[1], v[3], v[5], v[7])
            return ElementFusion.Box(xs.min(), ys.min(), xs.max(), ys.max())
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

        private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000
    }
}
