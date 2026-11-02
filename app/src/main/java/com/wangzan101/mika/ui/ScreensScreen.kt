package com.wangzan101.mika.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.capsule.previewFrames
import com.wangzan101.mika.capsule.sameFrame
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/**
 * タスクとその仮想画面がこのページにリストされます。 [displayId] は -1 で、画面がまだ作成されていないことを意味します (キューイング、純粋なダイアログ、またはアプリが開かれていない)。
 * [foreground] は、画面上部のアプリの表示名です。見つからない場合、または携帯電話操作が接続されていない場合は null になります。 [web] が true の場合、組み込みブラウザが使用されます。
 * の画面番号は、並べ替えと写真の撮影にのみ使用され、ユーザーには表示されません。
 */
data class ScreenRow(
    val key: String,
    val conversationId: String,
    val goal: String,
    val displayId: Int,
    val state: TaskState,
    val foreground: String? = null,
    val web: Boolean = false,
    /** このタスクはこれまでにいくつかのことを実行しました。変更されている場合は、一番上のアプリを再確認してください。 */
    val acted: Int = 0,
)

/** リストの順序: 画面が構築されているものは前 (画面の作成順序に従って)、まだ構築されていないものは後ろ (元の順序を維持)。 */
fun orderedScreens(rows: List<ScreenRow>): List<ScreenRow> =
    rows.filter { it.displayId >= 0 }.sortedBy { it.displayId } + rows.filter { it.displayId < 0 }

/**
 * サイドバーの「進行中」の隣にある「バックステージ画面」ページ: 実行中の各タスクのカード。何をしているのか、どのアプリを使用しているのか、そのステータスを書きます。展開するとリアルタイム画面が表示されます。
 * 読み取り専用 - タスクを停止するか、ダイアログに戻るか、ダイアログ ページとカプセルでタスクを実行します。カードをクリックすると、タスクが存在するダイアログに戻ります。
 *
 * データは、外部にサービスを提供する「表示されたタスク」ではなく、各タスク独自のストリーム (ステータス、画面、画面、および完了した内容) から取得されます。
 * 並列表示されていない画面も実際の画面です。最上位のアプリは、タスクが別のことを行うときにポーリングせずに再チェックするだけです。
 */
@Composable
fun ScreensScreen(taskState: TaskCenterState, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    val context = LocalContext.current
    val runs by taskState.runs.collectAsState()
    var rows by remember { mutableStateOf<List<ScreenRow>>(emptyList()) }
    LaunchedEffect(runs) {
        val list = runs
        if (list.isEmpty()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        // 各タスクのステータス、画面、ブラウザ、および実行された内容には独自のストリームがあり、すべての変更がまとめて再配置されます。
        combine(list.map { run ->
            combine(run.state, run.displayId, run.webOpen, run.activities) { s, id, web, acts ->
                ScreenRow(run.key, run.conversationId, run.goal, id, s, web = web, acted = acts.size)
            }
        }) { it.toList() }.collect { rows = it }
    }

    // 画面タスクと、それらが実行した処理の数があります。それらのいずれかが別の処理を実行すると、各画面の一番上のアプリが再度尋ねられます。
    val probe = remember(rows) { rows.filter { it.displayId >= 0 }.map { it.displayId to it.acted } }
    var foreground by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(probe) { foreground = foregroundOf(context, probe.map { it.first }) }

    ScreensContent(
        rows = orderedScreens(rows).map { r -> r.copy(foreground = foreground[r.displayId]) },
        onBack = onBack,
        onOpenConversation = onOpenConversation,
        frames = { key -> runs.firstOrNull { it.key == key }?.frames },
    )
}

/**
 * ステータスのないページはテストに便利です。[rows] が並べ替えられ、トップのアプリが入力されています。
 * [frames] タスク番号に従ってその画面の画像を表示します。展開するとサブスクライブされます。
 */
@Composable
fun ScreensContent(
    rows: List<ScreenRow>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    frames: (String) -> StateFlow<Bitmap?>? = { null },
) {
    PageScaffold(ScreensCopy.TITLE, onBack) {
        if (rows.isEmpty()) {
            EmptyNote(ScreensCopy.EMPTY)
            return@PageScaffold
        }
        rows.forEach { row -> ScreenCard(row, frames(row.key), onOpenConversation) }
        FooterNote(ScreensCopy.NOTE)
    }
}

/** タスク: 何をしているのか、どのアプリを使用しているのか、ステータス。この行をクリックするとダイアログに戻り、「画面表示」をクリックするとリアルタイム画面が展開されます。 */
@Composable
private fun ScreenCard(row: ScreenRow, frames: StateFlow<Bitmap?>?, onOpenConversation: (String) -> Unit) {
    val c = MikaTheme.colors
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    GroupCard(Modifier.padding(top = 8.dp)) {
        ListRow(
            MikaIcons.Screens, row.goal.oneLine(), ScreensCopy.place(row),
            first = true, onClick = { onOpenConversation(row.conversationId) },
        ) { RowValue(ScreensCopy.state(row.state)) }
        if (row.displayId >= 0 || row.web) {
            Text(
                if (open) ScreensCopy.HIDE_PREVIEW else ScreensCopy.PREVIEW,
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    .clickable { open = !open }.testTag("screens-preview-${row.key}"),
                fontSize = 13.sp, color = c.accent,
            )
            if (open && frames != null) LiveFrame(frames)
        }
    }
}

/**
 * この画面のリアルタイム画像: 前の画像と同じ、1 秒あたり最大 5 枚の画像 ([previewFrames] を参照)。購読せずに折りたたむ。
 * クリックすると全画面に拡大でき、ピンチしてズームしたりドラッグしたりできます。この小さな領域では、小さなフォントを使用したインターフェイスははっきりと見えません。
 */
@Composable
private fun LiveFrame(source: StateFlow<Bitmap?>) {
    val frames = remember(source) { source.filterNotNull().previewFrames(same = ::sameFrame) }
    val frame by frames.collectAsState(initial = null)
    val bmp = frame ?: return
    var zoom by remember { mutableStateOf(false) }
    Image(
        bmp.asImageBitmap(), stringResource(R.string.screens_zoom),
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .height(260.dp).clip(RoundedCornerShape(10.dp))
            .clickable { zoom = true }.testTag("screens-zoom"),
        contentScale = ContentScale.Fit,
    )
    if (zoom) FrameZoom(bmp) { zoom = false }
}

/** 拡大された画面: 画面全体、ピンチしてズーム (1 ～ 6 回)、ドラッグして隅を見ます。外側をクリックするか、Return キーを押して閉じます。 */
@Composable
private fun FrameZoom(frame: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(MikaTheme.colors.bg.copy(alpha = 0.96f)).clickable { onDismiss() }
                .testTag("screens-zoom-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                frame.asImageBitmap(), null,
                Modifier.fillMaxWidth().transformable(state)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** 内蔵アシスタントに各画面の上部にあるアプリを尋ね、それを表示名に変更します。アシスタントは、接続されていないことを確認しません (これは前景行にのみ影響します)。 */
private suspend fun foregroundOf(context: Context, ids: List<Int>): Map<Int, String> {
    if (ids.isEmpty()) return emptyMap()
    val hub = runCatching { PhoneServices.of(context).hub }.getOrNull() ?: return emptyMap()
    return withContext(Dispatchers.IO) {
        ids.mapNotNull { id ->
            val pkg = runCatching {
                val shell = hub.shell.value ?: return@runCatching null
                val task = shell.topTaskId(id)
                if (task < 0) null else shell.packageOfTask(task)
            }.getOrNull()
            pkg?.let { id to ConfirmationNotifier.appLabel(context, it) }
        }.toMap()
    }
}

/** ターゲットはリスト内の 1 行のみを占めます。改行と連続したスペースは 1 つのスペースに結合されます。 */
private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

/** ページのすべてのテキスト: 開発については、画面番号、ピクセル、密度などのことは書かず、タスクが何を実行しているか、何を使用しているか、どの段階に到達したかについてのみ話してください。 */
object ScreensCopy {
    val TITLE: String get() = tr(R.string.screens_title)
    val EMPTY: String get() = tr(R.string.screens_empty)
    val NOTE: String get() = tr(R.string.screens_note)
    val PREVIEW: String get() = tr(R.string.screens_preview)
    val HIDE_PREVIEW: String get() = tr(R.string.screens_hide_preview)

    /** カードの 2 行目: どのアプリ (またはブラウザ) が使用されていますか?開いていない場合や一時的に見れない場合はアプリごとに一文あります。 */
    fun place(row: ScreenRow): String = when {
        row.web -> tr(R.string.screens_using_browser)
        row.displayId < 0 -> tr(R.string.screens_no_app)
        row.foreground != null -> tr(R.string.screens_using_app, row.foreground)
        else -> tr(R.string.screens_app_unknown)
    }

    fun state(state: TaskState): String = when (state) {
        TaskState.Idle -> tr(R.string.screens_waiting)
        is TaskState.Running -> tr(R.string.task_running)
        // 状況 (ロック画面、アシスタントの再接続) を待つだけでは、それに対処する必要はありません。電話機の上で手を動かす必要があります。
        is TaskState.Held -> if (state.reason.needsYou) tr(R.string.screens_needs_you) else tr(R.string.screens_waiting)
        is TaskState.WaitingTakeover, is TaskState.WaitingInput, is TaskState.WaitingConfirmation -> tr(R.string.screens_needs_you)
        is TaskState.Succeeded -> tr(R.string.task_done)
        is TaskState.Failed -> tr(R.string.task_not_done)
        TaskState.Cancelled -> tr(R.string.task_stopped)
    }
}
