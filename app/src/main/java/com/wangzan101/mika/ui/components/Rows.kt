package com.wangzan101.mika.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** グループサブタイトル: 13 / 500、補助文字色。 */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp, bottom = 8.dp), style = MikaType.Label, color = MikaTheme.colors.ink3)
}

/** 以下の指示をグループ化し、カード内のテキストと合わせます。 */
@Composable
fun FooterNote(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        style = MikaType.Hint, color = color.takeOrElse { MikaTheme.colors.ink3 },
    )
}

/** アイコン ブロックの背景色とグラフィックの色。デフォルトはニュートラルです。ステータスのみ色が付きます。 */
enum class TileTone {
    Neutral, Accent, Ok, Warn, Bad;

    fun colors(c: MikaColors): Pair<Color, Color> = when (this) {
        Neutral -> c.neutralSoft to c.ink
        Accent -> c.accentSoft to c.accent
        Ok -> c.okSoft to c.ok
        Warn -> c.warnSoft to c.warn
        Bad -> c.badSoft to c.bad
    }
}

/** 行の左側のアイコン ブロック: 30 dp、丸い角 9、内側のグラフィック 18 dp。 */
@Composable
fun IconTile(icon: ImageVector, tone: TileTone = TileTone.Neutral) {
    val (bg, fg) = tone.colors(MikaTheme.colors)
    Box(Modifier.size(MikaSize.Tile).background(bg, MikaShapes.Tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(MikaSize.TileIcon), tint = fg)
    }
}

/** 行間の分割線は、[start] から右に引かれます。グループの最初の行 ([first]) は描画されません。 */
private fun Modifier.topHairline(first: Boolean, start: Dp, color: Color): Modifier =
    if (first) this
    else drawBehind {
        val y = 0.25.dp.toPx()
        drawLine(color, Offset(start.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
    }

/** アイコン ブロックがある場合、テキストはここから始まります: 左マージン 16 + アイコン ブロック 30 + スペース 14。 */
private val TextStartWithIcon = 60.dp
private val TextStartPlain = 16.dp

/**
 * リスト行: 左側にオプションのアイコン ブロック、中央にタイトルと説明、右側に [trailing] (値、カプセル、スイッチ、矢印)。
 * [detail] は次の行を説明する小さなテキスト (「最後の結果」など) で、色は [detailColor] によって決まります。
 * [first] がグループの最初の行である場合、分割線は描画されません。 [onClick] が空の場合、行全体をクリックすることはできません。
 */
@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    first: Boolean = false,
    titleColor: Color = Color.Unspecified,
    tone: TileTone = TileTone.Neutral,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .topHairline(first, if (icon != null) TextStartWithIcon else TextStartPlain, c.hair)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = MikaSize.RowMin)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconTile(icon, tone)
        Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
            Text(title, style = MikaType.Body, color = titleColor.takeOrElse { c.ink })
            if (!subtitle.isNullOrEmpty()) Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            if (!detail.isNullOrEmpty()) Text(detail, Modifier.padding(top = 2.dp), style = MikaType.Hint, color = detailColor.takeOrElse { c.ink3 })
        }
        trailing()
    }
}

enum class ValueTone { Plain, Ok, Warn, Bad, Link }

/** 行末の値。 */
@Composable
fun RowValue(text: String, tone: ValueTone = ValueTone.Plain) {
    val c = MikaTheme.colors
    Text(
        text, style = MikaType.Value,
        fontWeight = if (tone == ValueTone.Link) FontWeight.Medium else FontWeight.Normal,
        color = when (tone) {
            ValueTone.Plain -> c.ink3
            ValueTone.Ok -> c.ok
            ValueTone.Warn -> c.warn
            ValueTone.Bad -> c.bad
            ValueTone.Link -> c.accent
        },
        maxLines = 1,
    )
}

/** 行の最後にある小さな矢印: クリックすると、別のページが表示されます。 */
@Composable
fun RowChevron() {
    DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(MikaSize.SmallIcon), tint = MikaTheme.colors.ink3)
}

/** 単一選択行: 左側に円が表示され、選択すると単色のアクセント カラーになります。区切り線はテキストから始まります。 */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    first: Boolean = false,
    enabled: Boolean = true,
    titleStyle: TextStyle = MikaType.Body,
    /** タイトルの下の追加コンテンツ (小さな機能ラベルなど)。 */
    below: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .topHairline(first, 50.dp, c.hair)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioMark(selected, enabled)
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(title, style = titleStyle, color = if (enabled) c.ink else c.ink3)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            if (below != null) Box(Modifier.padding(top = 4.dp)) { below() }
        }
        trailing()
    }
}

@Composable
private fun RadioMark(selected: Boolean, enabled: Boolean = true) {
    val c = MikaTheme.colors
    if (selected && enabled) {
        Box(Modifier.size(22.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(c.onAccent, CircleShape))
        }
    } else {
        Box(Modifier.size(22.dp).border(1.6.dp, c.hair, CircleShape))
    }
}

/**
 * カードの入力行: 左側に固定幅のタイトル、右側に単一行の入力ボックス (空の場合は [placeholder] が表示されます)。
 * [secret] が true の場合、パスワードに従って表示されます。 [trailing] は入力ボックス (たとえば、「表示」) の右側に配置されます。
 */
@Composable
fun InputRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    first: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    labelWidth: Dp = 84.dp,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().topHairline(first, TextStartPlain, c.hair).keepsKeyboard()
            .heightIn(min = MikaSize.RowMin).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(labelWidth), style = MikaType.Body, color = c.ink)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MikaType.Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                value, onChange, modifier.fillMaxWidth(), singleLine = true,
                textStyle = MikaType.Body.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
        if (trailing != null) Box(Modifier.padding(start = 8.dp)) { trailing() }
    }
}

/** カード内の自己入力ブロック間の分割線は、[start] から右に引かれます。 */
@Composable
fun Hairline(start: Dp = TextStartWithIcon) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(0.5.dp).background(MikaTheme.colors.hair))
}
