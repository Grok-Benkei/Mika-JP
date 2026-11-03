package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.DOUBLE_TAP_GAP_MS
import com.wangzan101.mika.core.uistate.Pace
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 携帯電話のジェスチャーのリズム: 指を置く前に一時停止し、タップとホールドの長さが変化し、着地点が要素の中心付近からわずかにずれます (常に要素内)。
 * は小さな円弧に沿ってスライドし、最初に加速してから減速し、持続時間は指定された値を中心に変動します。すべての持続時間と振幅には上限と下限 ([Limits]) があるため、アクションの合計持続時間は制限されます。
 * [Instant] は一時停止、偏差、ラジアンを追加せず、継続時間は以前と同じです ([TAP_MS] をクリックし、間隔 [DOUBLE_TAP_GAP_MS] をダブルクリックし、スライドして指定された継続時間一定の速度で直線を歩きます)。
 */
class GesturePacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * の上限と下限。期間の単位はミリ秒、距離の単位はピクセルです。
     * [approachMs]: 指が落ちる前に一時停止します。 [pressMs]: 1 回クリックして押し続けます。 [doublePressMs] / [doubleGapMs]: 2 回のクリックの間、ダブルクリックして押し続けます。
     * (システムはダブルクリックの間隔が 40 ～ 300 ミリ秒であると認識します)。 [jitterFraction] / [maxJitterPx] / [minJitterSpanPx]: 要素をクリックすると、着地点が中心から外れます
     * は、要素の幅 (高さ) と多くのピクセルの比率を超えず、[minJitterSpanPx] よりも狭い (短い)。 [pointJitterPx]: 座標点を押したときの偏差。
     * [durationJitter]: 上下のフローティングに対するスライド時間の比率。 [holdStretch]: 所定の位置で押し続けると (長押しなど、始点と終点がほぼ一致します)、長くなるだけで、短くならず、最大でもこの比率が加算されます。
     * [stillPx]: 開始点と終了点の距離がこれよりも小さい場合、所定の位置に保持されているとみなされます。 [bowFraction] / [maxBowPx]: 直線からの円弧の最大偏差。
     * [sampleMs]: 指が動くときの 2 つの隣接する点の間の時間間隔。 [maxPoints]: 1 つのストロークで最大数のポイント。
     * [dragMs]: この長さ以上のスライドをスロードラグとして使用し、手を離すとブレることなくエンドが減速して停止します。短いスライドは速いスライドとして使用され、手を離したときの速度は平均速度の [flingEndSpeed] 倍になります。
     * （リリース速度は従来の一定スライド速度と同等、転がり距離は変わりません）。
     */
    data class Limits(
        val approachMs: LongRange = 30L..120L,
        val pressMs: LongRange = 50L..120L,
        val doublePressMs: LongRange = 40L..90L,
        val doubleGapMs: LongRange = 80L..140L,
        val jitterFraction: Double = 0.2,
        val maxJitterPx: Int = 12,
        val minJitterSpanPx: Int = 24,
        val pointJitterPx: Int = 3,
        val durationJitter: Double = 0.08,
        val holdStretch: Double = 0.12,
        val stillPx: Double = 3.0,
        val bowFraction: Double = 0.06,
        val maxBowPx: Double = 30.0,
        val sampleMs: LongRange = 8L..16L,
        val maxPoints: Int = 600,
        val dragMs: Long = 800L,
        val flingEndSpeed: ClosedFloatingPointRange<Double> = 0.9..1.0,
    )

    /** ストローク内のポイント: 指が ([x]、[y]) にあるときの相対的な下降時間 [t] ミリ秒。 */
    data class Point(val x: Double, val y: Double, val t: Long)

    /** 1 ストローク: 押してから持ち上げるまでの点 (最初の t は 0、最後の t は持ち上げる瞬間と位置)。 */
    data class Stroke(val points: List<Point>) {
        val durationMs: Long get() = points.last().t
    }

    /** にはリズムがあります ([Instant] ではありません)。 */
    val paced: Boolean get() = random != null

    fun approach(): Long = pick(limits.approachMs, 0L)
    fun press(): Long = pick(limits.pressMs, TAP_MS)
    fun doublePress(): Long = pick(limits.doublePressMs, TAP_MS)
    fun doubleGap(): Long = pick(limits.doubleGapMs, DOUBLE_TAP_GAP_MS)

    private fun pick(r: LongRange, instant: Long): Long = random?.let { Pace.pick(it, r) } ?: instant

    /**
     * が要素([left]、[top])-([right]、[bottom])を指すときの着地点(右と下は含まれません)：中心付近(Rect.centerX/centerYのように丸められます)、
     * は、中心に近い [Limits.jitterFraction] および [Limits.maxJitterPx] の幅 (高さ) を超えて逸脱することはありません。狭い方向はずれません。配置ポイントは常に要素内にあります。
     */
    fun within(left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
        val cx = (left + right) shr 1
        val cy = (top + bottom) shr 1
        val r = random ?: return cx to cy
        fun reach(span: Int) = if (span < limits.minJitterSpanPx) 0 else minOf((span * limits.jitterFraction).toInt(), limits.maxJitterPx)
        return (cx + offset(r, reach(right - left))).coerceIn(left, maxOf(left, right - 1)) to
            (cy + offset(r, reach(bottom - top))).coerceIn(top, maxOf(top, bottom - 1))
    }

    /** 座標点を押したときの着地点: 指定された点の近くの [Limits.pointJitterPx] 以内 (1dp 未満、点は他のコントロールに偏向しません)。座標は 0 未満ではありません。 */
    fun near(x: Int, y: Int): Pair<Int, Int> {
        val r = random ?: return x to y
        return (x + offset(r, limits.pointJitterPx)).coerceAtLeast(0) to (y + offset(r, limits.pointJitterPx)).coerceAtLeast(0)
    }

    // 2 つの均一な数の合計 (三角分布): ほとんどが中心付近にあり、±reach を超えない
    private fun offset(r: Random, reach: Int): Int =
        if (reach <= 0) 0 else ((r.nextDouble() + r.nextDouble() - 1.0) * reach).roundToInt().coerceIn(-reach, reach)

    /** 複数の指が一緒に動くジェスチャの継続時間 ([stroke] 配置なし): 指定された値は [Limits.durationJitter] で上下に変動します。 */
    fun duration(durationMs: Long): Long {
        val r = random ?: return durationMs
        return (durationMs * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
    }

    /**
     * 1 本の指を使用して、[path] に沿って [durationMs] の周りにストロークを移動します (少なくとも 2 点)。開始点と終了点は変更されません。 [bow] が true の場合、2 点間に短い円弧が作成されます。
     * (2 点のパスの場合のみ、複数点のパスは呼び出し元によって描画され、それに従う)。 [bounds](幅、高さ)を与えると、すべての点が画面内に挟まれます。
     * 開始点と終了点がほぼ重なったら、その場で長押しします。存在するのは開始点と終了点のみで、継続時間は伸びるだけで短縮されません（長押しでも十分な長さです）。
     * [Instant]: ポイントは [path] で、時間はパスの長さに応じて均一に配分され、時間は正確に [durationMs] になります。
     */
    fun stroke(path: List<Pair<Double, Double>>, durationMs: Long, bow: Boolean = false, bounds: Pair<Int, Int>? = null): Stroke {
        require(path.size >= 2) { "a stroke needs at least two points" }
        val total = durationMs.coerceAtLeast(1)
        val r = random ?: return Stroke(uniform(path, total))
        val start = path.first()
        val end = path.last()
        val length = lengthOf(path)
        if (length < limits.stillPx) {
            val hold = (total * (1 + r.nextDouble(0.0, limits.holdStretch))).roundToLong()
            return Stroke(listOf(Point(start.first, start.second, 0), Point(end.first, end.second, hold)))
        }
        val t = (total * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
        val shape = if (bow && path.size == 2) {
            val c = Pace.control(start, end, Pace.bow(r, length, limits.bowFraction, limits.maxBowPx))
            (0..CURVE_SEGMENTS).map { Pace.bezier(start, c, end, it.toDouble() / CURVE_SEGMENTS) }
        } else path
        val endSpeed = if (t >= limits.dragMs) 0.0 else r.nextDouble(limits.flingEndSpeed.start, limits.flingEndSpeed.endInclusive)
        val step = Pace.pick(r, limits.sampleMs).coerceAtLeast(1)
        val n = ceil(t.toDouble() / step).toInt().coerceIn(1, limits.maxPoints - 1)
        val cum = cumulative(shape)
        val points = (0..n).map { i ->
            val tm = if (i == n) t else (t * i.toDouble() / n).roundToLong()
            val (x, y) = when (i) {
                0 -> start
                n -> end
                else -> at(shape, cum, cum.last() * progress(i.toDouble() / n, endSpeed))
            }
            Point(x, y, tm)
        }
        return Stroke(if (bounds == null) points else points.map { it.copy(x = it.x.coerceIn(0.0, bounds.first - 1.0), y = it.y.coerceIn(0.0, bounds.second - 1.0)) })
    }

    companion object {
        /** リズムを追加しないときにクリックしたままにする時間の長さ。 */
        const val TAP_MS = 50L

        /** 円弧は非常に多くの小さなセグメントに分割され、時間に従って点が取得されます。 */
        private const val CURVE_SEGMENTS = 32

        /** は、一時停止、偏差、ラジアンを追加しません。 */
        val Instant = GesturePacing(null)

        /** は人間の手のリズムに似ています。 [random] は固定シード (テスト) に置き換えることができます。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = GesturePacing(random, limits)

        /**
         * 時間が [u] (0..1) に達したときの移動距離の割合: 3 つのエルミート、開始速度 0、終了速度 [endSpeed] に平均速度を掛けたもの (0..3 の間で単調)。
         * [endSpeed] 0の場合は両端が遅く、中間が速くなります。立ち止まってから手を挙げてください。 1の場合は一定速度と同じ速度で手を放します。
         */
        internal fun progress(u: Double, endSpeed: Double): Double {
            val u2 = u * u
            val u3 = u2 * u
            return (-2 * u3 + 3 * u2) + endSpeed * (u3 - u2)
        }

        private fun lengthOf(path: List<Pair<Double, Double>>): Double = cumulative(path).last()

        private fun cumulative(path: List<Pair<Double, Double>>): DoubleArray {
            val out = DoubleArray(path.size)
            for (i in 1 until path.size) out[i] = out[i - 1] + hypot(path[i].first - path[i - 1].first, path[i].second - path[i - 1].second)
            return out
        }

        /** は、[s] をポリラインに沿って通過させたときの位置です。 */
        private fun at(path: List<Pair<Double, Double>>, cum: DoubleArray, s: Double): Pair<Double, Double> {
            var i = 1
            while (i < path.size - 1 && cum[i] < s) i++
            val seg = cum[i] - cum[i - 1]
            val f = if (seg <= 0) 1.0 else ((s - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
            val (x0, y0) = path[i - 1]
            val (x1, y1) = path[i]
            return x0 + (x1 - x0) * f to y0 + (y1 - y0) * f
        }

        /** リズムのないストローク: それは与えられた点であり、時間は距離に応じて均一な速度で分布します (システムがパスに沿って均一な速度で歩くのと同じです)。 */
        private fun uniform(path: List<Pair<Double, Double>>, total: Long): List<Point> {
            val cum = cumulative(path)
            val length = cum.last()
            return path.mapIndexed { i, (x, y) ->
                val t = when {
                    i == path.lastIndex -> total
                    length <= 0 -> 0L
                    else -> (total * cum[i] / length).roundToLong()
                }
                Point(x, y, t)
            }
        }
    }
}
