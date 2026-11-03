package com.wangzan101.mika.core.cap.phone

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ホイール セレクターのアルゴリズム (一度に 1 フレーム、日付、領域などを回転します) は、回転方法とは何の関係もありません。値を認識し、セルの方向と数を計算し、1 つのセルの高さを推定します。
 * ポートは、モデルを 1 つずつ表示する必要がなく、1 つのツール呼び出しでそのまま配置されます。
 *
 * 「前方」は、ローラーの値が後方に移動することを意味します (次の項目を中央に回すと、通​​常は数字が大きくなります)。大きくなるかどうかは読み戻した値によって補正されます。
 */
object WheelMath {
    private val ONLY_NUMBER = Regex("""^-?\d{1,6}$""")
    private val LEADING_NUMBER = Regex("""^\D{0,3}?(\d{1,6})(?!\d)""")
    private val DIGITS = Regex("""(?<!\d)\d{1,6}(?!\d)""")
    /** 項目のテキスト内の値、ステータス単語 (「選択済み」、「選択済み」...)、および単位を区切る句読点。 */
    private val SEPARATORS = Regex("""[,，、:：;；|/()（）\[\]【】<>《》·•]+""")

    /** テキストを比較するには: 空白を削除し、全角の数字を半角に置き換え、大文字と小文字を無視します。 */
    fun normalize(s: String): String = buildString {
        for (c in s.trim()) when {
            c.isWhitespace() -> Unit
            c in '０'..'９' -> append('0' + (c - '０'))
            else -> append(c.lowercaseChar())
        }
    }

    /**
     * によって表示されるテキスト内の整数: 「09」→ 9、「9 時」/「9 時」→ 9、「2026」→ 2026、「第 3 週」→ 3;
     * アクセシブルな名前には、値の前後にステータス単語 (「選択済み、21」、「選択済み、21」、「21 (選択済み)」) が含まれることがよくあります。これは、段落全体に数字が 1 つしかない場合に当てはまります。
     * 複数の数字がある場合、先頭近くの最初の数字が取得されます (先頭に 3 文字以上ある場合は null)。数値がない場合は null です。
     */
    fun number(s: String?): Int? {
        val t = s?.let(::normalize)?.ifEmpty { null } ?: return null
        if (ONLY_NUMBER.matches(t)) return t.toIntOrNull()
        DIGITS.findAll(t).toList().singleOrNull()?.let { return it.value.toIntOrNull() }
        return LEADING_NUMBER.find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 句読点に従って切り取られた複数のセグメント ([normalize] に従って処理): 「選択済み、21」 → [選択済み、21]。 */
    fun segments(s: String): List<String> = normalize(s).split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * モデルに報告される値: 名前にステータス単語が含まれる場合、値セグメントのみが残ります (「選択済み、21」→「21」、「選択済み、21」→「21」)。
     * は、番号が含まれる唯一の段落を切り取ってそのままにすることはできません (テキスト値は、どの段落がステータス ワードであるかを推測しません)。
     */
    fun clean(label: String?): String? {
        val t = label?.trim()?.ifEmpty { null } ?: return null
        val parts = t.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return t
        return parts.filter { p -> p.any { it.isDigit() || it in '０'..'９' } }.singleOrNull() ?: t
    }

    /** ターゲット自体は数字 (「9」、「09」、「2026」) であり、数字を含むテキストではありません。 */
    fun numericTarget(target: String): Int? = normalize(target).takeIf(ONLY_NUMBER::matches)?.toIntOrNull()

    /**
     * 現時点でスクロールホイールに表示されている [shown] は [target] です。 対象が純粋な数字の場合は、数字の比率を押します (「09」は「9」、「40 ポイント」は「40」、
     * "選択済み、21" は "21");それ以外の場合、テキストは同じであるか、表示される (または句読点に従って切り取られたセクション) がターゲットで開始/終了します (「北京市」と「北京」)。
     */
    fun matches(shown: String?, target: String): Boolean {
        val s = shown?.let(::normalize)?.ifEmpty { null } ?: return false
        val t = normalize(target).ifEmpty { return false }
        numericTarget(target)?.let { return number(s) == it }
        fun affix(x: String) = x == t || x.startsWith(t) || x.endsWith(t)
        return affix(s) || segments(s).any(::affix)
    }

    /**
     * いくつかの項目を上から下に見てループの長さを推定します。「下に行くほど小さくなる」が数値の中に 1 回出現します (...58 59 00 01...)。ループの長さ = ブレークポイント前の最大値 − ブレークポイント後の最小値 + 1。
     * は、ループ (数値ではなくすべての増分、少なすぎる) が null であることを認識できません。
     */
    fun modulus(visible: List<String>): Int? {
        val n = visible.map { number(it) ?: return null }
        if (n.size < 2) return null
        val drops = (1 until n.size).filter { n[it] < n[it - 1] }
        if (drops.size != 1) return null
        val i = drops.single()
        return (n.subList(0, i).max() - n.subList(i, n.size).min() + 1).takeIf { it >= 2 }
    }

    /** [current] から [target] まで、数字が大きい側に何グリッド移動する必要があります (負の数字は小さい側に移動します): サイクル長 [modulus] がわかっている場合は、最も近い側を選択します。 */
    fun distance(current: Int, target: Int, modulus: Int?): Int {
        val d = target - current
        if (modulus == null || modulus < 2) return d
        val m = ((d % modulus) + modulus) % modulus
        return if (m <= modulus / 2) m else m - modulus
    }

    /** という数値は、[before] から [after] まで何グリッドになりますか (ループの長さがわかっている場合、59→0 は +1 ですが、-59 ではありません)。数値でない場合は null です。 */
    fun moved(before: String?, after: String?, modulus: Int?): Int? {
        val b = number(before) ?: return null
        val a = number(after) ?: return null
        return distance(b, a, modulus)
    }

    /** 1 グリッドの高さ: 隣接する項目の中心間の距離の中央値を取得します。項目が 2 つ未満で距離が小さすぎる場合は、null になります。 */
    fun itemHeight(centers: List<Int>): Int? {
        val sorted = centers.sorted()
        val gaps = (1 until sorted.size).map { sorted[it] - sorted[it - 1] }.filter { it > 2 }
        if (gaps.isEmpty()) return null
        return gaps.sorted()[gaps.size / 2]
    }

    /**
     * 選択された項目: 選択済みとマークされた項目は 1 つだけ ([Item.selected]、バリアフリー isSelected)、それだけです。各アイテムに独自の高さがある場合、
     * は、上下の範囲が正中線 [centerY] をカバーするアイテムです (カバーされていないのは -1 です。中央の正方形は報告されず、上下の隣接範囲は充電に使用されません)。
     * それ以外の場合は、[selected] を押して中心線を見つけます。
     */
    fun selectedIndex(items: List<Item>, centerY: Int, itemHeight: Int?): Int {
        items.indices.filter { items[it].selected }.singleOrNull()?.let { return it }
        if (items.isNotEmpty() && items.all { it.height > 0 }) {
            return items.indices.filter { abs(items[it].centerY - centerY) * 2 <= items[it].height }
                .minByOrNull { abs(items[it].centerY - centerY) } ?: -1
        }
        return selected(items.map { it.centerY }, centerY, itemHeight)
    }

    /**
     * 中心線上のグリッドはレポートされません (一部のローラーでは、上部と下部のグリッドのみがレポートされます)。すぐ上と下の 2 つの項目は時間を数えています。中央のグリッドのテキストは次のとおりです。
     * と 2 の違いは、真ん中に挟まれた数字です。差がはるかに大きい場合は、一度回転されたインターフェイスのみが認識されます (59 と 1 の中間は 0、22 と 0 の中間は 23、12 と 2 の中間は 1、
     * 11 と 1 は 12、1 と 59 は 0)。 12 グリッドが報告されていない場合、1 ～ 12 の時針は 11 と 1 になります。 「小さい方マイナス1」を押して0にすることはできません。
     * (このローラーには 0 はありません);上下逆に並べると（上に1、下に11）、12になります。書き方は上記の通りです（単位。上下に0がある場合は0を追加します）。推定できない場合は null になります。
     */
    fun between(above: String, below: String): String? {
        val a = number(above) ?: return null
        val b = number(below) ?: return null
        val mid = when {
            abs(b - a) == 2 -> (a + b) / 2
            // 1–12:11 で、1 はその間の 12 です。 「小さい方から 1 を引いた値」より前にランク付けする必要があります。そうでない場合は 0 にプッシュされます。
            (a == 11 && b == 1) || (a == 1 && b == 11) -> 12
            b < a && b <= 2 -> if (b >= 1) b - 1 else a + 1
            a < b && a <= 2 -> if (a >= 1) a - 1 else b + 1
            else -> return null
        }
        if (mid < 0) return null
        val token = DIGITS.find(above.trim())?.value ?: return null
        val other = DIGITS.find(below.trim())?.value.orEmpty()
        // ゼロ パディングされたローラー (09、01 など、上下の項目のいずれかが 0 で始まる) は引き続き同じ幅にパディングされます。
        val padded = listOf(token, other).any { it.length > 1 && it.startsWith('0') }
        return above.trim().replaceFirst(token, if (padded) mid.toString().padStart(token.length, '0') else mid.toString())
    }

    /**
     * 上から下にいくつかの項目のうち、項目 [anchor] と同じ間隔で並んでいるセクション、つまり同じブロック内のスクロール ホイールの上下のタイトルとボタンは含まれません。
     * 1 つのグリッドの高さは、そのグリッドと上下に隣接するアイテムの間の距離 (いずれか小さい方) に基づいて計算されます。アイテム数が 3 つ未満で 1 つのブロックの高さが推定できない場合は、そのまま返却します。
     */
    fun column(items: List<Item>, anchor: Int): List<Item> {
        if (items.size < 3 || anchor !in items.indices) return items
        val near = listOfNotNull(
            items.getOrNull(anchor - 1)?.let { items[anchor].centerY - it.centerY },
            items.getOrNull(anchor + 1)?.let { it.centerY - items[anchor].centerY },
        ).filter { it > 2 }
        val h = near.minOrNull() ?: return items
        fun even(gap: Int) = gap > h * 0.4 && gap < h * 1.8
        var lo = anchor
        var hi = anchor
        while (lo > 0 && even(items[lo].centerY - items[lo - 1].centerY)) lo--
        while (hi < items.lastIndex && even(items[hi + 1].centerY - items[hi].centerY)) hi++
        return items.subList(lo, hi + 1)
    }

    /** 中心線 [centerY] の項目の下付き文字: 中心線に最も近い中心。正方形の半分を超える距離 (中心線に面していない) は -1 です。 */
    fun selected(centers: List<Int>, centerY: Int, itemHeight: Int?): Int {
        val best = centers.indices.minByOrNull { abs(centers[it] - centerY) } ?: return -1
        if (itemHeight != null && abs(centers[best] - centerY) > itemHeight * 0.75) return -1
        return best
    }

    /**
     * 到着するまでに前方に曲がる必要があるスペースの数 (負の数は後方に戻ります): ターゲットが表示されるいくつかのアイテムの中にある場合、計算は位置の差に基づいて行われます (以下は前方)。ターゲットと選択されたものが両方とも数値の場合、数値の差に基づいて計算されます
     * (ループが見える場合は、ループに近づきます。見えない場合は、前に見た [knownModulus] を使用します。[increasingForward] が false の場合、順方向が小さくなります);
     * どれもだめな場合（テキスト一覧、対象が目の前にない）は null。
     */
    fun stepsTo(visible: List<String>, selectedIndex: Int, target: String, increasingForward: Boolean = true, knownModulus: Int? = null): Int? {
        if (selectedIndex !in visible.indices) return null
        visible.indexOfFirst { matches(it, target) }.takeIf { it >= 0 }?.let { return it - selectedIndex }
        val t = numericTarget(target) ?: return null
        val cur = number(visible[selectedIndex]) ?: return null
        val d = distance(cur, t, modulus(visible) ?: knownModulus)
        return if (increasingForward) d else -d
    }

    /**
     * [steps] マス回すのにドラッグする距離（ピクセル、正数が指を上へドラッグ = 前進）、1マスは [itemHeight]; 1回のドラッグは [maxDrag] を超えず、超える分は複数回に分ける。
     * 各回のドラッグ距離を返す。
     */
    fun drags(steps: Int, itemHeight: Int, maxDrag: Int): List<Int> {
        if (steps == 0 || itemHeight <= 0) return emptyList()
        val perDrag = (maxDrag / itemHeight).coerceAtLeast(1)
        val sign = if (steps > 0) 1 else -1
        val out = mutableListOf<Int>()
        var left = abs(steps)
        while (left > 0) {
            val n = minOf(left, perDrag)
            out += sign * n * itemHeight
            left -= n
        }
        return out
    }

    /** ホイールの中で見えている1項目: テキストと中心（ピクセル）; [selected] はユーザー補助が選択中としているか; [height] はその項目自身の高さ（不明なら 0）。 */
    data class Item(val text: String, val centerX: Int, val centerY: Int, val selected: Boolean = false, val height: Int = 0)

    /**
     * 垂直方向に転がすことができるコンテナがローラーのように見える場合、私はそれを識別するよりも見逃したくなります。3 ～ 9 個の項目があり、各項目は短い値 ([MAX_ITEM_CHARS] 文字以下) であり、互いに異なります。
     * はすべてコンテナの水平方向の中央にあり (偏差は幅の 15% 未満)、上から下まで等間隔に配置されています (最大間隔は最小間隔の 1.5 倍未満)。
     * 設定ページの左行にある短いラベルとチャット リストは、それとはまったく異なります。
     */
    fun looksLikeWheel(items: List<Item>, left: Int, right: Int): Boolean {
        if (items.size !in 3..9 || right <= left) return false
        val texts = items.map { normalize(it.text) }
        if (texts.any { it.isEmpty() || it.length > MAX_ITEM_CHARS } || texts.toSet().size != texts.size) return false
        val mid = (left + right) / 2.0
        if (items.any { abs(it.centerX - mid) > (right - left) * 0.15 }) return false
        val ys = items.map { it.centerY }
        if (ys.zipWithNext().any { (a, b) -> b <= a }) return false
        val gaps = ys.zipWithNext { a, b -> b - a }
        return gaps.max() < gaps.min() * 1.5
    }

    private const val MAX_ITEM_CHARS = 8

    /** は、[dragged] ピクセルをドラッグして、[moved] グリッドを実際に回転させました: 1 つのグリッドの実際の高さ。 null には変換されません。 */
    fun calibrated(dragged: Int, moved: Int): Int? =
        if (moved == 0) null else abs((dragged.toDouble() / moved).roundToInt()).takeIf { it > 2 }
}

/**
 * バリアフリーの「前方/後方ロール」に従ってホイールを回すときの次のステップ: 各バッチの後に、読み取った値を渡すと、どちらの方向にさらに数回回転するか、停止するかを教えてくれます。
 * は必ずしも一度に 1 セルずつ回転するわけではない (リストは一度に 1 ページずつスクロールします)。そのため、各バッチの後に読み取られる値に従って再計算されます。
 * - ターゲットは数字です。方向を決定し、数字の差に応じてカウントダウンします。初めて読み返すときに、「進む」が大きくなるか小さくなるかを確認します。値が動かない場合は、こちら側が終端に到達し、ターゲットが範囲内にないことを意味します。停止します。
 *   はターゲットを数回前後に横切り (一度に 1 マス以上、停止は正確ではありません)、停止し、キャリブレーションのためにドラッグに任せます。
 * - ターゲットはテキストです。それが目の前にある場合は、位置の差を押します。そこにない場合は、1 つずつ前にスワイプし、最後に到達したら、両端に到達するまで逆にスワイプします。
 */
class WheelSteps(private val target: String, private val cap: Int = 80, private val burst: Int = 5) {
    sealed interface Step {
        data object Done : Step
        /** は [forward] に進み、[count] にロールします。 */
        data class Act(val forward: Boolean, val count: Int) : Step
        /**
         * Stop: [reason] は at_end (終わり、ターゲットはこちら側にありません)、not_moving (サイクルホイールが回転していません)、cap (回転回数が多すぎます)、
         * オーバーシュート (停止できない)、unknown_value (値を読み取ることができません)。
         */
        data class Stop(val reason: String) : Step
    }

    /** はこれまでに数回ロールしました。 */
    var actions: Int = 0
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var increasingForward = true
    private var calibrated = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastAct: Step.Act? = null
    private var reversals = 0
    private var scanForward = true
    private var scanReversed = false

    /** 前のバッチの後: 選択された値 [current] といくつかの表示項目 [visible] (上から下)。初めて調整したときは転がりませんでした。 */
    fun next(current: String?, visible: List<String> = emptyList()): Step {
        if (WheelMath.matches(current, target)) return Step.Done
        if (current == null) return Step.Stop("unknown_value")
        WheelMath.modulus(visible)?.let { modulus = it }
        val prev = lastAct
        val unchanged = prev != null && current == last
        if (actions >= cap) return Step.Stop("cap")
        if (numeric != null && WheelMath.number(current) != null) {
            if (prev != null) {
                // 値が動かない: 循環ホイールに終わりがないことがわかります。それは、今回がうまくいかないからであり、終わりではないからです。
                if (unchanged) return Step.Stop(if (modulus != null) "not_moving" else "at_end")
                val moved = WheelMath.moved(last, current, modulus)
                if (!calibrated && moved != null && moved != 0) {
                    calibrated = true
                    if ((moved > 0) != prev.forward) increasingForward = false
                }
            }
            val d = WheelMath.distance(WheelMath.number(current)!!, numeric, modulus).let { if (increasingForward) it else -it }
            val forward = d > 0
            val turned = prev != null && forward != prev.forward
            if (turned && ++reversals > 3) return Step.Stop("overshoot")
            // 目標を越えて歩いて戻りました: ステップバイステップ
            val n = if (turned) 1 else minOf(abs(d), burst)
            return act(forward, n.coerceAtMost(cap - actions), current)
        }
        val sel = visible.indexOfFirst { it == current }
        val at = visible.indexOfFirst { WheelMath.matches(it, target) }
        if (sel >= 0 && at >= 0) return act(at > sel, minOf(abs(at - sel), burst, cap - actions), current)
        if (unchanged) {
            if (scanReversed) return Step.Stop("at_end")
            scanReversed = true
            scanForward = !scanForward
        }
        return act(scanForward, 1, current)
    }

    private fun act(forward: Boolean, count: Int, current: String?): Step {
        val a = Step.Act(forward, count.coerceAtLeast(1))
        last = current
        lastAct = a
        actions += a.count
        return a
    }
}

/**
 * スクロール ホイールをドラッグするときの次のステップ (アプリ自体によって描画され、障害物がないとスクロールできません): ドラッグするたびに手を入力すると、列を読み戻すと、あと何スペースドラッグするか、ドラッグする方法、または停止するかが表示されます。
 * - [WheelMath.stepsTo] を押して番号を固定します (番号の差を押してループが近づいている側を確認し、前に見たループの長さを覚えておいてください)。初めて回すときは「ドラッグアップ」を見てください
 *   が大きくなるか小さくなるかにかかわらず、実際に回転するグリッドの数に応じて 1 つのグリッドの高さを調整します。
 * - 値をドラッグしても、それが終了であることを意味するわけではありません。ループが表示されず、移動したい方向に項目がない場合にのみ、終了 (at_end) になります。そうしないと、今回は機能しません。
 *   次回速度を変更します (フレーム差が 1 つだけの場合は、さらにドラッグします)。[maxStill] は移動せず、not_moving を報告します。
 * - テキスト リスト。ターゲットは目の前にありません。一度に表示されるいくつかの項目をスクロールして片側にスワイプし、最後に到達したら反転し、両端で停止します。
 */
class WheelDrags(private val target: String, rowHeight: Int, private val maxMoves: Int = 5, private val maxStill: Int = 3) {
    sealed interface Step {
        data object Done : Step
        /** は [steps] グリッド上をドラッグし (正の指が上を向いている = 前方)、距離に [boost] を掛けます。 [slow] 震えずにゆっくりとドラッグします。それ以外の場合は素早くスワイプします。 */
        data class Drag(val steps: Int, val boost: Double, val slow: Boolean) : Step
        /** 停止: [reason] は at_end、not_moving、not_reached (数ラウンド後に到着しないか、数値に従って計算されましたがこの値を持っていません)、unknown_value です。 */
        data class Stop(val reason: String) : Step
    }

    /** 1 つのグリッド (ピクセル) の高さ: 最初に取得したいくつかの値に従って推定し、次に実際に回転したグリッドの数に従って回転および校正します。 */
    var itemHeight: Int = rowHeight.coerceAtLeast(1)
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var started = false
    private var increasingForward = true
    private var directionKnown = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastDrag: Step.Drag? = null
    private var lastScanning = false
    private var scanForward = true
    private var scanReversed = false
    private var moves = 0
    private var still = 0

    /**
     * ドラッグ後: この列のいくつかの項目を上から下に [items]、各中心の縦座標 [centers]、選択した項目の添字 [selected] (見つからない場合は -1)、
     * と指が実際に歩いたピクセル [dragged] (上は正です。最初の調整中にドラッグされていないため、0 です)。
     */
    fun next(items: List<String>, selected: Int, centers: List<Int> = emptyList(), dragged: Int = 0): Step {
        val current = items.getOrNull(selected)
        if (WheelMath.matches(current, target)) return Step.Done
        if (items.isEmpty()) return Step.Stop("unknown_value")
        if (!started) {
            started = true
            WheelMath.itemHeight(centers)?.let { itemHeight = it }
        }
        WheelMath.modulus(items)?.let { modulus = it }
        val prev = lastDrag
        if (prev != null) {
            if (current != last) {
                still = 0
                moves++
                val moved = WheelMath.moved(last, current, modulus)
                if (moved != null && moved != 0) {
                    if (!directionKnown && numeric != null) {
                        directionKnown = true
                        if ((moved > 0) != (dragged > 0)) increasingForward = false
                    }
                    WheelMath.calibrated(dragged, moved)?.let { itemHeight = it }
                }
            } else if (atEnd(items, selected, forward = prev.steps > 0)) {
                if (!lastScanning || scanReversed) return Step.Stop("at_end")
                scanReversed = true
                scanForward = !scanForward
            } else {
                still++
            }
        }
        if (still >= maxStill) return Step.Stop("not_moving")
        if (moves >= maxMoves) return Step.Stop("not_reached")
        val planned = WheelMath.stepsTo(items, selected, target, increasingForward, modulus)
        // 計算によると、到着しましたが一致しません: 値がスクロール ホイール上にありません (たとえば、0 ～ 59 で 75 を見つけます)
        if (planned == 0) return Step.Stop("not_reached")
        lastScanning = planned == null
        val steps = planned ?: ((items.size - 1).coerceAtLeast(1) * if (scanForward) 1 else -1)
        last = current
        // 以前のものは機能しませんでした。速度を変更して再試行してください。 1 フレームだけ短いものをドラッグし、さらにドラッグします (長すぎる場合は、頭を回転させてキャリブレーションを偏らせないように、ドラッグしないでください)。
        val boost = if (abs(steps) <= 1) 1.0 + BOOST * still else 1.0
        return Step.Drag(steps, boost, slow = still % 2 == 0).also { lastDrag = it }
    }

    /** ループが見えません。行きたい方向（下が前方）への項目がありません。 */
    private fun atEnd(items: List<String>, selected: Int, forward: Boolean): Boolean {
        if (modulus != null || items.size < 2 || selected !in items.indices) return false
        return if (forward) selected == items.lastIndex else selected == 0
    }

    private companion object {
        const val BOOST = 0.6
    }
}
