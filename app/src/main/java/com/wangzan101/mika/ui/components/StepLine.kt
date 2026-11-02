package com.wangzan101.mika.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaTheme

/** 垂直線ステップの前のポイントのステータス: 完了は灰色のポイント、現在は絞りのある青色のポイント、一時停止はオレンジ色のポイント、エラーは赤色のポイントです。 */
enum class StepState { Done, Current, Paused, Failed }

private fun StepState.color(c: MikaColors) = when (this) {
    StepState.Done -> c.ink3
    StepState.Current -> c.accent
    StepState.Paused -> c.warnSolid
    StepState.Failed -> c.bad
}

private fun StepState.halo(c: MikaColors) = when (this) {
    StepState.Done -> null
    StepState.Current -> c.accentSoft
    StepState.Paused -> c.warnSoft
    StepState.Failed -> c.badSoft
}

/**
 * 垂直線ステップの 1 ステップ: 左側の 1.5 dp の細い線が各ステップを通過し、[dotY] の高さで点描されます (テキストの最初の行の中心線に揃えられます)。
 * 最初のステップの線は点から始まり、最後のステップの線は点で終わります。ステップ間の線は連続しています。コンテンツは右側に配置され、複数行にすることができます。
 */
@Composable
fun StepItem(
    state: StepState,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    dotY: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier.width(GutterWidth).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val y = dotY.toPx()
                val top = if (first) y else 0f
                val bottom = if (last) y else size.height
                drawLine(c.hair, Offset(x, top), Offset(x, bottom), strokeWidth = 1.5.dp.toPx())
                state.halo(c)?.let { drawCircle(it, 7.dp.toPx(), Offset(x, y)) }
                drawCircle(state.color(c), 4.dp.toPx(), Offset(x, y))
            },
        )
        Column(Modifier.weight(1f), content = content)
    }
}

private val GutterWidth = 20.dp
