package com.wangzan101.mika.core.uistate

import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Web マウス ([com.wangzan101.mika.core.cap.web.cdp.InputPacing]) および携帯電話のジェスチャー ([com.wangzan101.mika.core.cap.phone.GesturePacing])
 * が共有するリズムのオリジナル: 区間内の境界のあるランダム値、わずかに円弧状の 2 次ベジェ、中央のゆっくりとしたイージング。
 */
object Pace {
    /** [r] (両端を含む) からランダムに 1 つを選択します。間隔内に値が 1 つだけある場合、それがその値です。 */
    fun pick(random: Random, r: LongRange): Long = if (r.first >= r.last) r.first else random.nextLong(r.first, r.last + 1)

    /** [r] から整数 (両端を含む) をランダムに選択します。 */
    fun pick(random: Random, r: IntRange): Int = if (r.first >= r.last) r.first else random.nextInt(r.first, r.last + 1)

    /** 直線からの円弧の偏差の振幅: 距離 [fraction] を超えず、[maxPx] も超えません。どちら側かはランダムです（符号付き）。 */
    fun bow(random: Random, dist: Double, fraction: Double, maxPx: Double): Double =
        min(dist * fraction, maxPx) * random.nextDouble(-1.0, 1.0)

    /** 二次ベジェ制御点: [from]、[to] 接続の中点は、通常の [bow] からオフセットされます。 2 つの点が一致する場合、それが中点になります。 */
    fun control(from: Pair<Double, Double>, to: Pair<Double, Double>, bow: Double): Pair<Double, Double> {
        val (x0, y0) = from
        val (x1, y1) = to
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist == 0.0) return (x0 + x1) / 2 to (y0 + y1) / 2
        return (x0 + x1) / 2 - (y1 - y0) / dist * bow to (y0 + y1) / 2 + (x1 - x0) / dist * bow
    }

    /** [t] (0..1) の二次ベジェ点。 */
    fun bezier(from: Pair<Double, Double>, control: Pair<Double, Double>, to: Pair<Double, Double>, t: Double): Pair<Double, Double> {
        val u = 1 - t
        return (u * u * from.first + 2 * u * t * control.first + t * t * to.first) to
            (u * u * from.second + 2 * u * t * control.second + t * t * to.second)
    }

    /** 両端が遅く、中間が速い（スムーズステップ）：0→0、1→1、両端の速度は0です。 */
    fun ease(t: Double) = t * t * (3 - 2 * t)
}
