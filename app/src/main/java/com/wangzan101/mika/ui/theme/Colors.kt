package com.wangzan101.mika.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * インターフェイスで使用されるすべての色。ページは常に [MikaTheme.colors] から取得され、デッド カラー値は書き込まれません。
 * 単色は強調 (メイン ボタン、選択、スイッチ、ユーザー メッセージ) とステータス (成功、必要、エラー) にのみ使用されます。アイコン ブロックは常に中立です。
 */
@Immutable
class MikaColors(
    /** ページの背景。 */
    val bg: Color,
    /** カード、グループ。 */
    val surface: Color,
    /** 浅い塗りつぶし: サブボタン、セグメント化されたコントロールの下部、コード ブロックの下部。 */
    val fill: Color,
    /** 本文、副本文、補助本文の 3 つのレベルのテキスト。 */
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** ディバイダー。 */
    val hair: Color,
    /** カードのストローク。 */
    val edge: Color,
    val accent: Color,
    val accentSoft: Color,
    /** アクセントカラーのテキストとアイコン。 */
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    /** "Need You": [warn] はテキストに使用され、[warnSolid] はドットとストロークに使用されます。 */
    val warn: Color,
    val warnSolid: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    /** アイコンブロックの下部。 */
    val neutralSoft: Color,
    /** ミカの答え: ページの背景より少し濃いグレーで、ユーザーの青いバブルとは異なります。 */
    val answer: Color,
    /** 灰色のカプセル、閉じたスイッチ。 */
    val greySoft: Color,
    /** フローティング ガラスの底部とストローク (入力フィールド、コンテンツ上にフローティングするボタン)。 */
    val glass: Color,
    val glassEdge: Color,
    /** カード シャドウ;暗い色の場合、カードはストロークのみに依存し、透明になります。 */
    val cardShadow: Color,
    /** 吊り下げられた要素の影。 */
    val floatShadow: Color,
    /** スイッチの丸いボタン。 */
    val thumb: Color,
    /**
     * チャート内で分類された色 (たとえば、サービス プロバイダーごとに 1 つ) は、固定された順序で取得されます。余分な色は [seriesOther] に分類されます。
     * はグラフィックのみに色を付け、すべてのテキストはインク レベル 3 を使用します。
     */
    val series: List<Color>,
    val seriesOther: Color,
    /** グラフのグリッド線。 */
    val grid: Color,
    val isDark: Boolean,
)

val LightColors = MikaColors(
    bg = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    fill = Color(0x0A000000),
    ink = Color(0xFF1C1C1E),
    ink2 = Color(0xFF5F6068),
    ink3 = Color(0xFF9A9BA3),
    hair = Color(0x12000000),
    edge = Color(0x0D000000),
    accent = Color(0xFF0A7CFF),
    accentSoft = Color(0x1A0A7CFF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF1F9D55),
    okSoft = Color(0x1F1F9D55),
    warn = Color(0xFFB86E00),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x1FFF9F0A),
    bad = Color(0xFFE5483D),
    badSoft = Color(0x1AE5483D),
    neutralSoft = Color(0x0B000000),
    answer = Color(0x09000000),
    greySoft = Color(0x1F767680),
    glass = Color(0xB8FFFFFF),
    glassEdge = Color(0x0F000000),
    cardShadow = Color(0xFF000000),
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
    seriesOther = Color(0xFFB4B5BC),
    grid = Color(0x0F000000),
    isDark = false,
)

val DarkColors = MikaColors(
    bg = Color(0xFF0E0E10),
    surface = Color(0xFF1A1A1D),
    fill = Color(0x0FFFFFFF),
    ink = Color(0xFFF2F2F4),
    ink2 = Color(0xFFA4A5AD),
    ink3 = Color(0xFF6C6D75),
    hair = Color(0x14FFFFFF),
    edge = Color(0x0FFFFFFF),
    accent = Color(0xFF3D97FF),
    accentSoft = Color(0x293D97FF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF3CCF7A),
    okSoft = Color(0x243CCF7A),
    warn = Color(0xFFFFB340),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x29FF9F0A),
    bad = Color(0xFFFF6961),
    badSoft = Color(0x24FF6961),
    neutralSoft = Color(0x12FFFFFF),
    answer = Color(0x0DFFFFFF),
    greySoft = Color(0x38767680),
    glass = Color(0xB81A1A1D),
    glassEdge = Color(0x14FFFFFF),
    cardShadow = Color.Transparent,
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
    seriesOther = Color(0xFF5A5B62),
    grid = Color(0x12FFFFFF),
    isDark = true,
)

/** が [MikaTheme] に含まれていない場合（インターフェイステストなど）、明るい色を押します。 */
val LocalMikaColors = staticCompositionLocalOf { LightColors }
