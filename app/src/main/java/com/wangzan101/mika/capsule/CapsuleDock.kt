package com.wangzan101.mika.capsule

import com.wangzan101.mika.cap.phone.a11y.OverlayArea
import kotlin.math.roundToInt

enum class DockSide { Left, Right }

/** カプセルはどこに止まりますか: [side] はどちら側にくっつきますか? [fraction] は揺動範囲内の高さの比率（0 が上、1 が下）で、比例して記録し、画面を回転しても元の位置に戻ります。 */
data class CapsuleDock(val side: DockSide = DockSide.Right, val fraction: Float = 0f)

/**
 * カプセル ウィンドウを配置できる範囲はウィンドウ座標 (ピクセル、ウィンドウにはシャドウ用に残されたエッジが含まれます) です。ドッキングすると、ウィンドウの左側は [left]、右側は [right] になります。
 * は、[top]..[bottom] の間にあります (ステータス バー、前髪、下部のジェスチャ バーを避けます)。ドラッグする場合は、[edgeLeft]..[edgeRight]まで左右にドラッグできます。
 * (画面の端に取り付けられたカプセルが表示されます)。
 */
data class DockArea(
    val width: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val edgeLeft: Int, val edgeRight: Int,
) {
    /**
     * 入力メソッドがポップアップすると（画面座標の [imeTop] が一番上）、移動できる範囲はその上になり、表示されている部分はそこから切り離されます。 [gap]; [room] はシャドウ エッジです。
     * がポップアップしない場合 ([imeTop] が null) は変更されません。
     */
    fun above(imeTop: Int?, gap: Int, room: Int): DockArea =
        if (imeTop == null) this else copy(bottom = minOf(bottom, imeTop - gap + room).coerceAtLeast(top))

    companion object {
        /**
         * 画面とシステム バーから計算: ドッキングされたときに表示されるカプセルは、画面の左側と右側から [margin] 離れており、ステータス バーと下部のジェスチャ バーから [gap] 離れています。
         * [room] はウィンドウ内の影のために残された円です。
         */
        fun of(area: OverlayArea, margin: Int, gap: Int, room: Int) = DockArea(
            width = area.width,
            left = area.insetLeft + margin - room,
            top = area.insetTop + gap - room,
            right = area.width - area.insetRight - margin + room,
            bottom = area.height - area.insetBottom - gap + room,
            edgeLeft = area.insetLeft - room,
            edgeRight = area.width - area.insetRight + room,
        )
    }
}

/** ドラッグ、ウェルティング、比例高さ設定用のアルゴリズム (純粋関数、ピクセル)。 */
object CapsuleDocking {
    /** は、[fraction] で押されたときの高さ [h] のウィンドウの上部です。 */
    fun y(fraction: Float, area: DockArea, h: Int): Int = area.top + (fraction.coerceIn(0f, 1f) * range(area, h)).roundToInt()

    /** 上端が [y] にあるときの高さの比率。 */
    fun fraction(y: Int, area: DockArea, h: Int): Float {
        val r = range(area, h)
        return if (r == 0) 0f else ((y - area.top).toFloat() / r).coerceIn(0f, 1f)
    }

    /** 高さ [h] のウィンドウ（カードはカプセルより高い）を [y] から下へ置けないときは上へずらし、ステータスバーと下端は越えない。 */
    fun fitY(y: Int, area: DockArea, h: Int): Int = y.coerceIn(area.top, maxOf(area.top, area.bottom - h))

    /** ドラッグ中のウィンドウの左端: 見えているカプセルが画面端に貼り付くまで。 */
    fun dragX(x: Int, area: DockArea, w: Int): Int = x.coerceIn(area.edgeLeft, maxOf(area.edgeLeft, area.edgeRight - w))

    /** [side] にドッキングしているときのウィンドウの左端。 */
    fun dockedX(side: DockSide, area: DockArea, w: Int): Int = if (side == DockSide.Left) area.left else area.right - w

    /** が [side] にドッキングされている場合のその端からの距離 (ウィンドウが左または右に揃えられている場合は x)。 */
    fun edgeOffset(side: DockSide, area: DockArea): Int = if (side == DockSide.Left) area.left else area.width - area.right

    /**
     * 放します: [x] はウィンドウの左側にあり、[y] はウィンドウの上部にあります。中心線は画面の左半分の左側に接続され、それ以外の場合は右側に接続されます。高さは比例して記録されます。
     */
    fun release(x: Int, y: Int, w: Int, h: Int, area: DockArea): CapsuleDock {
        val side = if (x + w / 2 < area.width / 2) DockSide.Left else DockSide.Right
        return CapsuleDock(side, fraction(fitY(y, area, h), area, h))
    }

    private fun range(area: DockArea, h: Int) = maxOf(0, area.bottom - area.top - h)
}

/**
 * 拡張されたカードを配置する場所とその高さ (純粋な機能、ピクセル): カードは非常に幅が広​​く、左右に中央が配置されており、上下にのみドラッグできます。ダイアログ カードは [maxHeight] に固定されています。タスク カードは内容に応じた高さになります。
 * は最大でも [maxHeight] です。それ以上のものはロールオーバーする必要があります。位置はカードの頂点に従って記録されます。配置可能な範囲([release])の頂点の割合はカプセルとは別に記録されます。
 * の最上部に配置できる範囲は、最も高いカード (ウィンドウの高さ [full]、つまり [maxHeight] に下側のシャドウ エッジを加えたもの) に基づいて計算されるため、高さの異なるカードが同じ割合で最上部に配置されます。
 * カードを交換するときに、上部が動かず、下に伸びたり縮んだりします。下に置けない場合（下部のジェスチャーバー、入力方法）、ちょうどいい位置になるまで上に移動します。画面を回転させると、それに比例して後ろに振り戻ります。
 */
object CardDocking {
    /** 画面の高さに対するカードの最大比率。 */
    const val SHARE = 0.58f
    /** カードの上部の比率を覚えていない場合は、最も高いカードが配置可能な範囲の中央に配置されます。 */
    const val DEFAULT_FRACTION = 0.5f

    /**
     * 表示されるカードの最大の高さはどれくらいですか (ダイアログ カードはこの高さです): 画面の高さは [screenHeight] から [SHARE] ですが、[area] に収まる高さより高くはありません。
     * (上下のシャドウ エッジ [room] は別にします)。入力方法がポップアップすると、その上で [area] が受信され、カードが短くなり、中央のセクションが縮小しますが、入力ボックスはまだ表示されています。
     */
    fun maxHeight(screenHeight: Int, area: DockArea, room: Int): Int =
        minOf((screenHeight * SHARE).roundToInt(), area.bottom - area.top - 2 * room).coerceAtLeast(0)

    /**
     * は [fraction] に比例して配置され、高さ [h] のカード ウィンドウの上部: まず、入力メソッド [base] の範囲で最も高いカード (ウィンドウの高さ [full]) で上部を設定し、
     * に移動し、現在の範囲 [area] に移動します (下に置けない場合は上に移動します)。
     */
    fun y(fraction: Float, base: DockArea, area: DockArea, full: Int, h: Int): Int =
        CapsuleDocking.fitY(base.top + (fraction.coerceIn(0f, 1f) * topRange(base, full)).roundToInt(), area, h)

    /** タイトルバーをドラッグする際、ウィンドウ上部：[dy]に指を動かしたときに、上部が[base]に配置できる範囲から出ず、枠全体が[area]から出ません。 */
    fun dragY(startY: Int, dy: Float, base: DockArea, area: DockArea, full: Int, h: Int): Int =
        CapsuleDocking.fitY(minOf(startY + dy.roundToInt(), base.top + topRange(base, full)), area, h)

    /** 手放すときに記録される上限の比率 (入力メソッド [base] の範囲に従って計算され、入力メソッドは折りたたまれた後も同じ場所にあります)。 */
    fun release(y: Int, base: DockArea, full: Int): Float {
        val r = topRange(base, full)
        return if (r == 0) 0f else ((y - base.top).toFloat() / r).coerceIn(0f, 1f)
    }

    /** の上部をどこまで移動できるか: 上から下までの最も高いカード。 */
    private fun topRange(base: DockArea, full: Int) = maxOf(0, base.bottom - base.top - full)
}
