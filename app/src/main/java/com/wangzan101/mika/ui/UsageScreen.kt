package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.usage.ModelUsage
import com.wangzan101.mika.core.usage.ProviderUsage
import com.wangzan101.mika.core.usage.UsageBucket
import com.wangzan101.mika.core.usage.UsageLedger
import com.wangzan101.mika.core.usage.UsageQuery
import com.wangzan101.mika.core.usage.UsageRange
import com.wangzan101.mika.core.usage.UsageReport
import com.wangzan101.mika.core.usage.UsageStep
import com.wangzan101.mika.core.usage.UsageTotals
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import com.wangzan101.mika.i18n.uiLocale
import kotlin.math.roundToInt

/** 使用状況ページの上部にある 5 つのセグメント: 最初の 4 つのセグメントは固定範囲であり、[Custom] は日付の選択を開きます。 */
enum class UsagePreset(private val labelRes: Int) {
    Day(R.string.usage_24h), Week(R.string.usage_7d), Month(R.string.usage_30d), Quarter(R.string.usage_90d), Custom(R.string.usage_custom);

    val label: String get() = tr(labelRes)

    /** このセクションの対応範囲。 [Custom] は [from]..[to] (現地の日付) を与える必要があります。 */
    fun range(from: LocalDate?, to: LocalDate?): UsageRange = when (this) {
        Day -> UsageRange.Last24Hours
        Week -> UsageRange.LastDays(7)
        Month -> UsageRange.LastDays(30)
        Quarter -> UsageRange.LastDays(90)
        Custom -> if (from != null && to != null) UsageRange.Custom(from, to) else UsageRange.LastDays(7)
    }
}

/** モデル ランキングの行: [share] は、この行 (すべてのサービス プロバイダー、または選択したサービス プロバイダー) の割合です。 */
data class ModelRank(val provider: ProviderUsage, val model: ModelUsage, val share: Double)

/** トークンの使用に関するテキスト、数値の書き込み、グラフの小さなルール。 */
object UsageCopy {
    val TITLE: String get() = tr(R.string.usage_title)
    val NONE: String get() = tr(R.string.usage_none)
    val EMPTY_TITLE: String get() = tr(R.string.usage_empty_title)
    val EMPTY_BODY: String get() = tr(R.string.usage_empty_body)
    val NOTE: String get() = tr(R.string.usage_note)
    val TOTAL: String get() = tr(R.string.usage_total)
    val TREND: String get() = tr(R.string.usage_trend)
    val BY_PROVIDER: String get() = tr(R.string.usage_by_provider)
    val BY_PROVIDER_HINT: String get() = tr(R.string.usage_by_provider_hint)
    val BY_MODEL: String get() = tr(R.string.usage_by_model)
    val ALL: String get() = tr(R.string.usage_all)
    val ALL_PROVIDERS: String get() = tr(R.string.usage_all_providers)
    val OTHERS: String get() = tr(R.string.usage_others)
    val DELETED: String get() = tr(R.string.usage_deleted)
    val CHANGE_DATES: String get() = tr(R.string.usage_change_dates)
    const val UNIT = "Token"

    /** チャート内で独自の色を持つサービス プロバイダーの最大数 (テーマ内の [MikaColors.series] の数) であり、残りはトレンド チャートで「その他」として分類されます。 */
    const val COLORED = 3

    /**
     * トークン番号: 10,000 未満の場合は書き留めます。上がった場合は、10,000 または 100,000,000 を押し、有効数字 3 桁を維持します (四捨五入して最後の 0 を削除します)。
     * たとえば、8765、12,300、123,000、123 万、1 億 5,000 万。繁体字中国語では、万または十億と書きます。
     */
    fun tokens(n: Long): String {
        if (n < 10_000) return n.toString()
        val wan = round3(BigDecimal(n).divide(BigDecimal(10_000)))
        if (wan < BigDecimal(10_000)) return "${wan.stripTrailingZeros().toPlainString()} 万"
        val yi = round3(BigDecimal(n).divide(BigDecimal(100_000_000)))
        return "${yi.stripTrailingZeros().toPlainString()} 億"
    }

    private fun round3(v: BigDecimal): BigDecimal = v.setScale(
        when {
            v < BigDecimal.TEN -> 2
            v < BigDecimal(100) -> 1
            else -> 0
        },
        RoundingMode.HALF_UP,
    )

    /** ホームページの行の値を設定します。過去 7 日間の合計、そうでない場合は「まだ」と記入します。 */
    fun settingsValue(weekTokens: Long): String = if (weekTokens <= 0) NONE else tr(R.string.usage_last_7d, tokens(weekTokens))

    fun calls(n: Long) = trPlural(R.plurals.times, n.toInt())

    /** 割合: 1% 未満ですが 0 ではないため、「<1%」と書き込みます。 */
    fun percent(share: Double): String {
        val p = (share * 100).roundToInt()
        return if (p == 0 && share > 0) "<1%" else "$p%"
    }

    fun missing(n: Long) = trPlural(R.plurals.usage_missing, n.toInt())

    fun providerName(p: ProviderUsage) = if (p.deleted) p.name + DELETED else p.name

    fun stepLabel(step: UsageStep) = when (step) {
        UsageStep.Hour -> tr(R.string.usage_hourly)
        UsageStep.Day -> tr(R.string.usage_daily)
        UsageStep.Week -> tr(R.string.usage_weekly)
    }

    /** 現地の日付: 今年の年は書かないでください。 */
    fun date(d: LocalDate, today: LocalDate): String =
        if (d.year == today.year) tr(R.string.date_month_day, d.monthValue, d.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), d.dayOfMonth)
        else tr(R.string.date_year_month_day, d.year, d.monthValue, d.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), d.dayOfMonth)

    /** 範囲として下の行を選択し、実際の日数を書き留めます。 */
    fun rangeText(range: UsageRange, today: LocalDate): String = when (range) {
        UsageRange.Last24Hours -> tr(R.string.usage_past_24h)
        is UsageRange.LastDays -> tr(R.string.usage_until_today, date(UsageQuery.days(range, today).first, today))
        is UsageRange.Custom -> UsageQuery.days(range, today).let { (from, to) ->
            if (from == to) date(from, today) else "${date(from, today)} – ${date(to, today)}"
        }
    }

    /** 傾向グラフの下のスケール: 時間単位で「18:00」と書き込み (この時点でボックスに「今」と書き込みます)、日および週単位で「10/27」と書き込みます (日単位で「今日」と書き込みます)。 */
    fun axis(b: UsageBucket, step: UsageStep, today: LocalDate, last: Boolean = false): String = when {
        step == UsageStep.Hour && last -> tr(R.string.usage_now)
        step == UsageStep.Hour -> "%d:%02d".format(b.start.hour, b.start.minute)
        b.start.toLocalDate() == today -> tr(R.string.usage_today)
        else -> "${b.start.monthValue}/${b.start.dayOfMonth}"
    }

    /** 柱をクリックするとどの期間が表示されるか。 このサービス プロバイダー */
    fun period(b: UsageBucket, step: UsageStep, today: LocalDate): String = when (step) {
        UsageStep.Hour -> "%d:%02d".format(b.start.hour, b.start.minute)
        UsageStep.Day -> date(b.start.toLocalDate(), today)
        UsageStep.Week -> {
            val first = b.start.toLocalDate()
            val last = b.lastDay ?: first
            if (first == last) date(first, today) else "${date(first, today)} – ${date(last, today)}"
        }
    }

    /** はグラフでどの色を使用しますか。 [COLORED] の後のものは null (灰色で、トレンド チャートでは「その他」に分類されます) です。 */
    fun colorSlot(p: ProviderUsage): Int? = p.order.takeIf { it < COLORED }

    /** グリッド ラインの上部: [max] の 1、2、4、6、8 の 10 倍以上であり、ハーフ スケールも整数です。 */
    fun niceMax(max: Long): Long {
        if (max <= 0) return 1
        var scale = 1L
        while (scale * 10 < max) scale *= 10
        return listOf(1L, 2L, 4L, 6L, 8L, 10L).map { it * scale }.first { it >= max }
    }

    /** モデル別ランキング: サービス プロバイダーが選択されている場合、このプロバイダーのみがリストされます。すべてのサービス プロバイダーが選択されるわけではありません。トークンごとに、最も多いものから最も少ないものまで、割合はこの行の合計に基づきます。 */
    fun modelRanking(report: UsageReport, providerId: String?): List<ModelRank> {
        val from = providerId?.let { id -> listOfNotNull(report.provider(id)) } ?: report.providers
        val rows = from.flatMap { p -> p.models.map { p to it } }
            .sortedWith(compareByDescending<Pair<ProviderUsage, ModelUsage>> { it.second.totals.tokens }.thenByDescending { it.second.totals.calls })
        val total = rows.sumOf { it.second.totals.tokens }
        return rows.map { (p, m) -> ModelRank(p, m, if (total <= 0) 0.0 else m.totals.tokens.toDouble() / total) }
    }
}

/**
 * 設定 › トークンの使用法: 1 ページ。上部の時刻と、この期間内の合計使用量を選択します。次の 3 つの図は、使用傾向 (サービス プロバイダーごとに積み上げたもの)、
 * サービス プロバイダー別のランキング (クリックして選択し、クリックしてキャンセル)、モデル別のランキング (サービス プロバイダーを切り替えることができます)。選択したサービス プロバイダーは、図内でどのサービス プロバイダーが強調表示されるか、およびどのサービス プロバイダーがモデルにリストされるかにのみ影響します。
 * の合計使用量は常にすべてです。レコードが台帳 ([UsageLedger.version]) に記録されるたびに、統計が再計算されます。ローカル タイム ゾーンは統計中に変換されます。
 */
@Composable
fun UsageScreen(ledger: UsageLedger, providers: StateFlow<List<Provider>>, onBack: () -> Unit) {
    var preset by rememberSaveable { mutableStateOf(UsagePreset.Week) }
    // カスタム範囲の両端がエポック日として保存されます (rememberSaveable タイプは保存可能)
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val version by ledger.version.collectAsState()
    val live by providers.collectAsState()
    val range = preset.range(customFrom?.let(LocalDate::ofEpochDay), customTo?.let(LocalDate::ofEpochDay))
    val zone = ZoneId.systemDefault()
    val report by produceState<UsageReport?>(null, version, range, live) {
        value = withContext(Dispatchers.Default) {
            // 接続されたサービスプロバイダーは接続に従って順番に送信され、カラーマッチングはこの順番に従って固定されます。
            UsageQuery.report(ledger.snapshot(), range, Instant.now(), zone, live.associate { it.id to it.name })
        }
    }
    // 時刻変更後、選択したサービスプロバイダーはこの期間は利用がないため、すべて返却されます。
    LaunchedEffect(report) {
        val r = report ?: return@LaunchedEffect
        if (selected?.let(r::provider) == null) selected = null
    }

    UsageContent(
        report = report, preset = preset, today = LocalDate.now(zone), selected = selected,
        onPreset = { p -> if (p == UsagePreset.Custom) picking = true else preset = p },
        onPickCustom = { picking = true },
        onSelect = { selected = it },
        onBack = onBack,
    )

    if (picking) {
        UsageRangePicker(
            today = LocalDate.now(zone),
            from = customFrom?.let(LocalDate::ofEpochDay), to = customTo?.let(LocalDate::ofEpochDay),
            onPicked = { from, to ->
                customFrom = from.toEpochDay(); customTo = to.toEpochDay()
                preset = UsagePreset.Custom
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * ステータスのない使用状況ページ。テストに便利です。 [report] が null の場合は、統計がまだ収集中であり、範囲選択のみが表示されていることを意味します。
 * [onPreset] [UsagePreset.Custom] を受信すると、発信者は日付選択を開きます。 「日付の変更」もカスタム時点 ([onPickCustom]) で開きます。
 * [selected] は選択されたサービス プロバイダー (null はすべてを意味します)、クリック ランキングまたはカプセルは [onSelect] によって変更されます。
 */
@Composable
fun UsageContent(
    report: UsageReport?,
    preset: UsagePreset,
    today: LocalDate,
    selected: String?,
    onPreset: (UsagePreset) -> Unit,
    onPickCustom: () -> Unit,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    PageScaffold(UsageCopy.TITLE, onBack) {
        SegmentedControl(
            UsagePreset.entries.map { it.label }, preset.ordinal, { onPreset(UsagePreset.entries[it]) },
            tags = UsagePreset.entries.map { "usage-range-${it.name}" },
        )
        if (report == null) return@PageScaffold
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            Text(UsageCopy.rangeText(report.range, today), style = MikaType.Hint, color = c.ink3)
            if (preset == UsagePreset.Custom) {
                Text(" · ", style = MikaType.Hint, color = c.ink3)
                Text(
                    UsageCopy.CHANGE_DATES, Modifier.clickable(role = Role.Button, onClick = onPickCustom).testTag("usage-change-dates"),
                    style = MikaType.Hint, color = c.accent, fontWeight = FontWeight.Medium,
                )
            }
        }
        if (report.providers.isEmpty()) {
            EmptyCard()
            return@PageScaffold
        }
        val pick = selected?.takeIf { report.provider(it) != null }
        TotalsCard(report.totals)

        ChartCard(UsageCopy.TREND, UsageCopy.stepLabel(report.step)) {
            TrendChart(report, pick, today)
        }

        ChartCard(UsageCopy.BY_PROVIDER, UsageCopy.BY_PROVIDER_HINT) {
            val max = report.providers.maxOf { it.totals.tokens }.coerceAtLeast(1)
            Column(Modifier.padding(top = 6.dp)) {
                report.providers.forEach { p ->
                    RankRow(
                        UsageCopy.providerName(p), mono = false, muted = p.deleted, color = seriesColor(c, p),
                        fraction = p.totals.tokens.toFloat() / max, tokens = p.totals.tokens, share = p.share,
                        dim = pick != null && pick != p.id, bold = pick == p.id,
                        onClick = { onSelect(if (pick == p.id) null else p.id) }, tag = "usage-rank-provider-${p.id}",
                    )
                }
            }
        }

        ChartCard(UsageCopy.BY_MODEL, pick?.let(report::provider)?.let(UsageCopy::providerName) ?: UsageCopy.ALL_PROVIDERS) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UsageChip(UsageCopy.ALL, pick == null, { onSelect(null) }, tag = "usage-chip-all")
                report.providers.sortedBy { it.order }.forEach { p ->
                    UsageChip(p.name, pick == p.id, { onSelect(p.id) }, deleted = p.deleted, tag = "usage-chip-${p.id}")
                }
            }
            val models = UsageCopy.modelRanking(report, pick)
            val max = models.maxOfOrNull { it.model.totals.tokens }?.coerceAtLeast(1) ?: 1
            models.forEach { m ->
                RankRow(
                    m.model.model, mono = true, muted = false, color = seriesColor(c, m.provider),
                    fraction = m.model.totals.tokens.toFloat() / max, tokens = m.model.totals.tokens, share = m.share,
                    tag = "usage-rank-model-${m.provider.id}-${m.model.model}",
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        if (report.totals.callsWithoutUsage > 0) {
            FooterNote(UsageCopy.missing(report.totals.callsWithoutUsage), Modifier.testTag("usage-missing"))
        }
        FooterNote(UsageCopy.NOTE)
    }
}

private fun seriesColor(c: MikaColors, p: ProviderUsage): Color = UsageCopy.colorSlot(p)?.let { c.series.getOrNull(it) } ?: c.seriesOther

/** 合計使用量カード: 大きなワードは入力と出力で、一番下の行は入力、出力、キャッシュ ヒット、および呼び出し時間です。 */
@Composable
private fun TotalsCard(t: UsageTotals) {
    val c = MikaTheme.colors
    MikaCard {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
            Text(UsageCopy.TOTAL, style = MikaType.Caption, color = c.ink3)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                Text(UsageCopy.tokens(t.tokens), Modifier.testTag("usage-total"), style = TotalStyle, color = c.ink)
                Text(
                    UsageCopy.UNIT, Modifier.padding(start = 4.dp, bottom = 6.dp),
                    style = MikaType.Value, fontWeight = FontWeight.Medium, color = c.ink2,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(tr(R.string.usage_input), UsageCopy.tokens(t.input), Modifier.weight(1f))
                Stat(tr(R.string.usage_output), UsageCopy.tokens(t.output), Modifier.weight(1f))
                Stat(tr(R.string.usage_cached), UsageCopy.tokens(t.cached), Modifier.weight(1f))
                Stat(tr(R.string.usage_calls), UsageCopy.calls(t.calls), Modifier.weight(1f))
            }
        }
    }
}

private val TotalStyle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 1)
        Text(
            value, Modifier.padding(top = 2.dp), style = MikaType.Value, fontWeight = FontWeight.SemiBold,
            color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 写真付きのカード: 左側にタイトル、右側に短い文章。 */
@Composable
private fun ChartCard(title: String, hint: String, content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    MikaCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = CardTitle, color = c.ink)
                Text(hint, style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

private val CardTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

/** 金額を使用しない場合: 中央に配置された指示カード。 */
@Composable
private fun EmptyCard() {
    val c = MikaTheme.colors
    MikaCard(Modifier.testTag("usage-empty")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(MikaIcons.Chart)
            Text(UsageCopy.EMPTY_TITLE, Modifier.padding(top = 12.dp), style = MikaType.BodyStrong, color = c.ink)
            Text(UsageCopy.EMPTY_BODY, Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.ink3, textAlign = TextAlign.Center)
        }
    }
}

/**
 * ランキングの行: 名前、水平バー (色はサービス プロバイダーの色)、使用状況、および割合。 [dim] の場合、行全体が明るくなります (別のサービス プロバイダーが選択されています)。[bold] の場合、名前が太字になります (選択されています)。
 * [muted] の名前は、補助文字色 (削除されたサービス プロバイダー) を使用します。 サービスプロバイダーを切り替えるための
 */
@Composable
private fun RankRow(
    name: String, mono: Boolean, muted: Boolean, color: Color, fraction: Float, tokens: Long, share: Double,
    dim: Boolean = false, bold: Boolean = false, onClick: (() -> Unit)? = null, tag: String,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RowShape).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (dim) 0.4f else 1f)
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            name, Modifier.width(118.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = (if (mono) RankMono else RankText).let { if (bold) it.copy(fontWeight = FontWeight.SemiBold) else it },
            color = if (muted) c.ink3 else c.ink,
        )
        Box(Modifier.weight(1f).height(14.dp)) {
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val w = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(2.dp.toPx())
                val r = 4.dp.toPx().coerceAtMost(w / 2)
                val path = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, size.height, topRightCornerRadius = CornerRadius(r), bottomRightCornerRadius = CornerRadius(r)))
                }
                drawPath(path, color)
            }
        }
        Column(Modifier.width(74.dp), horizontalAlignment = Alignment.End) {
            Text(UsageCopy.tokens(tokens), style = RankValue, color = c.ink, maxLines = 1)
            Text(UsageCopy.percent(share), style = RankShare, color = c.ink3, maxLines = 1)
        }
    }
}

private val RowShape = RoundedCornerShape(8.dp)
private val RankText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp)
private val RankMono = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
private val RankValue = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
private val RankShare = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)

/** カプセル: 選択すると、色が強調され、単色の背景が強調表示されます。 [deleted] の名前が薄くなり、その後に「(削除)」と表示されます。 */
@Composable
private fun UsageChip(text: String, selected: Boolean, onClick: () -> Unit, deleted: Boolean = false, tag: String) {
    val c = MikaTheme.colors
    val fg = when {
        selected -> c.onAccent
        deleted -> c.ink3
        else -> c.ink
    }
    Box(
        Modifier.height(34.dp)
            .then(if (selected) Modifier.background(c.accent, MikaShapes.Pill) else Modifier.cardSurface(c, MikaShapes.Pill))
            .clip(MikaShapes.Pill)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (deleted) text + UsageCopy.DELETED else text, maxLines = 1, color = fg, style = ChipText)
    }
}

private val ChipText = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

/** はトレンド チャートのセグメントです。各サービス プロバイダーには独自の色があり ([slot] は色番号)、残りは「その他」に結合されます ([slot] は null)。 */
private data class StackPart(val slot: Int?, val tokens: Long)

/**
 * の使用傾向: グリッドごとに 1 つの柱、サービス プロバイダーに従って積み重ねられ (色付きの柱が下部、「その他」が上部)、セグメントと列の間に 2 dp の間隔があり、各列の上部に 4 dp の丸い角が残ります。
 * 右側の列をそのままにして、2 つの点線グリッドのスケール (整数の上半分と半分) を書き込みます。 1 つをクリックすると、このグリッドの合計と各世帯の消費量が上部に書き込まれ、もう一度クリックしてグリッドを閉じます。
 * サービス プロバイダーを選択すると、他のプロバイダーのパフォーマンスは 4 分の 1 に低下しました。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendChart(report: UsageReport, selected: String?, today: LocalDate) {
    val c = MikaTheme.colors
    val series = report.series
    if (series.isEmpty()) return
    val byId = report.providers.associateBy { it.id }
    val colored = report.providers.filter { UsageCopy.colorSlot(it) != null }.sortedBy { it.order }
    val hasOthers = report.providers.any { UsageCopy.colorSlot(it) == null && it.totals.tokens > 0 }
    val selectedSlot = selected?.let(byId::get)?.let { UsageCopy.colorSlot(it) ?: -1 }
    // 下から順に色分けされたアイテムが表示され、「その他」が上に表示されます。
    val slots: List<Int?> = colored.map(UsageCopy::colorSlot) + if (hasOthers) listOf(null) else emptyList()
    val stacks = series.map { b ->
        val bySlot = b.byProvider.entries.groupBy { byId[it.key]?.let(UsageCopy::colorSlot) }
        slots.map { s -> StackPart(s, bySlot[s].orEmpty().sumOf { it.value }) }.filter { it.tokens > 0 }
    }
    val nice = UsageCopy.niceMax(series.maxOf { it.tokens })
    var tapped by remember(series) { mutableStateOf<Int?>(null) }
    val gutter = 38.dp

    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        colored.forEach { p -> LegendItem(c.series[UsageCopy.colorSlot(p)!!], UsageCopy.providerName(p)) }
        if (hasOthers) LegendItem(c.seriesOther, UsageCopy.OTHERS)
    }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(132.dp)) {
        Canvas(
            Modifier.fillMaxSize().testTag("usage-chart")
                .semantics { contentDescription = "${UsageCopy.TREND}，${UsageCopy.stepLabel(report.step)}" }
                .pointerInput(series) {
                    detectTapGestures { o ->
                        val plot = size.width - gutter.toPx()
                        if (o.x > plot) return@detectTapGestures
                        val i = (o.x / (plot / series.size)).toInt().coerceIn(0, series.lastIndex)
                        tapped = if (tapped == i) null else i
                    }
                },
        ) {
            val plot = size.width - gutter.toPx()
            val h = size.height
            val gap = 2.dp.toPx()
            val n = series.size
            val colW = ((plot - gap * (n - 1)) / n).coerceAtLeast(1f)
            val step = if (n > 1) (plot - colW) / (n - 1) else 0f
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            listOf(0f, 0.5f).forEach { y -> drawLine(c.grid, Offset(0f, h * y), Offset(plot, h * y), 1.dp.toPx(), pathEffect = dash) }
            drawLine(c.grid, Offset(0f, h), Offset(plot, h), 1.dp.toPx())
            stacks.forEachIndexed { i, segs ->
                val x = i * step
                var bottom = h
                segs.forEachIndexed { k, s ->
                    val top = k == segs.lastIndex
                    val segH = h * s.tokens / nice
                    val drawn = if (top) segH else (segH - gap).coerceAtLeast(0.5f)
                    val base = if (s.slot != null) c.series.getOrNull(s.slot) ?: c.seriesOther else c.seriesOther
                    val color = if (selectedSlot != null && selectedSlot != (s.slot ?: -1)) base.copy(alpha = base.alpha * 0.25f) else base
                    if (top) {
                        val r = 4.dp.toPx().coerceAtMost(colW / 2).coerceAtMost(drawn)
                        drawPath(
                            Path().apply {
                                addRoundRect(RoundRect(x, bottom - drawn, x + colW, bottom, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
                            },
                            color,
                        )
                    } else {
                        drawRect(color, Offset(x, bottom - drawn), Size(colW, drawn))
                    }
                    bottom -= segH
                }
            }
        }
        // グリッドスケールは右の列に記載されています
        GridLabel(UsageCopy.tokens(nice), Modifier.align(Alignment.TopEnd).offset(y = (-7).dp))
        GridLabel(UsageCopy.tokens(nice / 2), Modifier.align(Alignment.CenterEnd).offset(y = (-7).dp))
        tapped?.let { i -> TrendTip(series[i], report, i, gutter, today) }
    }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, end = gutter)) {
        val style = MikaType.Hint
        Text(UsageCopy.axis(series.first(), report.step, today), Modifier.align(Alignment.CenterStart), style = style, color = c.ink3)
        if (series.size > 2) {
            Text(UsageCopy.axis(series[series.size / 2], report.step, today), Modifier.align(Alignment.Center), style = style, color = c.ink3)
        }
        if (series.size > 1) {
            Text(UsageCopy.axis(series.last(), report.step, today, last = true), Modifier.align(Alignment.CenterEnd), style = style, color = c.ink3)
        }
    }
}

@Composable
private fun LegendItem(color: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, LegendShape))
        Text(name, style = MikaType.Hint, color = MikaTheme.colors.ink2, maxLines = 1)
    }
}

private val LegendShape = RoundedCornerShape(3.dp)

@Composable
private fun GridLabel(text: String, modifier: Modifier) {
    Text(text, modifier.width(34.dp), style = GridText, color = MikaTheme.colors.ink3, textAlign = TextAlign.End, maxLines = 1)
}

private val GridText = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp)

/** 柱をクリックするときの手順: 以下の各サービス プロバイダーの合計期間が 1 つの行に表示されます (最も多いものから最も少ないものまで)。その柱を水平に向け、写真の左右は映さないようにしてください。 */
@Composable
private fun TrendTip(b: UsageBucket, report: UsageReport, index: Int, gutter: Dp, today: LocalDate) {
    val c = MikaTheme.colors
    val rows = b.byProvider.entries.sortedByDescending { it.value }
    Column(
        Modifier.layout { m, cons ->
            val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
            layout(cons.maxWidth, p.height) {
                val plot = cons.maxWidth - gutter.roundToPx()
                val center = plot / report.series.size.toFloat() * (index + 0.5f)
                p.place((center - p.width / 2f).toInt().coerceIn(0, (cons.maxWidth - p.width).coerceAtLeast(0)), 0)
            }
        }.background(c.ink, TipShape).padding(horizontal = 9.dp, vertical = 6.dp).testTag("usage-chart-tip"),
    ) {
        Text(
            "${UsageCopy.period(b, report.step, today)} · ${UsageCopy.tokens(b.tokens)}",
            style = TipText, fontWeight = FontWeight.SemiBold, color = c.bg, maxLines = 1,
        )
        rows.forEach { (id, v) ->
            val name = report.provider(id)?.let(UsageCopy::providerName) ?: id
            Text("$name　${UsageCopy.tokens(v)}", style = TipText, color = c.bg, maxLines = 1)
        }
    }
}

private val TipShape = RoundedCornerShape(8.dp)
private val TipText = TextStyle(fontSize = 11.5.sp, lineHeight = 18.sp)

/** カスタム範囲の日付選択: 元帳がまだ保持されている日 (最新の [UsageLedger.KEEP_DAYS] 日) から今日までのみ選択できます。両端を選択した場合のみ表示できます。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageRangePicker(today: LocalDate, from: LocalDate?, to: LocalDate?, onPicked: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    // 日付ピッカーの日付は UTC ゼロからのミリ秒数です
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val earliest = today.minusDays(UsageLedger.KEEP_DAYS.toLong())
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (from ?: today.minusDays(6)).let(::millis),
        initialSelectedEndDateMillis = (to ?: today).let(::millis),
        yearRange = earliest.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = date(utcTimeMillis).let { it >= earliest && it <= today }
            override fun isSelectableYear(year: Int) = year in earliest.year..today.year
        },
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ if (start != null && end != null) onPicked(date(start), date(end)) }, enabled = start != null && end != null) {
                Text(tr(R.string.view))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    ) {
        DateRangePicker(state, Modifier.weight(1f), showModeToggle = false)
    }
}
