package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.RootfsInstaller
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.cap.sandbox.SandboxState
import kotlinx.coroutines.launch
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxUsage
import com.wangzan101.mika.cap.sandbox.SandboxUsageReader
import com.wangzan101.mika.cap.sandbox.WorkspaceUsage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * のコピーライティング 設定 › ファイルとコンピューティング環境 (純粋な機能、テストが簡単)。ランタイム環境は、ユーザーには見えない実装の詳細です (製品の決定)。
 * には、文字通り「スクリプト」、「処理ファイル」、「実行環境」のみが記載されており、サンドボックス、Linux、PRoot、Alpine などの専門用語は含まれていません。
 */
object SandboxCopy {
    val NETWORK_TITLE: String get() = tr(R.string.sandbox_network)
    val NETWORK_SUBTITLE: String get() = tr(R.string.sandbox_network_sub)
    val NETWORK_EXPLAIN: String get() = tr(R.string.sandbox_network_explain)
    val USAGE_TITLE: String get() = tr(R.string.sandbox_usage)
    val USAGE_NOTE: String get() = tr(R.string.sandbox_usage_note)
    val CLEAR: String get() = tr(R.string.sandbox_usage_clear)
    val CLEAR_TITLE: String get() = tr(R.string.sandbox_usage_clear_title)
    val CLEAR_ALL: String get() = tr(R.string.sandbox_usage_clear_all)
    val CLEAR_ALL_TITLE: String get() = tr(R.string.sandbox_usage_clear_all_title)
    val CLEAR_ALL_BODY: String get() = tr(R.string.sandbox_usage_clear_all_body)

    fun clearBody(w: WorkspaceUsage): String = tr(R.string.sandbox_usage_clear_body, workspaceTitle(w))

    /** ワークスペース内の行の名前: 会話のタイトル。会話が削除されたかどうかを示します。 */
    fun workspaceTitle(w: WorkspaceUsage): String = w.title?.let(ChatCopy::plainTitle)?.takeIf(String::isNotBlank) ?: tr(R.string.sandbox_usage_deleted_chat)

    fun usageRows(u: SandboxUsage): List<Pair<String, Long>> = listOf(
        tr(R.string.sandbox_usage_total) to u.total,
        tr(R.string.sandbox_usage_runtime) to u.runtime,
        tr(R.string.sandbox_usage_skills) to u.skills,
        tr(R.string.sandbox_usage_workspaces) to u.workspacesTotal,
    )

    /** バイト数は人間が判読できる単位で書き込まれます。 */
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.ROOT, bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(Locale.ROOT, bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
    val ISOLATION_UNAVAILABLE: String get() = tr(R.string.sandbox_isolation_unavailable)
    val RESET_TITLE: String get() = tr(R.string.sandbox_reset_title)
    val RESET_BODY: String get() = tr(R.string.sandbox_reset_body)

    /** 「動作環境をリセットする」の下の行: 通常時に何が起こるかを説明し、準備時または準備が失敗した場合に真実を伝えます。 */
    fun resetSubtitle(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Installing -> tr(R.string.runtime_preparing)
        is SandboxStatus.Broken -> tr(R.string.sandbox_broken, s.reason)
        else -> tr(R.string.sandbox_reset_sub)
    }
}

/**
 * 計算環境のネットワーク状態を [SandboxState] へ接続します。プロキシは詳細設定でまとめて変更します。
 * ページに入るときにバックグラウンドで使用されているスペースを読み取り、クリーンアップします。 [SandboxUsageReader] (実行中のタスクのダイアログが不明瞭です)。リセット後はすぐにバックグラウンドで再度準備を行ってください。
 */
@Composable
fun AdvancedSettings() {
    val context = LocalContext.current
    val state = remember { SandboxState.get(context) }
    val status by state.status.collectAsState()
    var network by remember { mutableStateOf(state.networkAllowed) }
    val app = context.applicationContext as MikaApp
    val usageReader = remember {
        SandboxUsageReader(SandboxDirs(context), { app.conversations.list() }, { app.taskState.activeConversations.value })
    }
    var usage by remember { mutableStateOf<SandboxUsage?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        usage = withContext(Dispatchers.IO) { runCatching { usageReader.read() }.getOrNull() }
    }
    val scope = rememberCoroutineScope()
    fun clearing(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
            reload++
        }
    }
    AdvancedSection(
        status = status, networkAllowed = network, isolationIssue = state.isolationIssue != null,
        onNetworkChange = { state.networkAllowed = it; network = it },
        usage = usage,
        onClearWorkspace = { w -> clearing { usageReader.clear(w.dirName) } },
        onClearAll = { clearing { usageReader.clearAll() } },
        onReset = {
            scope.launch {
                RootfsInstaller.create(context).reset()
                RuntimePreparer.ensure(context, retryFailed = true)
                reload++
            }
        },
    )
}

/** ステータスのない「ファイルとコンピューティング環境」ページのコンテンツは、テストに便利です。サポートされていないデバイスのグループ全体は表示されません。 */
@Composable
fun AdvancedSection(
    status: SandboxStatus,
    networkAllowed: Boolean,
    isolationIssue: Boolean,
    onNetworkChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    /** 使用済みスペース。まだ読み取られていない場合は null です (このグループはまだ表示されません)。 */
    usage: SandboxUsage? = null,
    onClearWorkspace: (WorkspaceUsage) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    if (status is SandboxStatus.Unsupported) return
    var confirming by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<WorkspaceUsage?>(null) }
    var clearingAll by remember { mutableStateOf(false) }

    GroupCard {
        ListRow(MikaIcons.Link, SandboxCopy.NETWORK_TITLE, SandboxCopy.NETWORK_SUBTITLE, first = true) {
            MikaSwitch(networkAllowed, onNetworkChange, Modifier.testTag("sandbox-network"))
        }
        Note(SandboxCopy.NETWORK_EXPLAIN, if (networkAllowed) MikaTheme.colors.warn else MikaTheme.colors.ink3)
        if (isolationIssue) Note(SandboxCopy.ISOLATION_UNAVAILABLE, MikaTheme.colors.bad)
        ListRow(MikaIcons.Close, tr(R.string.sandbox_reset), SandboxCopy.resetSubtitle(status), titleColor = MikaTheme.colors.bad, tone = TileTone.Bad,
            onClick = if (status is SandboxStatus.Installing) null else ({ confirming = true }))
    }

    usage?.let { u -> UsageGroup(u, onClear = { clearing = it }, onClearAll = { clearingAll = true }) }

    clearing?.let { w ->
        MikaDialog(
            onDismissRequest = { clearing = null },
            title = { Text(SandboxCopy.CLEAR_TITLE) },
            text = { Text(SandboxCopy.clearBody(w)) },
            confirmButton = { TextButton({ clearing = null; onClearWorkspace(w) }) { Text(SandboxCopy.CLEAR, color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ clearing = null }) { Text(tr(R.string.cancel)) } },
        )
    }
    if (clearingAll) {
        MikaDialog(
            onDismissRequest = { clearingAll = false },
            title = { Text(SandboxCopy.CLEAR_ALL_TITLE) },
            text = { Text(SandboxCopy.CLEAR_ALL_BODY) },
            confirmButton = { TextButton({ clearingAll = false; onClearAll() }) { Text(SandboxCopy.CLEAR, color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ clearingAll = false }) { Text(tr(R.string.cancel)) } },
        )
    }

    if (confirming) {
        MikaDialog(
            onDismissRequest = { confirming = false },
            title = { Text(SandboxCopy.RESET_TITLE) },
            text = { Text(SandboxCopy.RESET_BODY) },
            confirmButton = { TextButton({ confirming = false; onReset() }) { Text(tr(R.string.reset), color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ confirming = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp), style = MikaType.Hint, color = color)
}

/**
 * 使用スペース: 動作環境、スキル、会話ワークスペースの合計。ワークスペースは会話ごとに以下にリストされます (大きい順)。行をクリックしてクリアします (最初に確認します)。
 * タスクが実行中のダイアログは、現時点ではクリアできないことを示しています。最後の行はすべてのワークスペースをクリアします。
 */
@Composable
private fun UsageGroup(u: SandboxUsage, onClear: (WorkspaceUsage) -> Unit, onClearAll: () -> Unit) {
    val c = MikaTheme.colors
    GroupLabel(SandboxCopy.USAGE_TITLE)
    GroupCard {
        SandboxCopy.usageRows(u).forEachIndexed { i, (name, bytes) ->
            ListRow(null, name, first = i == 0) { RowValue(SandboxCopy.size(bytes)) }
        }
    }
    if (u.workspaces.isNotEmpty()) {
        GroupCard(Modifier.padding(top = 8.dp)) {
            u.workspaces.forEachIndexed { i, w ->
                ListRow(
                    null, SandboxCopy.workspaceTitle(w), if (w.busy) tr(R.string.sandbox_usage_busy) else null, first = i == 0,
                    onClick = if (w.busy) null else ({ onClear(w) }),
                ) { RowValue(SandboxCopy.size(w.bytes)) }
            }
            if (u.workspaces.any { !it.busy }) {
                ListRow(null, SandboxCopy.CLEAR_ALL, titleColor = c.bad, onClick = onClearAll)
            }
        }
    }
    Text(SandboxCopy.USAGE_NOTE, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MikaType.Hint, color = c.ink3)
}
