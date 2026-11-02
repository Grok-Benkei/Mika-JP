package com.wangzan101.mika.settings

import android.app.NotificationManager
import com.wangzan101.mika.task.TaskService

/** 通知チャネルがどのように見えるか (システム内の重要性を見てください)。 */
enum class ChannelLevel {
    /** チャンネルがオフ（またはメインスイッチがオフ）です。 */
    Off,
    /** が画面にポップアップ表示されます。 */
    HeadsUp,
    /** は通知バーに静かに表示されるだけです。 */
    Quiet,
}

/** 通知ページのチャネルの行: [warn] true の場合、期待と矛盾していることを意味し (表示されるはずのものが表示されない)、マークする必要があります。 */
data class ChannelRow(val channelId: String, val level: ChannelLevel, val warn: Boolean)

/** 通知の全体的なステータスは、設定の「通知」行の右側に表示されます。 */
sealed interface NotificationSummary {
    data object Normal : NotificationSummary
    /** [count] チャネルがポップアップされるはずですが、ポップアップされません。 */
    data class Lowered(val count: Int) : NotificationSummary
    /** アプリの通知マスタースイッチがオフになっています。 */
    data object AllOff : NotificationSummary
}

data class NotificationOverview(
    val appAllowed: Boolean,
    val attention: ChannelRow,
    val result: ChannelRow,
    val progress: ChannelRow,
    val summary: NotificationSummary,
) {
    companion object {
        /**
         * は、マスター スイッチと 3 つのチャネルの重要度から各行のステータスと全体のステータスを取得します。重要度が空の場合はチャネルが確立されていないことを意味し、確立時のレベルに基づいて計算されます。
         * 「処理が必要です」と「タスクの結果」がポップアップ表示されます。 「ポップアップしない」場合は、警告がマークされます (閉じられたものとしてカウントされます)。 「タスクの進行状況」は、タスクが静かであるか閉じられている場合には影響を与えないため、マークされません。
         * メインスイッチをオフにすると、3 つのラインがすべて「閉じ」になり、警告は表示されません (メインスイッチに問題があります)。
         */
        fun of(appAllowed: Boolean, attention: Int?, result: Int?, progress: Int?): NotificationOverview {
            fun row(id: String, importance: Int?, shouldPopUp: Boolean): ChannelRow {
                val level = if (!appAllowed) ChannelLevel.Off else levelOf(importance ?: initialImportance(id))
                return ChannelRow(id, level, warn = appAllowed && shouldPopUp && level != ChannelLevel.HeadsUp)
            }
            val a = row(TaskService.CHANNEL_ATTENTION, attention, shouldPopUp = true)
            val r = row(TaskService.CHANNEL_RESULT, result, shouldPopUp = true)
            val p = row(TaskService.CHANNEL_PROGRESS, progress, shouldPopUp = false)
            val lowered = listOf(a, r, p).count { it.warn }
            val summary = when {
                !appAllowed -> NotificationSummary.AllOff
                lowered > 0 -> NotificationSummary.Lowered(lowered)
                else -> NotificationSummary.Normal
            }
            return NotificationOverview(appAllowed, a, r, p, summary)
        }

        fun levelOf(importance: Int): ChannelLevel = when {
            importance == NotificationManager.IMPORTANCE_NONE -> ChannelLevel.Off
            importance >= NotificationManager.IMPORTANCE_HIGH -> ChannelLevel.HeadsUp
            else -> ChannelLevel.Quiet
        }

        private fun initialImportance(id: String): Int =
            TaskService.CHANNEL_IMPORTANCE[id] ?: NotificationManager.IMPORTANCE_DEFAULT
    }
}
