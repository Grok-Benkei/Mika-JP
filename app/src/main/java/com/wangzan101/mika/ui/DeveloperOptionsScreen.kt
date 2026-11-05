package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron

/** はエンジニアリング スイッチです。[id] はテスト ラベルとして使用され、[change] はこの項目を変更した後のスイッチのセット全体を計算するために使用されます。 */
data class DevSwitch(
    val id: String, val title: String, val subtitle: String, val checked: Boolean, val change: (Boolean) -> SpeedFlags,
)

/** 開発者向けオプションの 2 つのグループ: 速度 (検証済み、デフォルトでほとんどがオン) と実験的 (デフォルトでオフ)。スイッチとストレージの値 ([SpeedFlags]) は変更されません。 */
object DeveloperSwitches {
    fun speed(flags: SpeedFlags): List<DevSwitch> = listOf(
        DevSwitch("adaptive-vision", tr(R.string.dev_adaptive), tr(R.string.dev_adaptive_sub), flags.adaptiveVision) { flags.copy(adaptiveVision = it) },
        DevSwitch("inline-screen", tr(R.string.dev_inline), tr(R.string.dev_inline_sub), flags.inlineScreen) { flags.copy(inlineScreen = it) },
        DevSwitch("batch-steps", tr(R.string.dev_batch), tr(R.string.dev_batch_sub), flags.batchSteps) { flags.copy(batchSteps = it) },
        DevSwitch("deep-links", tr(R.string.dev_deep_links), tr(R.string.dev_deep_links_sub), flags.deepLinks) { flags.copy(deepLinks = it) },
        DevSwitch("light-steps", tr(R.string.dev_light), tr(R.string.dev_light_sub), flags.lightSteps) { flags.copy(lightSteps = it) },
    )

    fun experimental(flags: SpeedFlags): List<DevSwitch> = listOf(
        DevSwitch("replay", tr(R.string.dev_replay), tr(R.string.dev_replay_sub), flags.replay) { flags.copy(replay = it) },
        DevSwitch("parallel-tasks", tr(R.string.dev_parallel), tr(R.string.dev_parallel_sub), flags.parallelTasks) { flags.copy(parallelTasks = it) },
        DevSwitch("structured-compaction", tr(R.string.dev_compaction), tr(R.string.dev_compaction_sub), flags.structuredCompaction) { flags.copy(structuredCompaction = it) },
    )
}

/** 「物事のやり方」セット: 照会/自律グローバルスイッチ。 */
object AutonomyCopy {
    val GROUP: String get() = tr(R.string.dev_group)
    val TITLE: String get() = tr(R.string.dev_autonomy)
    val SUBTITLE: String get() = tr(R.string.dev_autonomy_sub)
}

val DEVELOPER_NOTE: String get() = tr(R.string.dev_note)

/** 現在の画像閲覧モデルの「画像位置確認済み」：[model] がモデル名です。 */
data class GroundingSetting(val model: String, val verified: Boolean)

object GroundingCopy {
    val GROUP: String get() = tr(R.string.cap_vision)
    val TITLE: String get() = tr(R.string.dev_grounding)

    fun subtitle(model: String) = tr(R.string.dev_grounding_sub, model)
}

/**
 * 设置 › 高级与其他 › 开发者选项:速度和实验两组开关;有看图模型时另有它的「看图定位已验证」
 * ([grounding] が null の場合は表示されません)。最後は「実行統計」のエントリです（[onOpenRunStats]がnullの場合は表示されません）。
 */
@Composable
fun DeveloperOptionsScreen(
    speed: SpeedFlags,
    onSpeed: (SpeedFlags) -> Unit,
    onBack: () -> Unit,
    grounding: GroundingSetting? = null,
    onGrounding: (Boolean) -> Unit = {},
    onOpenRunStats: (() -> Unit)? = null,
) {
    PageScaffold(tr(R.string.settings_developer), onBack) {
        GroupLabel(tr(R.string.dev_speed))
        SwitchGroup(DeveloperSwitches.speed(speed), onSpeed)
        GroupLabel(tr(R.string.dev_experiments))
        SwitchGroup(DeveloperSwitches.experimental(speed), onSpeed)
        if (grounding != null) {
            GroupLabel(GroundingCopy.GROUP)
            GroupCard {
                ListRow(null, GroundingCopy.TITLE, GroundingCopy.subtitle(grounding.model), first = true, onClick = { onGrounding(!grounding.verified) }) {
                    MikaSwitch(grounding.verified, onGrounding, Modifier.testTag("dev-grounding-verified"))
                }
            }
        }
        GroupLabel(tr(R.string.dev_run_log))
        GroupCard {
            TraceSettingsRows()
            if (onOpenRunStats != null) {
                ListRow(null, RunMetricsCopy.STATS_TITLE, RunMetricsCopy.STATS_SUBTITLE, onClick = onOpenRunStats) { RowChevron() }
            }
        }
        FooterNote(DEVELOPER_NOTE)
    }
}

@Composable
private fun SwitchGroup(switches: List<DevSwitch>, onSpeed: (SpeedFlags) -> Unit) {
    GroupCard {
        switches.forEachIndexed { i, s ->
            // は行全体をクリックできます: 小さなスイッチ、大きな行
            ListRow(null, s.title, s.subtitle, first = i == 0, onClick = { onSpeed(s.change(!s.checked)) }) {
                MikaSwitch(s.checked, { onSpeed(s.change(it)) }, Modifier.testTag("dev-${s.id}"))
            }
        }
    }
}
