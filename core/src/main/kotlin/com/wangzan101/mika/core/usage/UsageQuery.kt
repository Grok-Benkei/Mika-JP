package com.wangzan101.mika.core.usage

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * は期間によって異なります。 [Last24Hours] は、ローリングの過去 24 時間 (現在の時間を含む) を時と分で示します。
 * [LastDays] は今日を含むローカル暦の過去数日、 [Custom] はローカル日付 [from]..[to] (両端を含む)、すべて日ベースです。
 * は [UsageQuery.MAX_DAILY_BARS] を日単位で超えています。
 */
sealed interface UsageRange {
    data object Last24Hours : UsageRange
    data class LastDays(val days: Int) : UsageRange
    data class Custom(val from: LocalDate, val to: LocalDate) : UsageRange
}

/** トレンド チャートのバーの長さはどれくらいですか? */
enum class UsageStep { Hour, Day, Week }

/** 一定期間にわたる合計。 [tokens] は入力と出力です (キャッシュ ヒットは入力でカウントされ、個別に追加されません)。 */
data class UsageTotals(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    val tokens: Long get() = input + output

    operator fun plus(n: UsageCounts) =
        UsageTotals(input + n.input, output + n.output, cached + n.cached, calls + n.calls, callsWithoutUsage + n.callsWithoutUsage)
}

/**
 * トレンド チャートの 1 つのブランチ: [start] は、このグリッドの先頭の現地時間です (毎時は時間、日と週は最初の日がゼロになります)。
 * [lastDay] は、週単位でのこのグリッドの最終日 (両端を含みます。最後のグリッドは 7 日未満の場合があります) であり、残りは null です。
 * [byProvider] は、このフィールドの各サービス プロバイダーのトークンです (使用されていないものはリストされていません)。
 */
data class UsageBucket(
    val start: LocalDateTime,
    val tokens: Long,
    val byProvider: Map<String, Long> = emptyMap(),
    val lastDay: LocalDate? = null,
)

/** は、モデルにおける特定の目的の使用法です。 [share] は、このモデルの比率 (0..1) です。これは統計の一次元にのみ使用され、インターフェイス上では区別されません。 */
data class PurposeUsage(val purpose: UsagePurpose, val totals: UsageTotals, val share: Double)

/** は、サービス プロバイダーの次のモデルの使用法です。 [share] は、このサービス プロバイダーの割合です。 */
data class ModelUsage(val model: String, val totals: UsageTotals, val share: Double, val purposes: List<PurposeUsage>)

/**
 * は 1 つのサービス プロバイダーの使用法です。 [share] は全体の割合です。 [name] は現在の名前です。サービスプロバイダーが削除したとき ([deleted]) は、会計時に残された名前です。
 * [order] は、このサービスプロバイダの固定順序（接続されているものは接続順、削除されたものは後ろ、課金時間の早い順）であり、これに従ってカラーマッチングが決定されます。
 * の交換時間範囲は変更されません。
 */
data class ProviderUsage(
    val id: String,
    val name: String,
    val deleted: Boolean,
    val totals: UsageTotals,
    val share: Double,
    val models: List<ModelUsage>,
    val order: Int = 0,
)

/** 一定期間の統計: 合計、トレンド チャート ([step] は 1 つのグリッドと同じ長さ)、サービス プロバイダー別の内訳 › モデル › 目的、各レイヤーはトークンごとに最も多いものから最も少ないものの順に並べられています。 */
data class UsageReport(
    val range: UsageRange,
    val step: UsageStep,
    val totals: UsageTotals,
    val series: List<UsageBucket>,
    val providers: List<ProviderUsage>,
) {
    val hourly: Boolean get() = step == UsageStep.Hour

    fun provider(id: String): ProviderUsage? = providers.firstOrNull { it.id == id }
}

/**
 * は台帳の内容から統計を計算します。台帳は UTC で正時に保存されます。これを現地時間に変更して、ここでこれを実行します。1 時間ごとのバケットは、開始時刻に従って現地の日に該当します。
 * (日付境界は [LocalDate.atStartOfDay] のルールを使用し、夏時間の 23 時間目または 25 時間目も正しいです)。
 * タイム ゾーンが UTC と 30 分異なる場合、ローカル ゼロを越えた時間が開始日としてカウントされます。
 */
object UsageQuery {
    private const val HOUR_MS = UsageLedger.HOUR_MS

    /** 最大個数は日ごとに分割できます。長押しすると週ごとに分割されます（初日から7日ごとに1枚）。 */
    const val MAX_DAILY_BARS = 45

    /**
     * [live] は、まだ接続されているサービス プロバイダー (ID → 名前) であり、接続順に並べられています (反復順序が順序です)。
     * 存在しない場合は削除されており、台帳に名前が残っています。
     */
    fun report(snapshot: UsageSnapshot, range: UsageRange, now: Instant, zone: ZoneId, live: Map<String, String>): UsageReport {
        val nowHour = Math.floorDiv(now.toEpochMilli(), HOUR_MS)
        val today = now.atZone(zone).toLocalDate()
        // 各行がどの列に属するか。範囲内にない場合は null
        val slot: (Long) -> Int?
        val starts: List<LocalDateTime>
        val lastDays: List<LocalDate?>
        val step: UsageStep
        when (range) {
            UsageRange.Last24Hours -> {
                val first = nowHour - 23
                step = UsageStep.Hour
                starts = (0 until 24).map { i -> LocalDateTime.ofInstant(Instant.ofEpochMilli((first + i) * HOUR_MS), zone) }
                lastDays = starts.map { null }
                slot = { h -> (h - first).takeIf { it in 0..23 }?.toInt() }
            }
            is UsageRange.LastDays, is UsageRange.Custom -> {
                val (from, to) = days(range, today)
                val count = ChronoUnit.DAYS.between(from, to).toInt() + 1
                val days = if (count > MAX_DAILY_BARS) 7 else 1
                step = if (days == 7) UsageStep.Week else UsageStep.Day
                val bars = (count + days - 1) / days
                starts = (0 until bars).map { from.plusDays(it.toLong() * days).atStartOfDay() }
                lastDays = (0 until bars).map { i -> if (days == 1) null else minOf(from.plusDays(i * 7L + 6), to) }
                slot = { h ->
                    val d = Instant.ofEpochMilli(h * HOUR_MS).atZone(zone).toLocalDate()
                    if (d < from || d > to) null else ChronoUnit.DAYS.between(from, d).toInt() / days
                }
            }
        }

        val bars = LongArray(starts.size)
        val perBar = List(starts.size) { HashMap<String, Long>() }
        val byKey = HashMap<UsageKey, UsageTotals>()
        val firstSeen = HashMap<String, Long>()
        var total = UsageTotals()
        for (row in snapshot.rows) {
            firstSeen.merge(row.key.providerId, row.hour, ::minOf)
            val i = slot(row.hour) ?: continue
            val t = row.counts.input + row.counts.output
            bars[i] += t
            if (t > 0) perBar[i].merge(row.key.providerId, t, Long::plus)
            byKey[row.key] = (byKey[row.key] ?: UsageTotals()) + row.counts
            total += row.counts
        }

        // 固定順序: 接続されているものが最初にリストされ、削除されたものが次にリストされ、最も早いアカウンティング時間が使用されます。
        val liveOrder = live.keys.withIndex().associate { (i, id) -> id to i }
        val goneOrder = firstSeen.keys.filter { it !in live }.sortedWith(compareBy<String> { firstSeen.getValue(it) }.thenBy { it })
            .withIndex().associate { (i, id) -> id to live.size + i }

        val providers = byKey.entries.groupBy { it.key.providerId }.map { (pid, entries) ->
            val models = entries.groupBy { it.key.model }.map { (model, es) ->
                val mTotal = es.fold(UsageTotals()) { a, e -> a.add(e.value) }
                val purposes = es.groupBy { it.key.purpose }.map { (p, ps) -> p to ps.fold(UsageTotals()) { a, e -> a.add(e.value) } }
                    .sortedWith(compareByDescending<Pair<UsagePurpose, UsageTotals>> { it.second.tokens }
                        .thenByDescending { it.second.calls }.thenBy { it.first.ordinal })
                    .map { (p, t) -> PurposeUsage(p, t, share(t, mTotal)) }
                Triple(model, mTotal, purposes)
            }
            val pTotal = models.fold(UsageTotals()) { a, m -> a.add(m.second) }
            val sortedModels = models.sortedWith(compareByDescending<Triple<String, UsageTotals, List<PurposeUsage>>> { it.second.tokens }
                .thenByDescending { it.second.calls }.thenBy { it.first })
                .map { (m, t, ps) -> ModelUsage(m, t, share(t, pTotal), ps) }
            val name = live[pid] ?: snapshot.providerNames[pid] ?: pid
            ProviderUsage(
                pid, name, deleted = pid !in live, totals = pTotal, share = share(pTotal, total), models = sortedModels,
                order = liveOrder[pid] ?: goneOrder.getValue(pid),
            )
        }.sortedWith(compareByDescending<ProviderUsage> { it.totals.tokens }.thenByDescending { it.totals.calls }.thenBy { it.order })

        return UsageReport(
            range, step, totals = total,
            series = starts.indices.map { i -> UsageBucket(starts[i], bars[i], perBar[i], lastDays[i]) },
            providers = providers,
        )
    }

    /** 日の範囲がどの日に該当するかを示します (両端を含む)。カスタムの終端が逆に書かれている場合は、それらを入れ替えてください。 */
    fun days(range: UsageRange, today: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
        is UsageRange.LastDays -> today.minusDays(range.days.coerceAtLeast(1) - 1L) to today
        is UsageRange.Custom -> if (range.from <= range.to) range.from to range.to else range.to to range.from
        UsageRange.Last24Hours -> today.minusDays(1) to today
    }

    private fun UsageTotals.add(o: UsageTotals) =
        UsageTotals(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    private fun share(part: UsageTotals, whole: UsageTotals): Double =
        if (whole.tokens <= 0) 0.0 else part.tokens.toDouble() / whole.tokens
}
