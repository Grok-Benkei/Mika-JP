package com.wangzan101.mika.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.RootControl
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme

/** スマホ操作設定の root グループ。su が見つかるときだけ表示する。[helperRow] が false の Android 13 未満ではヘルパーの root 起動を表示しない。 */
@Composable
fun RootSwitches(helperRow: Boolean) {
    val context = LocalContext.current
    val root = remember { PhoneServices.of(context).shellManager.root }
    // root の導入や削除を反映するため、設定画面を開くたびに su の存在を再確認する。
    LaunchedEffect(Unit) { root.refresh() }
    val state by root.state.collectAsState()
    if (!state.suPresent) return
    RootSection(
        state, helperRow,
        onStart = root::setStart, onRetryStart = root::retryStart, onCommands = root::setCommands,
    )
}

/**
 * ヘルパーの root 起動と root コマンドの実行許可。2つのスイッチはどちらも既定でオフ。
 * 起動が前回拒否または応答なしならタップで再要求する。コマンドの許可は警告を確認してから su に要求する。
 */
@Composable
fun RootSection(
    state: RootControl.State,
    helperRow: Boolean,
    onStart: (Boolean) -> Unit,
    onRetryStart: () -> Unit,
    onCommands: (Boolean) -> Unit,
) {
    var warn by remember { mutableStateOf(false) }
    val commandsTo = { on: Boolean -> if (on) warn = true else onCommands(false) }
    GroupLabel(PhoneSetupCopy.ROOT_GROUP)
    GroupCard {
        if (helperRow) {
            val retries = PhoneSetupCopy.rootStartRetries(state.start, state.startGrant)
            ListRow(
                MikaIcons.Key, PhoneSetupCopy.ROOT_START, PhoneSetupCopy.rootStartLine(state.startGrant), first = true,
                tone = if (retries) TileTone.Warn else TileTone.Neutral,
                onClick = if (retries) onRetryStart else ({ onStart(!state.start) }),
            ) { MikaSwitch(state.start, onStart, Modifier.testTag("root-start")) }
        }
        ListRow(
            MikaIcons.Terminal, PhoneSetupCopy.ROOT_COMMANDS, PhoneSetupCopy.rootCommandsLine(state.commands, state.commandsGrant), first = !helperRow,
            onClick = { commandsTo(!state.commands) },
        ) { MikaSwitch(state.commands, commandsTo, Modifier.testTag("root-commands")) }
    }
    FooterNote(PhoneSetupCopy.ROOT_NOTE)
    if (warn) {
        AlertDialog(
            onDismissRequest = { warn = false },
            title = { Text(PhoneSetupCopy.ROOT_COMMANDS_TITLE) },
            text = { Text(PhoneSetupCopy.ROOT_COMMANDS_WARNING) },
            confirmButton = {
                TextButton({ warn = false; onCommands(true) }) { Text(PhoneSetupCopy.ROOT_COMMANDS_CONFIRM, color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ warn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
