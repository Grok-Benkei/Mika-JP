package com.wangzan101.mika.core.uistate

/**
 * ホイールを値に回した結果。 [reached] が到着しました。 [value] は現時点で表示されている値 (null として読み取ることはできません)、最初は [from] が表示されていました。 [attempts] はいくつかのアクションを実行しました
 * (各カウントを 1 回入力、スクロール、ドラッグ); [method] 最後に使用した方法: テキスト (直接入力)、アクション (バリアフリー スクロール)、ドラッグ (ドラッグ)。
 * [error] 未到着理由(ref_expired、not_a_wheel、unknown_value、at_end、not_moving、not_reached...)、[hint] ポートの補足説明。
 */
data class WheelOutcome(
    val reached: Boolean,
    val value: String?,
    val from: String?,
    val attempts: Int,
    val method: String,
    val error: String? = null,
    val hint: String? = null,
)
