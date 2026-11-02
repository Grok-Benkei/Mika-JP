package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.keepsKeyboard
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.lazyListScrollbar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import com.wangzan101.mika.i18n.uiLocale

/** サイドバーの会話。 [id] は null です。これは、現在の会話が会話リストに保存されていないことを意味します。 */
data class DrawerConversation(
    val id: String?,
    val title: String,
    val subtitle: String,
    val running: Boolean,
    val current: Boolean,
    val enabled: Boolean,
    /** 最後に変更された時刻 (タイトルの右側に記載されています)。会話リストに保存されていない場合は空になります。 */
    val time: String = "",
    /** が固定されています: タイトルの前に小さな画鋲があります。メニューの長押しは「固定を解除」です。 */
    val pinned: Boolean = false,
    /** 会話は、1 つのページに面したブラウザ ページで始まりました。その Web サイト (ホスト名)、タイトルの後に小さな「Web ページ」タグが付いています。それはヌルではありません。 */
    val site: String? = null,
    /** 別の会話から分岐しました: タイトルの前の小さな分岐アイコン。 */
    val branch: Boolean = false,
)

/** サイドバーの会話セグメント: 進行中、固定済み、最近。 */
data class DrawerSections(
    val running: List<DrawerConversation>,
    val recent: List<DrawerConversation>,
    val pinned: List<DrawerConversation> = emptyList(),
)

/** 現在の会話がサイドバーにどのように表示されるか: 実行中のタスクがあるかどうか、タイトル、サブタイトル (何をしているのか)。 */
data class CurrentConversation(val id: String?, val running: Boolean, val title: String, val subtitle: String)

/**
 * サイドバーには 3 つのセクションがあります。「進行中」は、現在の会話 (タスクがある場合) と、タスクが実行されている他の会話を指します。 「固定」とは、一番上に固定された他の会話を指し、最新の会話が一番上に表示されます。
 * 「最近」は残りのダイアログで、最近変更されたものが最初に表示されます。各ダイアログは 1 つの段落にのみ表示されます。固定されたダイアログには、実行中に進行中のタスクが (押しピンで) リストされ、実行後に先頭に戻ります。
 * [canSwitch] が false (タスクが進行中であり、並列処理が有効になっていない) の場合、現在のダイアログのみをクリックできます。
 */
fun drawerSections(
    all: List<ConversationSummary>,
    current: CurrentConversation,
    others: List<String>,
    otherTitle: (String) -> String?,
    canSwitch: Boolean,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): DrawerSections {
    val byId = all.associateBy { it.id }
    fun timeOf(id: String?) = byId[id]?.let { conversationTime(it.updatedAt, now, zone) }.orEmpty()
    fun pinned(id: String?) = byId[id]?.pinnedAt != null
    val running = buildList {
        if (current.running) add(DrawerConversation(current.id, current.title.ifBlank { tr(R.string.chat_new) }, current.subtitle, true, true, true, time = timeOf(current.id), pinned = pinned(current.id), site = byId[current.id]?.page?.host, branch = byId[current.id]?.branchOf != null))
        others.filter { it != current.id }.distinct().forEach { id ->
            val title = (byId[id]?.title ?: otherTitle(id))?.let(ChatCopy::plainTitle)?.ifBlank { null } ?: tr(R.string.drawer_running_task)
            add(DrawerConversation(id, title, tr(R.string.drawer_running), running = true, current = false, enabled = canSwitch, time = timeOf(id), pinned = pinned(id), site = byId[id]?.page?.host, branch = byId[id]?.branchOf != null))
        }
    }
    val runningIds = running.mapNotNull { it.id }.toSet()
    fun entry(s: ConversationSummary): DrawerConversation {
        val isCurrent = s.id == current.id
        return DrawerConversation(s.id, ChatCopy.plainTitle(s.title), "", false, isCurrent, canSwitch || isCurrent, conversationTime(s.updatedAt, now, zone), pinned = s.pinnedAt != null,
            site = s.page?.host, branch = s.branchOf != null)
    }
    val rest = all.filter { it.id !in runningIds }
    val pinnedRows = rest.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }.map(::entry)
    val recent = rest.asSequence().filter { it.pinnedAt == null }.take(MAX_RECENT).map(::entry).toList()
    return DrawerSections(running, recent, pinnedRows)
}

/** 会話時間: 今日の時間を書き、昨日の「昨日」を書き、週の曜日を書き、今年の月と日を書き、前年を追加します。 */
fun conversationTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day: LocalDate = t.toLocalDate()
    val days = today.toEpochDay() - day.toEpochDay()
    return when {
        days <= 0L -> "%d:%02d".format(t.hour, t.minute)
        days == 1L -> tr(R.string.drawer_yesterday)
        days < 7L -> t.dayOfWeek.getDisplayName(DateTextStyle.SHORT, uiLocale())
        day.year == today.year -> tr(R.string.date_month_day, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
        else -> tr(R.string.date_year_month_day, day.year, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
    }
}

/** サイドバーの上部にあるミカの名前の下のステータス行と、それが良好であるか (緑色の点)、または処理する必要があるか (オレンジ色の点)。 */
fun readiness(check: Prerequisites.Check): Pair<String, Boolean> = when {
    !check.canStartTask -> tr(R.string.chat_cannot_start) to false
    !check.phoneReady -> tr(R.string.drawer_phone_not_ready) to false
    else -> tr(R.string.all_ready) to true
}

private const val MAX_RECENT = 50

/**
 * サイドバー: 左側からスライドさせて右側の角を丸くします。上部はロゴとステータスです。 4つの入り口。中央は進行中 (セクション タイトルの右側は「バックステージ スクリーン」ページに入ります) と最近の会話です。一番下は設定とほぼ横並びです。
 * は引き出し外側チューブにより開閉します。ここにはコンテンツのみが表示され、クリックされた内容はコールバックに渡されます。
 */
@Composable
fun ChatDrawer(
    check: Prerequisites.Check,
    sections: DrawerSections,
    canSwitch: Boolean,
    onNew: () -> Unit,
    onPlans: () -> Unit,
    onMemory: () -> Unit,
    onBrowser: () -> Unit,
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onOpen: (DrawerConversation) -> Unit,
    onAbout: () -> Unit = {},
    /** には新しいバージョンがあります。小さな赤い点が付いた「ミカについて」です。 */
    aboutDot: Boolean = false,
    /** 保存した会話を長押しすると、その場所に操作メニューがポップアップ表示されます (上部に固定、名前変更、削除)。ウィンドウ内のその行の位置とサイズが表示されます。 */
    onActions: (DrawerConversation, Rect) -> Unit = { _, _ -> },
) {
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxHeight().widthIn(max = DrawerWidth).fillMaxWidth()
            .clip(DrawerShape).background(c.bg, DrawerShape)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
            .testTag("drawer"),
    ) {
        val (state, ok) = readiness(check)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MikaLogo(36.dp, corner = 10.dp)
            Column {
                Text("Mika", style = MikaType.Title, color = c.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (ok) c.ok else c.warnSolid, CircleShape))
                    Text(state, style = MikaType.Hint, fontWeight = FontWeight.Medium, color = c.ink3)
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            NavEntry(MikaIcons.New, stringResource(R.string.chat_new), enabled = canSwitch, onClick = onNew)
            NavEntry(MikaIcons.Clock, stringResource(R.string.drawer_plans), onClick = onPlans)
            NavEntry(MikaIcons.Heart, stringResource(R.string.memory_and_skills), onClick = onMemory)
            NavEntry(MikaIcons.Globe, stringResource(R.string.drawer_browser), onClick = onBrowser)
        }
        // サイドバーが閉じられると、リストには最後の項目だけが残ります。サイドバーが開かれると、ダイアログがその前に挿入され、リストは元の項目に応じて一番下で停止します。先頭の項目が変わった場合は先頭に戻ります
        val list = rememberLazyListState()
        val first = when {
            sections.running.isNotEmpty() -> "h-running"
            sections.pinned.isNotEmpty() -> "h-pinned"
            sections.recent.isNotEmpty() -> "h-recent"
            else -> "end"
        }
        LaunchedEffect(first) { list.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).lazyListScrollbar(list), state = list) {
            if (sections.running.isNotEmpty()) {
                // [進行中] セクションの右側に移動して、[バックステージ画面] ページに入ります。実行中の各タスクにどのアプリとリアルタイム画面が使用されているかを確認します。
                item("h-running") { RunningLabel(onScreens) }
                items(sections.running, key = { "r-${it.id}" }) { ConversationEntry(it, onOpen, onActions) }
            }
            // 会話を変更できない場合、説明は最初に保存された会話 (固定または最近) の上に配置されます。
            val busyNote: LazyListScope.() -> Unit = {
                if (!canSwitch) {
                    item("busy") {
                        Text(
                            stringResource(R.string.drawer_busy), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                            style = MikaType.Hint, color = c.ink3,
                        )
                    }
                }
            }
            if (sections.pinned.isNotEmpty()) {
                item("h-pinned") { SectionLabel(stringResource(R.string.drawer_pinned)) }
                busyNote()
                items(sections.pinned, key = { "p-${it.id}" }) { ConversationEntry(it, onOpen, onActions) }
            }
            if (sections.recent.isNotEmpty()) {
                item("h-recent") { SectionLabel(stringResource(R.string.drawer_recent)) }
                if (sections.pinned.isEmpty()) busyNote()
                items(sections.recent, key = { "c-${it.id}" }) { ConversationEntry(it, onOpen, onActions) }
            }
            item("end") { Box(Modifier.height(12.dp)) }
        }
        // [設定] と [バージョン情報] はサイドバーの下部に並べて固定されています。会話がどれだけ多くても、それらは絞り出されることはありません。左側に設定、右側にバージョン情報があり、中央は空白のままです。
        Row(
            Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
                .padding(horizontal = 12.dp, vertical = 6.dp).testTag("drawer-footer"),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NavEntry(MikaIcons.Gear, stringResource(R.string.settings_title), Modifier, onClick = onSettings)
            NavEntry(MikaIcons.Info, AboutCopy.TITLE, Modifier, dot = aboutDot, onClick = onAbout)
        }
    }
}

@Composable
private fun NavEntry(
    icon: ImageVector, text: String, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, dot: Boolean = false, onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        modifier.height(46.dp).clip(NavShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MikaSize.Icon), tint = c.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MikaType.Body, color = c.ink, maxLines = 1, softWrap = false)
            if (dot) UpdateDot()
        }
    }
}

/** 「進行中」のセクション タイトルの右側に、バックグラウンド スクリーン ページに入る「バックグラウンド スクリーン ›」があります (このセクションはタスクが実行されている場合にのみ存在します)。 */
@Composable
private fun RunningLabel(onScreens: () -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.drawer_running)) }
        Row(
            Modifier.clip(NavShape).clickable(role = Role.Button, onClick = onScreens)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp).testTag("drawer-screens"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(MikaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink3)
            Text(stringResource(R.string.drawer_screens), style = MikaType.Hint, color = c.ink2)
            Icon(MikaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
        style = SectionText, color = MikaTheme.colors.ink3,
    )
}

/**
 * 会話: クリックしてください。長押し（振動あり）すると操作メニュー（トップ、名前変更、削除）が表示されます。会話を変更できない場合 (タスクが進行中で並列処理が有効になっていない場合)、クリックを開くことはできませんが、押し続けるとクリックは機能します。
 * は会話リストに保存されておらず (番号なし)、何も変更できず、長押ししても反応しません。固定されたタイトルの前に小さな画鋲があります。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationEntry(item: DrawerConversation, onOpen: (DrawerConversation) -> Unit, onActions: (DrawerConversation, Rect) -> Unit) {
    val c = MikaTheme.colors
    val deletable = item.id != null
    val haptic = LocalHapticFeedback.current
    // ウィンドウ内のこの行の位置とサイズ (リストから展開される部分は切り取られません)、メニューは盛り上がった行として配置されます。これはコールバックで読み取られるだけであり、再編成は発生しません。
    val bounds = remember { mutableStateOf(Rect.Zero) }
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
            .then(if (item.current) Modifier.cardSurface(c, NavShape) else Modifier)
            .clip(NavShape)
            .then(
                if (deletable) Modifier.combinedClickable(
                    onClickLabel = null,
                    onLongClickLabel = stringResource(R.string.drawer_actions),
                    // はシステム内で長押しのように振動します。
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions(item, bounds.value) },
                    onClick = { if (item.enabled) onOpen(item) },
                ) else Modifier.clickable(enabled = item.enabled, onClick = { onOpen(item) }),
            )
            .testTag("conversation-row")
            .alpha(if (item.enabled) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        ConversationRowContent(item)
    }
}

/** 会話内の内容: 進行中の点、画鋲、タイトル、時刻、および字幕がある場合は下の行。サイドバーの行と長押しで上がった行の両方に使用します。 */
@Composable
internal fun ConversationRowContent(item: DrawerConversation) {
    val c = MikaTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (item.running) Box(Modifier.size(7.dp).background(c.accent, CircleShape))
            if (item.pinned) Icon(MikaIcons.Pin, stringResource(R.string.drawer_pinned_mark), Modifier.size(14.dp).testTag("conversation-pin"), tint = c.ink3)
            if (item.branch) Icon(MikaIcons.Route, stringResource(R.string.chat_branch_origin), Modifier.size(14.dp).testTag("conversation-branch"), tint = c.ink3)
            Text(item.title, Modifier.weight(1f, fill = item.site == null), style = ConversationTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.site != null) {
                Text(
                    PageAskCopy.WEB, Modifier.background(c.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp).testTag("conversation-web"),
                    style = ConversationTag, color = c.accent, maxLines = 1,
                )
                Box(Modifier.weight(1f))
            }
            if (item.time.isNotEmpty()) {
                Text(item.time, Modifier.padding(start = 5.dp).testTag("conversation-time"), style = ConversationTime, color = c.ink3, maxLines = 1, softWrap = false)
            }
        }
        val sub = item.subtitle.ifEmpty { item.site.orEmpty() }
        if (sub.isNotEmpty()) {
            Text(sub, Modifier.padding(top = 2.dp), style = ConversationTime, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 会話の名前を変更します。現在のタイトルが入力ボックスに表示されます (段落全体を選択し、直接入力して置き換えます)。空白部分は保存できず、両端の空白部分は削除されます。 */
@Composable
internal fun RenameConversationDialog(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_rename_title)) },
        text = {
            KeyboardDismissArea {
                OutlinedTextField(
                    value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).keepsKeyboard().testTag("rename-conversation-field"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(value.text.trim()) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, Modifier.testTag("confirm-rename-conversation"), enabled = ok) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 会話を削除する前の確認: どの会話を削除するかを示します。実行中のタスクがある場合、またはスケジュールされたタスクがバインドされている場合は、それが一緒に停止および削除されることも示します。
 * 削除の確認は他と同じで、右側に赤い文字「削除」、左側に「キャンセル」が表示されます。
 */
@Composable
internal fun DeleteConversationDialog(title: String, impact: DeleteImpact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val body = listOfNotNull(
        stringResource(R.string.drawer_delete_body, title),
        if (impact.runningTask) stringResource(R.string.drawer_delete_running) else null,
        if (impact.schedules > 0) trPlural(R.plurals.drawer_delete_schedules, impact.schedules) else null,
    ).joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = { Text(body, Modifier.testTag("delete-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal const val MENU_IN_MS = 200
internal const val MENU_OUT_MS = 140
private val DrawerWidth = 304.dp
private val DrawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
internal val NavShape = RoundedCornerShape(12.dp)
private val SectionText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
private val ConversationTitle = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val ConversationTime = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val ConversationTag = TextStyle(fontSize = 10.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

/**
 * ダイアログ ページとそのサイドバー: 左上隅のメニューがサイドバーの左からスライドして外側にマスクのレイヤーが表示されます。マスクをクリックして左にスライドし、Return キーを押してマスクを閉じます。
 * サイドバーが開いている場合にのみ、会話リストを読み取ります。別のページに移動して戻ってくると、サイドバーが閉じます。 [content] 「サイドバーを開く」を取得し、メニューボタンに配置します。
 */
@Composable
fun ChatWithDrawer(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    reduceMotion: Boolean,
    conversations: () -> List<ConversationSummary>,
    /** ストレージ内の変更されたダイアログ (`ConversationStore.changes` を参照)。サイドバーを開いたときに他の場所に保存されたダイアログもその場で一覧表示されます。 */
    conversationChanges: Flow<String> = emptyFlow(),
    /** 会話に適切なタイトルを付けます。現在の会話がリストに保存されていないとき（タスクが開始されたばかりのとき）でも、名前を呼び出すことができます。 */
    conversationTitle: (String) -> String? = { null },
    onPlans: () -> Unit,
    onMemory: () -> Unit,
    onBrowser: () -> Unit,
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onAbout: () -> Unit = {},
    /** には新しいバージョンがあります。サイドバーの「ミカについて」に小さな赤い点があります。 */
    updateAvailable: Boolean = false,
    /** サイドバーから入ったページが戻ってきました。その場でサイドバーを開いて入る前の状態に戻ってください。 */
    reopen: Boolean = false,
    onReopened: () -> Unit = {},
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(reopen) {
        if (reopen) {
            drawer.snapTo(DrawerValue.Open)
            onReopened()
        }
    }
    val ui by vm.ui.collectAsState()
    val others by vm.otherRunning.collectAsState()
    fun close(then: () -> Unit = {}) {
        scope.launch {
            if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
        }
        then()
    }
    /** 別のページに移動します。まずその場でサイドバーを閉じてください。戻ってくると閉じられます。 */
    fun leave(go: () -> Unit) {
        scope.launch { drawer.snapTo(DrawerValue.Closed) }
        go()
    }
    val open = drawer.targetValue == DrawerValue.Open || drawer.isOpen
    // 会話ページに戻る（スライドする）ときは、最初にサイドバーを開いてください。サイドバーが開いたら、アプリに戻り、アプリから離れます (デスクトップに戻るのと同じように、バックグラウンドに戻ります)。
    // ダイアログページ内のパネルやピクチャインピクチャなどは内層に戻り、ここで最初に処理されます。
    val activity = LocalContext.current.findActivity()
    BackHandler {
        if (open) activity?.moveTaskToBack(true)
        else scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() }
    }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(conversationChanges) { conversationChanges.collect { revision++ } }
    val deleted by vm.deleted.collectAsState()
    /** 長押しされた項目とウィンドウ内のその位置。メニューが開きます (折りたたみアニメーションは再生後にのみクリアされます)。 */
    var acting by remember { mutableStateOf<Pair<DrawerConversation, Rect>?>(null) }
    /** メニューの外観の進行状況: 0 は存在しないことを意味し、1 は完全に存在しないことを意味します。アニメーションがオフになると、アニメーションは直接配置されます。 */
    val lift = remember { Animatable(0f) }
    LaunchedEffect(acting) {
        if (acting != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MikaMotion.Decelerate))
        }
    }
    /** メニューを閉じる: 表示されるアニメーションを逆方向に再生し、再生後にクリアします。 [then] その場で実行します (ポップアップ ウィンドウの名前変更と削除は、メニューが閉じるまで待つ必要はありません)。 */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MikaMotion.Decelerate))
            acting = null
        }
    }
    /** 名前が変更されるもの。 */
    var renaming by remember { mutableStateOf<DrawerConversation?>(null) }
    /** 削除したい記事と削除するとどうなるか。確認のポップアップウィンドウが開きます。 */
    var confirming by remember { mutableStateOf<Pair<DrawerConversation, DeleteImpact>?>(null) }
    val menuOpen = acting != null
    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            // メニューが開いているときは、その背後にある画面全体 (ダイアログ ページとサイドバー) がぼやけ (Android 12 以降。それ以前は暗くなるだけです)、メニューはアクセス可能な場合にのみ読み取れます。
            modifier = if (!menuOpen) Modifier else Modifier
                .then(
                    if (GlassBlurSupported) Modifier.graphicsLayer {
                        val r = MenuBlur.toPx() * lift.value
                        renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                    } else Modifier,
                )
                .clearAndSetSemantics {},
            drawerContent = {
                // は、ディスクが開かれている場合にのみディスクを読み取ります。開かれるたび、または開いている間にストレージ内でダイアログの変更があった場合、再読み込みされます。
                val all = remember(open, revision) { if (open) conversations() else emptyList() }
                val stored = remember(open, revision, ui.conversationId) { if (open) ui.conversationId?.let(conversationTitle) else null }
                val last = ui.exchanges.lastOrNull()
                val running = vm.busy()
                val current = CurrentConversation(
                    ui.conversationId, running,
                    stored ?: ChatCopy.title(ui.exchanges),
                    if (running) ChatCopy.subtitle(last, last?.let(vm::livePlace))?.first.orEmpty() else "",
                )
                val canSwitch = vm.canSwitch()
                // 削除された会話はその場にはリストされず、バックグラウンドでの削除が完了するまで待機しません。
                val sections = remember(all, current, others, canSwitch, deleted) {
                    drawerSections(all.filter { it.id !in deleted }, current, others.filter { it !in deleted }, vm::runningGoal, canSwitch, System.currentTimeMillis())
                }
                ChatDrawer(
                    check, sections, canSwitch,
                    onNew = { close { vm.newConversation() } },
                    onPlans = { leave(onPlans) },
                    onMemory = { leave(onMemory) },
                    onSettings = { leave(onSettings) },
                    onBrowser = { leave(onBrowser) },
                    onScreens = { leave(onScreens) },
                    onAbout = { leave(onAbout) },
                    aboutDot = updateAvailable,
                    onActions = { item, bounds -> if (item.id != null) acting = item to bounds },
                    onOpen = { item ->
                        close {
                            val id = item.id
                            when {
                                id == null || id == ui.conversationId -> Unit
                                item.running -> vm.switchTo(id)
                                else -> vm.openConversation(id)
                            }
                        }
                    },
                )
            },
            drawerState = drawer,
            // 会話ページで、左から右にスワイプしてサイドバーを引き出します (手でドラッグします)。左右にスクロールできるコンテンツ (幅の広い表、コード ブロックなど) が最初にスクロールされます。
            // 左端からのスライドインはシステムのリターン ジェスチャです。上記の返品プロセスにより、サイドバーも開きます。
            gesturesEnabled = true,
            scrimColor = MikaTheme.colors.floatShadow.copy(alpha = 0.28f),
        ) {
            content { scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() } }
        }
        acting?.let { (item, bounds) ->
            val id = item.id ?: return@let
            ConversationMenu(
                item, bounds, progress = { lift.value },
                onPin = { closeMenu { vm.setPinned(id, !item.pinned) } },
                onRename = { closeMenu { renaming = item } },
                onDelete = { closeMenu { confirming = item to vm.deleteImpact(id) } },
                onDismiss = { closeMenu() },
            )
        }
    }
    renaming?.let { item ->
        RenameConversationDialog(
            item.title,
            onSave = { t ->
                renaming = null
                item.id?.let { vm.renameConversation(it, t) }
            },
            onDismiss = { renaming = null },
        )
    }
    confirming?.let { (item, impact) ->
        DeleteConversationDialog(
            item.title, impact,
            onConfirm = {
                confirming = null
                item.id?.let(vm::deleteConversation)
            },
            onDismiss = { confirming = null },
        )
    }
}
