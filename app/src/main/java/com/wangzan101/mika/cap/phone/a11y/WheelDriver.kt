package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.core.cap.phone.WheelDrags
import com.wangzan101.mika.core.cap.phone.WheelMath
import com.wangzan101.mika.core.cap.phone.WheelSteps
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.WheelOutcome
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * スクロール ホイールを回転して値を指定し、1 回の呼び出しで完了します。実行できる内容に応じて、コントロールを順番に試します (実行できる場合は実行し、実行できない場合は次のコントロールに戻ります)。
 * 1. スクロール ホイールには入力ボックスがあります (元のデジタル スクロール ホイールにはありました)。値を直接書き込み、フォーカスを外して確認し、それを読み戻します。
 * 2. スクロール ホイールは障害なく「前方/後方にロール」できます (元のデジタル スクロール ホイールは 1 フレームをクリックします): 現在の値を読み取り、[WheelSteps] を押して方向を設定し、カウント ダウンし、一度に 1 つのバッチをロールし、各バッチを読み取ります。
 * 3. どれも機能しません (アプリ自体によって描画されるスクロール ホイール): この列に表示されているいくつかの項目を読んで (問題なく読めない場合は、画面上の単語を認識してください)、1 つのグリッドの高さと現在の値を推定し、この列をゆっくりとドラッグします。
 *    (頭を動かさないように動かないでください)、読み返してから修正してください。値が動かない場合は、さらにドラッグし、より速く変更して、もう一度試してください。それでも動かない場合は、not_moving を報告し、モデルを返します。
 *
 * によって与えられる番号は、スクロール ホイールであることも、表示される特定の項目 (通常は選択された項目) であることもあります。このとき、スクロール ホイールは、この項目と水平に並んだ列内にあります。
 * 同じブロック内の他のスクロール ホイール (時間の隣の分) は、読み取りまたはドラッグできません。
 * の読み取り、ドラッグ、およびその他のインターフェイス機能はすべて [AndroidScreen] によって挿入されます。ここでは手順のみに焦点を当てます。
 */
internal class WheelDriver(
    private val width: Int,
    private val height: Int,
    /** インターフェイスが継続的に QuietMs (capMs まで) になるまで待ちます。 */
    private val quiet: suspend (quietMs: Long, capMs: Long) -> Unit,
    /** これらの点 (ピクセル) に沿って 1 本の指をドラッグします。 [slow] が true の場合、ゆっくりとドラッグし、放す前に停止します。 false の場合、人間のように素早くドラッグします。 */
    private val drag: suspend (points: List<Pair<Int, Int>>, slow: Boolean) -> Boolean,
    /** は画面上の文字を識別します (0 ～ 999 座標)。そうでない場合は null です。 */
    private val ocr: suspend () -> List<VisibleTextLine>?,
    /** この画面には入力方法がポップアップ表示されます。 */
    private val imeShown: () -> Boolean,
    /** クリックして戻ります (入力メソッドを折りたたむ)。 */
    private val back: () -> Boolean,
) {
    private var attempts = 0

    /** 1 回限りの読み取り値: 選択した項目 [current] (null として読み取ることはできません) およびこの列に表示されるいくつかの項目 (上から下)、[selected] は選択した項目の添字です。 */
    private class Reading(val current: String?, val items: List<WheelMath.Item>, val selected: Int) {
        val texts get() = items.map { it.text }
    }

    /**
     * 転送される列: [root] から下方に読み取り、[left]..[right] の水平方向にある項目のみを認識します。 [mid] は、選択したセルの中心線です。
     * [rowHeight] は、過大評価が 1 グリッド以上出ない場合の撤退です (1 項目を指定した場合は、それ自体の高さになります)。
     */
    private class Column(val root: AccessibilityNodeInfo, val box: Rect, val left: Int, val right: Int, val mid: Int, val rowHeight: Int)

    suspend fun set(start: AccessibilityNodeInfo, value: String): WheelOutcome {
        val startBox = Rect().also(start::getBoundsInScreen)
        if (startBox.isEmpty) return outcome(false, null, null, "none", "not_a_wheel")
        val wheel = scrollableWheel(start, startBox)
        val col = columnOf(start, startBox, wheel)
        var now = read(col)
        val from = now.current
        if (from == null && now.items.size < 2) {
            val scrollable = wheel != null || start.isScrollable
            return outcome(false, null, null, "none", if (scrollable) "unknown_value" else "not_a_wheel")
        }
        if (WheelMath.matches(from, value)) return outcome(true, from, from, "none")

        // 1. 直接入力
        editableOf(wheel ?: start)?.let { field ->
            if (typeInto(field, value)) {
                now = settled(col)
                if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "text")
            }
        }

        // 2. バリアフリースクロール
        if (wheel != null && canStep(wheel)) {
            val steps = WheelSteps(value, cap = MAX_STEPS)
            var stopped: String? = null
            var rechecks = 0
            loop@ while (true) {
                when (val s = steps.next(now.current, now.texts)) {
                    WheelSteps.Step.Done -> {
                        // ターゲットの読み取り時にスクロール ホイールがまだ動いている可能性があります。停止して再度確認してください。正しくない場合は、スクロールを続けてください。
                        now = settled(col)
                        if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "actions")
                        if (++rechecks > MAX_RECHECKS) { stopped = "not_reached"; break@loop }
                    }
                    is WheelSteps.Step.Stop -> { stopped = s.reason; break@loop }
                    is WheelSteps.Step.Act -> {
                        val action = if (s.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        var done = 0
                        repeat(s.count) { if (wheel.performAction(action)) done++ }
                        attempts += s.count
                        quiet(STEP_QUIET_MS, STEP_CAP_MS)
                        wheel.refresh()
                        now = settled(col)
                        // まったく転がりませんでした: こちら側は終了です (または、こちら側はロールできません)
                        if (done == 0 && !WheelMath.matches(now.current, value)) { stopped = "at_end"; break@loop }
                    }
                }
            }
            if (stopped == "at_end" || stopped == "unknown_value") return outcome(false, now.current, from, "actions", stopped)
        }

        // 3.ドラッグ
        return dragTo(col, value, from, now)
    }

    private suspend fun dragTo(col: Column, value: String, from: String?, first: Reading): WheelOutcome {
        var now = first
        val plan = WheelDrags(value, rowHeight = col.rowHeight)
        var dragged = 0
        var rechecks = 0
        while (true) {
            when (val s = plan.next(now.texts, now.selected, now.items.map { it.centerY }, dragged)) {
                WheelDrags.Step.Done -> {
                    // 停止して再度確認してください (ホイールを放した後も、ホイールは最も近いグリッドに張り付いたままになります)
                    now = settled(col)
                    if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "drag")
                    if (++rechecks > MAX_RECHECKS) return outcome(false, now.current, from, "drag", "not_reached")
                }
                is WheelDrags.Step.Stop -> return outcome(false, now.current, from, "drag", s.reason)
                is WheelDrags.Step.Drag -> {
                    dragged = dragSteps(col, now, s.steps, plan.itemHeight, s.boost, s.slow)
                        ?: return outcome(false, now.current, from, "drag", "not_reached")
                    now = settled(col)
                }
            }
        }
    }

    /**
     * この列で [steps] をドラッグし (正の数値は前に進むことを意味します)、グリッドごとに [itemHeight] を乗算し、さらに [boost] を乗算します。指が実際に歩いたピクセルを返します (上が正)、
     * は null になります。列の水平方向の中央に指を置き、上にドラッグして列の下部から開始し、下にドラッグして列の上部から開始すると、列をドラッグすることができます（指を離さずに、
     * スクロール ホイールは動きに追従します)、画面内に固定されているだけです。
     */
    private suspend fun dragSteps(col: Column, r: Reading, steps: Int, itemHeight: Int, boost: Double, slow: Boolean): Int? {
        val h = itemHeight.coerceAtLeast(1)
        val x = r.items.map { it.centerX }.sorted().getOrNull(r.items.size / 2) ?: (col.left + col.right) / 2
        val top = r.items.minOfOrNull { it.centerY - h / 2 } ?: (col.mid - h / 2)
        val bottom = r.items.maxOfOrNull { it.centerY + h / 2 } ?: (col.mid + h / 2)
        val maxDrag = maxOf(bottom - top - h, h * MIN_DRAG_ROWS)
        var dragged = 0
        for (piece in WheelMath.drags(steps, h, maxDrag)) {
            val len = maxOf((abs(piece) * boost).roundToInt(), MIN_DRAG_PX)
            val up = piece > 0
            val y0 = (if (up) bottom - h / 2 else top + h / 2).coerceIn(maxOf(top + 1, 1), minOf(bottom - 1, height - 2).coerceAtLeast(maxOf(top + 1, 1)))
            val y1 = (if (up) y0 - len else y0 + len).coerceIn(1, height - 2)
            if (!drag(listOf(x.coerceIn(0, width - 1) to y0, x.coerceIn(0, width - 1) to y1), slow)) return null
            attempts++
            dragged += y0 - y1
            quiet(DRAG_QUIET_MS, DRAG_CAP_MS)
        }
        return dragged
    }

    /** スクロール ホイールの入力ボックスに値を書き込み、確認します (フォーカスが失われると、元のスクロール ホイールは書き込まれた値に従って設定されます)。したがって、ポップアップ入力メソッドは廃止されます。 */
    private suspend fun typeInto(field: AccessibilityNodeInfo, value: String): Boolean {
        val imeBefore = imeShown()
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val set = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        attempts++
        field.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        quiet(STEP_QUIET_MS, STEP_CAP_MS)
        if (!imeBefore && imeShown()) {
            back()
            quiet(STEP_QUIET_MS, STEP_CAP_MS)
        }
        return set
    }

    // ---------- 読む ----------

    /**
     * 停止後の読み取り値: スクロールしてドラッグした後、ホイールはしばらく回転し、最も近いフレームに吸着します (元のデジタル スクロール ホイールの 1 フレームのアニメーションは約 0.3 秒です)。
     * は必ずしもアクセシビリティ イベントを引き起こすわけではありません。同じ値が [SETTLE_MS] ごとに 2 回連続して読み取られると停止します。最大 [MAX_SETTLE_READS] 回読み込むことができます。
     */
    private suspend fun settled(col: Column): Reading {
        var last = read(col)
        repeat(MAX_SETTLE_READS - 1) {
            delay(SETTLE_MS)
            val again = read(col)
            if (again.current == last.current) return again
            last = again
        }
        return last
    }

    private suspend fun read(col: Column): Reading {
        col.root.refresh()
        col.root.getBoundsInScreen(col.box)
        val found = treeItems(col)
        if (found.isNotEmpty()) {
            val h = WheelMath.itemHeight(found.map { it.centerY })
            val anchor = WheelMath.selectedIndex(found, col.mid, h)
            val items = if (anchor >= 0) WheelMath.column(found, anchor) else found
            val i = WheelMath.selectedIndex(items, col.mid, WheelMath.itemHeight(items.map { it.centerY }) ?: h)
            if (i >= 0) return Reading(items[i].text, items, i)
            // どの項目も中心線をカバーしていません。中央のグリッドが報告されていない可能性があります (上部と下部のグリッドのみが報告されます)。すぐ上と下の 2 つの項目を押して、中央のグリッドを押し出します。
            // は押し出せない場合は中心線に近い項目に戻ります。
            val below = items.indexOfFirst { it.centerY > col.mid }
            val mid = (if (below > 0) WheelMath.between(items[below - 1].text, items[below].text) else null) ?: run {
                val j = WheelMath.selected(items.map { it.centerY }, col.mid, WheelMath.itemHeight(items.map { it.centerY }) ?: h)
                return Reading(items.getOrNull(j)?.text, items, j)
            }
            val withMid = items.toMutableList().apply {
                add(below, WheelMath.Item(mid, (items[below - 1].centerX + items[below].centerX) / 2, col.mid, selected = true))
            }
            return Reading(mid, withMid, below)
        }
        // 私が描いたスクロール ホイール: この列の単語を認識します
        val lines = ocr().orEmpty().mapNotNull { l ->
            val cx = px((l.bounds[0] + l.bounds[2]) / 2, width)
            val cy = px((l.bounds[1] + l.bounds[3]) / 2, height)
            if (!col.box.contains(cx, cy) || cx !in col.left..col.right || l.text.isBlank() || l.text.trim().length > MAX_ITEM_CHARS) null
            else WheelMath.Item(l.text.trim(), cx, cy)
        }.sortedBy { it.centerY }
        val i = WheelMath.selected(lines.map { it.centerY }, col.mid, WheelMath.itemHeight(lines.map { it.centerY }))
        return Reading(lines.getOrNull(i)?.text, lines, i)
    }

    /** この列 (数レベル下にあります) に表示される単語を含むいくつかの項目 (上から下)。同じ項目は繰り返されません。 */
    private fun treeItems(col: Column): List<WheelMath.Item> {
        val out = mutableListOf<WheelMath.Item>()
        val r = Rect()
        fun add(n: AccessibilityNodeInfo): Boolean {
            val label = itemLabel(n) ?: return false
            n.getBoundsInScreen(r)
            if (r.isEmpty || !col.box.contains(r.centerX(), r.centerY()) || r.centerX() !in col.left..col.right) return false
            if (out.none { it.text == label && abs(it.centerY - r.centerY()) < 4 }) out += WheelMath.Item(label, r.centerX(), r.centerY(), n.isSelected, r.height())
            return true
        }
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                if (!add(c) && depth < MAX_DOWN) walk(c, depth + 1)
            }
        }
        if (col.root.childCount == 0) add(col.root) else walk(col.root, 0)
        return out.sortedBy { it.centerY }
    }

    /** アイテムの名前。システムによって個別に報告されるステータス (「selected」などの stateDescription) が名前に綴られている場合は、最初にそれを削除してください。長すぎるものはカウントされません。 */
    private fun itemLabel(n: AccessibilityNodeInfo): String? {
        var label = ScreenReader.ownLabel(n) ?: return null
        val state = n.stateDescription?.toString()?.trim()
        if (!state.isNullOrEmpty() && label != state && label.contains(state)) {
            label = label.replace(state, "").trim { it.isWhitespace() || !it.isLetterOrDigit() }.ifEmpty { label }
        }
        return label.takeIf { it.length <= MAX_ITEM_CHARS }
    }

    // ---------- ローラーの識別 ----------

    /**
     * 障害物なしでスクロールできるローラー: 指定されたノード自体、数レイヤー上にスクロールできるもの (指定された項目がローラーの 1 つの場合)、または下に見つかったもの。
     * 上で見つけたものは、指定された項目の中央を水平にカバーしているはずです (ページ全体のスクロール領域は、いくつかの並んだローラーで覆われており、スクロールには使用できません)。どれもヌルではありません。
     */
    private fun scrollableWheel(start: AccessibilityNodeInfo, startBox: Rect): AccessibilityNodeInfo? {
        if (scrollable(start)) return start
        val r = Rect()
        var p = start.parent
        var depth = 0
        while (p != null && depth++ < MAX_UP) {
            if (scrollable(p)) {
                p.getBoundsInScreen(r)
                return p.takeIf { r.centerX() in startBox.left..startBox.right }
            }
            p = p.parent
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(start) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 60) {
            val c = queue.removeFirst()
            if (scrollable(c)) return c
            for (i in 0 until c.childCount) c.getChild(i)?.let(queue::add)
        }
        return null
    }

    /**
     * 読み取られる列。ローラー (転がすことができる) またはいくつかのアイテムが入った状態で提供されます。それだけです。与えられるのは 1 つのアイテムです (私がよく描くローラーは次のようになります)。
     * 少なくとも 3 つのアイテムが水平方向に並んでいる最も近いレイヤーを検索します。横方向に並んだ項目のみが認識されるため、横にあるスクロール ホイールは含まれません。
     */
    private fun columnOf(start: AccessibilityNodeInfo, startBox: Rect, wheel: AccessibilityNodeInfo?): Column {
        val ownHeight = startBox.height().coerceAtLeast(1)
        if (wheel != null && wheel != start && Rect().also(wheel::getBoundsInScreen).let { it.width() <= startBox.width() * 2 }) {
            // は、スクロール ホイールに 1 つの項目を表示します。スクロール ホイールの幅はこの列のみです。スクロール ホイール全体を読んでください。
            val box = Rect().also(wheel::getBoundsInScreen)
            return Column(wheel, box, box.left, box.right, startBox.centerY(), ownHeight)
        }
        if (start.childCount > 0) {
            val own = Column(start, Rect(startBox), startBox.left, startBox.right, startBox.centerY(), startBox.height() / ASSUMED_ROWS)
            if (treeItems(own).size >= 2) return own
        }
        var best: Column? = null
        var p = start.parent
        var depth = 0
        while (p != null && depth++ < MAX_UP) {
            val box = Rect().also(p::getBoundsInScreen)
            val c = Column(p, box, startBox.left, startBox.right, startBox.centerY(), ownHeight)
            val n = treeItems(c).size
            if (n >= 3) return c
            if (n >= 2 && best == null) best = c
            p = p.parent
        }
        return best ?: Column(start, Rect(startBox), startBox.left, startBox.right, startBox.centerY(), ownHeight)
    }

    private fun scrollable(n: AccessibilityNodeInfo): Boolean = n.isScrollable || canStep(n)

    private fun canStep(n: AccessibilityNodeInfo): Boolean = n.actionList.any {
        it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    }

    /** スクロール ホイール自体、または内側のレイヤーの入力ボックス (元のデジタル スクロール ホイールの中央のボックス)。 */
    private fun editableOf(wheel: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (wheel.isEditable) return wheel
        for (i in 0 until wheel.childCount) {
            val c = wheel.getChild(i) ?: continue
            if (c.isEditable && c.isVisibleToUser) return c
        }
        return null
    }

    private fun px(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt()

    private fun outcome(reached: Boolean, value: String?, from: String?, method: String, error: String? = null) =
        WheelOutcome(reached, WheelMath.clean(value), WheelMath.clean(from), attempts, method, if (reached) null else error ?: "not_reached")

    private companion object {
        const val MAX_STEPS = 80
        const val MAX_UP = 5
        const val MAX_DOWN = 4
        /** アイテムの名前にはステータス ワード (「選択済み、21」「選択済み、21」) が含まれることが多いため、よりリラックスしてください。 */
        const val MAX_ITEM_CHARS = 24
        /** 複数のグリッドで過大評価を決定できない場合は、1 画面と 5 つの要素に従って計算します。 */
        const val ASSUMED_ROWS = 5
        /** 一度に少なくともこの数のセルをドラッグできます (リストに 1 つまたは 2 つの項目しか読み込まれない場合でもドラッグできます)、少なくともこの数のピクセルをドラッグできます (ドラッグが短すぎるとクリックとみなされます)。 */
        const val MIN_DRAG_ROWS = 3
        const val MIN_DRAG_PX = 48
        /** 停止判定：長時間２回読み込んでも値が同じ。多くても数回読むことができます。ターゲットを読んだ後に停止しますが、間違っており、継続できるのはせいぜい数回です。 */
        const val SETTLE_MS = 350L
        const val MAX_SETTLE_READS = 5
        const val MAX_RECHECKS = 2
        const val STEP_QUIET_MS = 150L
        const val STEP_CAP_MS = 700L
        const val DRAG_QUIET_MS = 300L
        const val DRAG_CAP_MS = 1_500L
    }
}
