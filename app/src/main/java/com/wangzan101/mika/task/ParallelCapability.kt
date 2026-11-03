package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

/** は複数のタスクを同時に実行できます。 [reason] は、(設定ページに表示される) 1 つしか存在できず、制限がない場合は空になる理由を説明しています。 */
data class ParallelLimit(val max: Int, val reason: String?)

/**
 * は、デバイスの機能に基づいて並列化するタスクの数を決定します。各仮想画面には独立したフォーカスが必要です (Android 14 以降)。メモリの少ないデバイスは並列化されません。
 * 今回 2 番目の画面の構築に失敗した場合、再試行されません。残りはユーザーが設定した上限に従って 1 ～ 3 に制限されます。
 */
object ParallelCapability {
    const val MIN_SDK = 34
    const val DEFAULT_MAX = 2
    private const val MAX = 3
    val SECOND_DISPLAY_FAILED: String get() = tr(R.string.parallel_second_display_failed)

    fun detect(sdk: Int, lowRam: Boolean, userMax: Int, secondDisplayFailed: Boolean): ParallelLimit = when {
        sdk < MIN_SDK -> ParallelLimit(1, tr(R.string.parallel_needs_14))
        lowRam -> ParallelLimit(1, tr(R.string.parallel_low_ram))
        secondDisplayFailed -> ParallelLimit(1, SECOND_DISPLAY_FAILED)
        else -> ParallelLimit(userMax.coerceIn(1, MAX), null)
    }
}
