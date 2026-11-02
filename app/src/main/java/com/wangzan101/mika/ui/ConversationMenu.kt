package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 長押しメニューを配置する場所: 盛り上がった行の上端 (メニューをドロップするために移動することもできます)、メニューの上端、およびメニューがその行にあるかどうか。 */
internal data class MenuPlacement(val rowTop: Float, val menuTop: Float, val above: Boolean)

/**
 * メニューの場所を長押しします。 [top] と [bottom] は、物を配置できる上側と下側です (ステータス バー、ナビゲーション バー、およびキーボードを削除します)。 まず、
 * 行をこの範囲に移動します。メニューが下部に収まる場合は、その下に配置します。それ以外の場合、上に収まる場合は上に置きます。どちらの側にも収まらない場合 (キーボードが画面の半分以上を占める場合)、その行を上に移動します。
 * その下にメニューが接続されています。それが十分でない場合は、その行を一番上に押し上げます。
 */
internal fun placeMenu(rowTop: Float, rowHeight: Float, menuHeight: Float, gap: Float, top: Float, bottom: Float): MenuPlacement {
    val row = rowTop.coerceIn(top, max(top, bottom - rowHeight))
    if (row + rowHeight + gap + menuHeight <= bottom) return MenuPlacement(row, row + rowHeight + gap, above = false)
    if (row - gap - menuHeight >= top) return MenuPlacement(row, row - gap - menuHeight, above = true)
    val moved = max(top, bottom - menuHeight - gap - rowHeight)
    return MenuPlacement(moved, moved + rowHeight + gap, above = false)
}

/**
 * サイドバーで会話を長押しした後のメニュー ([LongPressMenu] を参照): その行が白いカードに表示され、メニューには固定/固定解除、名前の変更、赤いテキストの削除が含まれます。
 * [bounds] は、ウィンドウ内のその線の位置とサイズです。
 */
@Composable
internal fun ConversationMenu(
    item: DrawerConversation,
    bounds: Rect,
    progress: () -> Float,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    LongPressMenu(
        bounds, progress, pane = stringResource(R.string.drawer_actions), tag = "drawer-menu",
        entries = listOf(
            MenuEntry(stringResource(if (item.pinned) R.string.unpin else R.string.pin), MikaIcons.Pin, "drawer-menu-pin", onClick = onPin),
            MenuEntry(stringResource(R.string.drawer_rename), MikaIcons.Edit, "drawer-menu-rename", onClick = onRename),
            MenuEntry(stringResource(R.string.confirm_delete), MikaIcons.Trash, "drawer-menu-delete", c.bad, onDelete),
        ),
        onDismiss = onDismiss,
    ) { LiftedRow(item) }
}

/** メニュー内の項目を長押しします: テキスト、右側のアイコン、テスト ラベル、色 (NULL はテキストの色)。 */
internal class MenuEntry(val text: String, val icon: ImageVector, val tag: String, val color: Color? = null, val onClick: () -> Unit)

/**
 * 何かを長押しした後にポップアップするメニュー (サイドバーの会話と会話ページのメッセージで共有): 背後の画面全体が暗くなります (Android 12 以降ではまだぼやけていて、背後のコンテンツは外部から追加されています。[MenuBlur] を参照)、
 * は、所定の位置にあるものを「持ち上げ」 ([lifted]、白いカードかそれに似たものとして描き、わずかに拡大します)、その下に (置くことができない場合は、上に置くだけです) 狭い白いカードに [entries] がリストされ、左側にテキスト、右側にアイコンが表示されます。
 * 外側をクリックし、Return キーを押して閉じます。 [progress] は出現の進行状況（0～1）です。外側のボタンを押してアニメーションをオフにすると、その位置に移動するか直接移動するかが決まります。
 * は描画および配置中にのみ読み取られ、アニメーション中に再構成されません。 [bounds] はウィンドウ内のそのものの位置とサイズです。持ち上げられたものの幅はそれと同じで、高さはそれ自体のコンテンツと同じです。
 * の半分の画面が最も高くなります (非常に長い回答の場合は、[lifted] で切り取られた最初のセクションのみを持ち上げてください)。 [alignEnd] を使用すると、メニューは右揃え (ユーザー自身の言葉で言えば右揃え) になります。
 */
@Composable
internal fun LongPressMenu(
    bounds: Rect,
    progress: () -> Float,
    pane: String,
    tag: String,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    alignEnd: Boolean = false,
    lifted: @Composable () -> Unit,
) {
    val c = MikaTheme.colors
    val insets = WindowInsets.safeDrawing
    val view = LocalView.current
    val close = stringResource(R.string.collapse)
    // ぼかせるときは後ろがすでにぼけているので、暗くするだけです。ぼかせないときは後ろを暗くして抑えましょう。
    val dim = c.floatShadow.copy(alpha = if (GlassBlurSupported) 0.16f else 0.32f)
    BackHandler(onBack = onDismiss)
    Layout(
        content = {
            lifted()
            MenuCard {
                entries.forEachIndexed { i, e ->
                    if (i > 0) MenuDivider(c.hair)
                    MenuItem(e.text, e.icon, e.tag, e.color ?: c.ink, e.onClick)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
            .drawBehind { drawRect(dim.copy(alpha = dim.alpha * progress())) }
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .semantics {
                paneTitle = pane
                isTraversalGroup = true
                dismiss(close) { onDismiss(); true }
            }
            .testTag(tag),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val rowW = bounds.width.roundToInt().coerceIn(0, w)
        val lifted = measurables[0].measure(Constraints(minWidth = rowW, maxWidth = rowW, maxHeight = (h / 2).coerceAtLeast(0)))
        val menuW = min(MenuWidth.roundToPx(), w).coerceAtLeast(0)
        val menu = measurables[1].measure(Constraints(minWidth = menuW, maxWidth = menuW, maxHeight = h))
        layout(w, h) {
            // その行の位置はウィンドウ座標です。このレイヤーは必ずしもウィンドウの左上隅から始まるとは限りません (システム バーの下部まで広がっていない場合)。
            val origin = coordinates?.positionInWindow() ?: Offset.Zero
            val top = max(0f, insets.getTop(this@Layout) - origin.y)
            val bottom = min(h.toFloat(), view.rootView.height - insets.getBottom(this@Layout) - origin.y)
            val at = placeMenu(bounds.top - origin.y, lifted.height.toFloat(), menu.height.toFloat(), MenuGap.toPx(), top, bottom)
            val x = (bounds.left - origin.x).roundToInt()
            val menuX = (if (alignEnd) x + rowW - menuW else x).coerceIn(0, max(0, w - menuW))
            lifted.placeWithLayer(x, at.rowTop.roundToInt()) {
                scaleX = 1f + LiftScale * progress()
                scaleY = scaleX
            }
            menu.placeWithLayer(menuX, at.menuTop.roundToInt()) {
                val p = progress()
                alpha = p
                scaleX = 0.92f + 0.08f * p
                scaleY = scaleX
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, if (at.above) 1f else 0f)
            }
        }
    }
}

/** 盛り上がった行: 横列の行と同じ内容ですが、白い背景、丸い角、影に置き換えられます。 */
@Composable
private fun LiftedRow(item: DrawerConversation) {
    val c = MikaTheme.colors
    Box(
        Modifier
            .floatShadow(c, NavShape)
            .background(c.surface, NavShape)
            .clip(NavShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        ConversationRowContent(item)
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.floatShadow(c, MikaShapes.Card).clip(MikaShapes.Card)
            .background(c.surface)
            .border(0.5.dp, c.edge, MikaShapes.Card)
            .semantics { popup() },
    ) { content() }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, tag: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MikaType.Body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(icon, null, Modifier.padding(start = 12.dp).size(MikaSize.Icon), tint = color)
    }
}

@Composable
private fun MenuDivider(color: Color) {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(color))
}

/** メニューが表示されたときに、バックレイヤーのコンテンツがどの程度ぼかされるか（Android 12以降）。 */
internal val MenuBlur = 14.dp
private val MenuWidth = 220.dp
private val MenuGap = 8.dp
private const val LiftScale = 0.03f
