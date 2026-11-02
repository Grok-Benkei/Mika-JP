package com.wangzan101.mika.core.uistate

import kotlin.math.abs
import kotlin.random.Random

/**
 * 滞在中に便利なちょっとしたアクション (ページに触れずに純粋なロジック): 次のページをめくるのを待っているとき、ユーザーはページを少し下にスライドさせたり、場合によっては少し戻したりします。
 * 十分に長く滞在する場合は 1 回実行してください ([Limits.minWaitMs])。これを 1 ～ 3 回実行し ([Limits.count])、毎回ビューポートの高さの小さなセクションをスクロールします ([Limits.stepFraction])。
 * はほとんどが下にスクロールし、時々 ([Limits.upChance]) は少しスクロールバックしますが、スクロールバックは前のスクロールを超えません。スクロールの合計数は [Limits.maxTotal] ビューポートを超えません。
 * の正味の動きは [Limits.maxNet] ビューポートを超えません。一時停止中に時間が経過し、前後のセクション ([Limits.leadMs]、[Limits.tailMs]) が残ります。
 * はページをスクロールするだけで、ポインターの移動、ホバー、クリックは行いません。
 */
class IdleScroll(private val random: Random, val limits: Limits = Limits()) {
    /** 期間はミリ秒単位で、振幅はビューポートの高さです。 */
    data class Limits(
        val minWaitMs: Long = 3_000L,
        val count: IntRange = 1..3,
        val stepFraction: ClosedFloatingPointRange<Double> = 0.06..0.16,
        val upChance: Double = 0.25,
        val maxTotal: Double = 0.45,
        val maxNet: Double = 0.3,
        val leadMs: Long = 800L,
        val tailMs: Long = 1_200L,
        /** 2 回のクリックの間は少なくともこの長さです。 */
        val gapMs: Long = 700L,
    )

    /** 1 回: 滞在開始後、[atMs] 時間に [fraction] ビューポートを (正の下方向に) スクロールします。 */
    data class Step(val atMs: Long, val fraction: Double)

    /** [waitMs] 1回の滞在で何回すればいいですか？短すぎると空になってしまいます。 */
    fun plan(waitMs: Long): List<Step> {
        val l = limits
        if (waitMs < l.minWaitMs) return emptyList()
        val room = waitMs - l.leadMs - l.tailMs
        if (room < 0) return emptyList()
        // は滞在中に数回置くことができます（2回の間に少なくともgapMを空ける必要があります）
        val fits = (room / l.gapMs + 1).toInt()
        val n = random.nextInt(l.count.first, l.count.last + 1).coerceAtMost(fits).coerceAtLeast(1)
        var total = 0.0
        var net = 0.0
        val fractions = mutableListOf<Double>()
        repeat(n) { i ->
            val size = random.nextDouble(l.stepFraction.start, l.stepFraction.endInclusive)
            val up = i > 0 && net > 0 && random.nextDouble() < l.upChance
            val f = if (up) -minOf(size, net) else minOf(size, l.maxNet - net)
            if (abs(f) < MIN_STEP || total + abs(f) > l.maxTotal) return@repeat
            fractions += f
            total += abs(f)
            net += f
        }
        // 時間: 空いている部分に均等に広げ、順番に並べます。 2 つの時間の差は近くありません
        val times = MutableList(fractions.size) { l.leadMs + random.nextLong(0, room + 1) }.sorted().toMutableList()
        for (i in 1 until times.size) times[i] = maxOf(times[i], times[i - 1] + l.gapMs)
        return fractions.indices.mapNotNull { i -> times[i].takeIf { it <= waitMs - l.tailMs }?.let { Step(it, fractions[i]) } }
    }

    private companion object {
        /** これより小さい場合は一度には行われません（スクロールしても見えません）。 */
        const val MIN_STEP = 0.02
    }
}
