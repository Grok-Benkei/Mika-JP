package com.wangzan101.mika.core.agent

/**
 * モデル コンテキスト ウィンドウ上の数行。すべては **リクエスト全体** (システム プロンプト ワード + ツール定義 + メッセージ、サービス プロバイダーによって報告された入力トークンと同じ口径) に基づいて計算されます。
 * の固定オーバーヘッドはここでは差し引かれず、金額 ([UsageMeter]) の側から計算され、1 回のみカウントされます。トリガーポイントは[of]でのみ計算されます。
 *
 * - [usable]: ウィンドウは出力の予約を差し引きます (最大 [MAX_OUTPUT_RESERVE]、小さいウィンドウはウィンドウの 4 分の 1)。
 * - [margin]: トリガーポイントと [usable] の間のマージン (最大 [MARGIN_TOKENS]、小さなウィンドウは [usable] の 4 分の 1)。それは圧縮後のヒステリシスでもあります。
 *   は、すべてのステップではなく、押してから少なくともこの時間が経過するまで、再度押されることはありません。
 * - [trigger]: 現在のリクエストは制限を超えると一度圧縮されます。ウィンドウの [MIN_TRIGGER_SHARE] 以上、[usable] 以下。
 * - [target]: 1 つの圧縮は、その下のリクエスト全体 ([usable] の [AFTER_COMPACTION]、およびトリガー ポイントより下位の少なくとも 1 つの [margin]) を圧縮する必要があります。
 */
class ContextLimits private constructor(
    val window: Int, val usable: Int, val trigger: Int, val margin: Int, val target: Int,
    val minTrigger: Int,
) {
    companion object {
        /** モデル出力用に予約されるトークンの上限。 */
        const val MAX_OUTPUT_RESERVE = 20_000
        /** トリガーポイントと使用可能なウィンドウの間に残されるマージンの上限。 */
        const val MARGIN_TOKENS = 13_000
        /** 圧縮は、使用可能なウィンドウのこの比率を下回る必要があります。 */
        const val AFTER_COMPACTION = 0.5
        /** トリガー ポイントの下限 (ウィンドウの比率): 他のトリガー ポイントを考慮すると、最初に抑制されるほど低くはなりません。 */
        const val MIN_TRIGGER_SHARE = 0.5

        /**
         * [window]: モデルコンテキストの長さ。 [triggerOverride]: モデルごとに個別に指定されたトリガー ポイント (リクエスト全体のトークン)。下限値と [usable] の間に制限されます。
         */
        fun of(window: Int, triggerOverride: Int? = null): ContextLimits {
            val w = window.coerceAtLeast(0)
            val usable = w - minOf(MAX_OUTPUT_RESERVE, w / 4)
            val margin = minOf(MARGIN_TOKENS, usable / 4)
            val floor = minOf((w * MIN_TRIGGER_SHARE).toInt(), usable)
            val trigger = (triggerOverride ?: (usable - margin)).coerceIn(floor, usable)
            val target = minOf((usable * AFTER_COMPACTION).toInt(), trigger - margin).coerceAtLeast(0)
            return ContextLimits(w, usable, trigger, margin, target, floor)
        }
    }
}
