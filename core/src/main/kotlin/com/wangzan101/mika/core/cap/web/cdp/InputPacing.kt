package com.wangzan101.mika.core.cap.web.cdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import com.wangzan101.mika.core.uistate.Pace
import kotlin.random.Random

/**
 * DevTools マウスとキーボードのリズム: マウスを短い円弧に沿って動かし、押し、しばらく押し続けてから持ち上げます。キー間にはさまざまな長さの間隔があり、スクロール ホイールはグリッドを 1 つずつ回転します。
 * すべての持続時間と振幅には上限と下限 ([Limits]) があるため、アクションの合計持続時間は制限されます。 [Instant] 一時停止や中間点なし
 * (イベント シーケンスはリズムなしと同じです。リズムを必要としないテストや呼び出し元に使用されます)。
 */
class InputPacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * の上限と下限。期間の単位はミリ秒、距離の単位は CSS ピクセルです。
     * [approachPx]: ポインターが不明な場合、ターゲットからの距離。 [bowFraction] / [maxBowPx]: 直線からの軌道偏差の最大振幅 (相対距離 / 絶対)。
     * [maxKeyedChars]: この長さより長いテキストの場合は、キーごとに入力するのではなく、段落全体を入力ボックス (貼り付けなど) のままにしておきます。
     */
    data class Limits(
        val moveSteps: IntRange = 4..9,
        val moveStepMs: LongRange = 8L..22L,
        val approachPx: IntRange = 60..180,
        val bowFraction: Double = 0.12,
        val maxBowPx: Double = 40.0,
        val dwellMs: LongRange = 40L..120L,
        val pressMs: LongRange = 50L..120L,
        val keyHoldMs: LongRange = 25L..70L,
        val keyGapMs: LongRange = 35L..120L,
        val modifierGapMs: LongRange = 15L..45L,
        val wheelNotchPx: Double = 53.0,
        val maxNotches: Int = 12,
        val wheelGapMs: LongRange = 25L..70L,
        val maxKeyedChars: Int = 30,
    )

    /** にはリズムがあります ([Instant] ではありません)。 */
    val paced: Boolean get() = random != null

    fun dwell(): Long = pick(limits.dwellMs)
    fun press(): Long = pick(limits.pressMs)
    fun keyHold(): Long = pick(limits.keyHoldMs)
    fun keyGap(): Long = pick(limits.keyGapMs)
    fun modifierGap(): Long = pick(limits.modifierGapMs)
    fun moveStep(): Long = pick(limits.moveStepMs)
    fun wheelGap(): Long = pick(limits.wheelGapMs)

    private fun pick(r: LongRange): Long = random?.let { Pace.pick(it, r) } ?: 0L

    /** ポインターが不明な場合の開始点: 遠く離れたターゲット [Limits.approachPx] の周囲のどこか (座標は 0 以上)。 [Instant] はターゲットそのものです。 */
    fun origin(x: Double, y: Double): Pair<Double, Double> {
        val r = random ?: return x to y
        val dist = Pace.pick(r, limits.approachPx).toDouble()
        val angle = r.nextDouble(0.0, 2 * PI)
        return (x + dist * cos(angle)).coerceAtLeast(0.0) to (y + dist * sin(angle)).coerceAtLeast(0.0)
    }

    /**
     * [from] から [to] までの中間点 (始点を除く、最後の点がちょうど終点です): 二次ベジェ円弧、両端が遅く、中央が速い。
     * [Instant] または 3 ピクセル未満離れている場合のみエンドポイント。
     */
    fun path(from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>> {
        val r = random ?: return listOf(to)
        val (x0, y0) = from
        val (x1, y1) = to
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist < 3.0) return listOf(to)
        val n = Pace.pick(r, limits.moveSteps)
        val c = Pace.control(from, to, Pace.bow(r, dist, limits.bowFraction, limits.maxBowPx))
        return (1..n).map { i -> if (i == n) to else Pace.bezier(from, c, to, Pace.ease(i.toDouble() / n)) }
    }

    /**
     * は、転動距離 [distance] (プラスは下向き) をローラー単位で 1 つずつ分割します。合計はちょうど [distance] です: 各グリッドは [Limits.wheelNotchPx]、
     * の最後の正方形は残りの小数です。正方形の数が [Limits.maxNotches] を超えると、[Limits.maxNotches] に変更され、正方形が均等に分割されます (各正方形が大きいため、合計期間は制限されます)。
     * [Instant]を一気に巻きます。 0 は空のテーブルです。
     */
    fun notches(distance: Double): List<Double> {
        if (distance == 0.0) return emptyList()
        if (random == null) return listOf(distance)
        val sign = if (distance < 0) -1.0 else 1.0
        val total = abs(distance)
        val count = ceil(total / limits.wheelNotchPx - 1e-9).toInt().coerceAtLeast(1)
        if (count > limits.maxNotches) return List(limits.maxNotches) { sign * total / limits.maxNotches }
        return List(count) { i -> sign * if (i < count - 1) limits.wheelNotchPx else total - limits.wheelNotchPx * (count - 1) }
    }

    companion object {
        /** は一時停止や中間点を追加しません。 */
        val Instant = InputPacing(null)

        /** は人間の手のリズムに似ています。 [random] は固定シード (テスト) に置き換えることができます。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = InputPacing(random, limits)
    }
}
