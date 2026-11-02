package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.QuoteMessage
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 会話ページで長押しされたメッセージ: [key] それはどのメッセージに属しますか (「テキストの選択」で記録されます)、[from]、[text] はコピーおよび引用用のプレーン テキストであると誰が言いましたか
 * (答えはマークダウン記号を削除し、ユーザーの言葉は添付ファイル、画面、および引用行を削除します)、[bounds] はウィンドウ内での位置とサイズ、[lifted] は画像が上がったものです。
 */
internal class MessageTarget(
    val key: String,
    val from: QuoteMessage.From,
    val text: String,
    val bounds: Rect,
    val lifted: @Composable () -> Unit,
)

/** 会話ページ上のメッセージの長押しメニュー: [selecting] は、現在「テキストの選択」の [open] ポップアップ メニューにあるものです。 */
internal class MessageMenuHost(val selecting: String?, val open: (MessageTarget) -> Unit)

/** メッセージがない場合 (コンポーネントを個別にテストしている場合)、長押しするとテキストが選択されたままになります。 */
internal val LocalMessageMenu = compositionLocalOf<MessageMenuHost?> { null }

/**
 * メッセージのテキスト部分: 長押し (振動) すると、コピー/引用/テキストの選択メニューが表示されます。 「テキストの選択」を選択すると、システムの長押し選択に戻ります (選択上に [selectTag] がマークされます)。
 * テキスト内のリンクはまだクリックされています (クリックは最初に内部のテキストによって処理されます)。ファイルカードと「ビュープロセス」は含まれません。 [text] が空の場合、メニューはポップアップしません。
 * [modifier] は、このメッセージの下部 (背景、幅、パディング) です。持ち上げられたものは、その位置とサイズに従って配置されます。余白は外側に配置されます。 ウィンドウ内の
 */
@Composable
internal fun MessageArea(
    key: String,
    from: QuoteMessage.From,
    text: String,
    selectTag: String,
    modifier: Modifier = Modifier,
    lifted: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val host = LocalMessageMenu.current
    if (host == null || host.selecting == key || text.isBlank()) {
        SelectionContainer(modifier.testTag(selectTag)) { content() }
        return
    }
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.chat_message_actions)
    // の位置とサイズ (リストからロールアウトされる部分は切り取られません)。コールバックでのみ読み取るため、再編成は発生しません
    val bounds = remember { mutableStateOf(Rect.Zero) }
    // のジェスチャは常に最新のものを使用し (回答のストリーミング中に常に再編成されます)、再編成によって再起動されたり、途中で長押しが失われることはありません。
    val open by rememberUpdatedState({ host.open(MessageTarget(key, from, text, bounds.value, lifted)) })
    Box(
        Modifier
            .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
            .then(modifier)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    // はシステム内で長押しのように振動します。
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    open()
                })
            }
            .semantics { onLongClick(label) { open(); true } }
            .testTag("message-$key"),
    ) { content() }
}

/**
 * メッセージを長押しした後のメニュー (サイドバーの長押しダイアログと同じ、[LongPressMenu] を参照): メッセージが表示され、テキストのコピー、引用、選択が行われます。
 * 完成した回答は「ここから分岐」することもでき、ユーザーは自分の言葉で「ここから質問し直す」こともできます ([onBranch]、[onBranchRetry] を参照)。ユーザー自身の言葉が右側にあり、メニューも右側にあります。
 */
@Composable
internal fun MessageMenu(
    target: MessageTarget,
    progress: () -> Float,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onWithdraw: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onBranchRetry: (() -> Unit)? = null,
) {
    LongPressMenu(
        target.bounds, progress, pane = stringResource(R.string.chat_message_actions), tag = "message-menu",
        entries = buildList {
            add(MenuEntry(stringResource(R.string.chat_copy), MikaIcons.Copy, "message-menu-copy", onClick = onCopy))
            add(MenuEntry(stringResource(R.string.chat_quote), MikaIcons.Quote, "message-menu-quote", onClick = onQuote))
            add(MenuEntry(stringResource(R.string.chat_select_text), MikaIcons.SelectText, "message-menu-select", onClick = onSelect))
            onBranch?.let { add(MenuEntry(stringResource(R.string.chat_branch), MikaIcons.Route, "message-menu-branch", onClick = it)) }
            onBranchRetry?.let { add(MenuEntry(stringResource(R.string.chat_branch_retry), MikaIcons.Route, "message-menu-branch-retry", onClick = it)) }
            onEdit?.let { add(MenuEntry(stringResource(R.string.chat_edit_message), MikaIcons.Edit, "message-menu-edit", onClick = it)) }
            onWithdraw?.let { add(MenuEntry(stringResource(R.string.chat_withdraw_message), MikaIcons.Close, "message-menu-withdraw", onClick = it)) }
        },
        onDismiss = onDismiss,
        alignEnd = target.from == QuoteMessage.From.You,
        lifted = target.lifted,
    )
}

/** メニューが開いているときの背後のレイヤー: Android 12 以降、表示される進行状況はぼやけて表示され ([MenuBlur])、メニューはアクセス可能で読み取りのみ可能です。メニューを開いていない場合は何も追加されません。 */
internal fun Modifier.behindMenu(open: Boolean, progress: () -> Float): Modifier =
    if (!open) this else this
        .then(
            if (GlassBlurSupported) Modifier.graphicsLayer {
                val r = MenuBlur.toPx() * progress()
                renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
            } else Modifier,
        )
        .clearAndSetSemantics {}

/** メッセージのプレーン テキストをクリップボードに置き、「コピーされました」というプロンプトを表示します。 */
internal fun copyMessage(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mika", text))
    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
}

/** スクリーン リーダーに引用を読み上げる方法: 「Mika を引用:...」「あなたを引用:...」。 */
@Composable
private fun quoteDescription(q: QuoteMessage.Quote): String =
    stringResource(if (q.from == QuoteMessage.From.Mika) R.string.chat_quoting_mika else R.string.chat_quoting_you, q.text)

/** 入力ボックスの上に送信する見積書：左側に縦線、見積内容は2行まで、右側の×を消します。 */
@Composable
internal fun QuoteChip(quote: QuoteMessage.Quote, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val remove = stringResource(R.string.chat_quote_remove)
    val said = quoteDescription(quote)
    Row(
        modifier.fillMaxWidth().testTag("quote-chip").cardSurface(c, QuoteShape).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(30.dp).background(c.accent, CircleShape))
        Text(
            quote.text, Modifier.weight(1f).padding(start = 10.dp).semantics { contentDescription = said },
            style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = remove, onClick = onRemove)
                .semantics { contentDescription = remove }
                .testTag("quote-remove"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(14.dp), tint = c.ink3)
        }
    }
}

/** ユーザーの言葉で引用されているもの: バブルの上にある明るい色の小さな領域 (最大 2 行)。 */
@Composable
internal fun SentQuote(quote: QuoteMessage.Quote, maxWidth: Dp) {
    val c = MikaTheme.colors
    val said = quoteDescription(quote)
    Row(
        Modifier.padding(bottom = 4.dp).widthIn(max = maxWidth).background(c.ink.copy(alpha = 0.05f), QuoteShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .testTag("sent-quote"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(16.dp).background(c.ink3, CircleShape))
        Text(quote.text, Modifier.padding(start = 8.dp), style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val QuoteShape = RoundedCornerShape(12.dp)
