package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.Usage
import kotlin.math.ceil

/**
 * 次の完全なリクエスト (システム プロンプト ワード + ツール定義 + メッセージ、[ContextLimits] と同じ口径) の大きさです。固定オーバーヘッドは 1 回だけカウントされます。
 *
 * - アンカー ポイント (同じモデルを実際に使用した最新の応答): そのリクエストの入力トークン (サービス プロバイダーによって報告され、その時点でのプロンプト ワードとツール定義を含む)
 *   + この応答で再度送信される出力 (出力からサービス プロバイダーによって別途報告された思考トークンを差し引いたもの。思考はコンテキストの成長としてカウントされません)
 *   + 後続のメッセージの推定値 + それ以降に固定オーバーヘッドがどのように変化したかの推定値 (ツールのセットの拡張など)。
 * - アンカー ポイントなし (最初のステップ、モデル変更、圧縮のみ): メッセージ + 固定オーバーヘッド、すべて推定。
 *
 * は常に、[TokenEstimate] に同じ校正率 [scale] を乗算したものとして推定されます ([ContextCompressor.scale] を参照)。
 */
internal object UsageMeter {
    /** 応答の実際のコスト、およびその要求を行うときの固定オーバーヘッドの推定値 (比率は乗算されません)。 */
    data class Anchor(val usage: Usage, val overhead: Int)

    fun of(messages: List<Message>, overhead: Int, scale: Double, anchorOf: (Message) -> Anchor? = { null }): Int {
        val at = messages.indexOfLast { it.role == Role.Assistant && anchorOf(it) != null }
        if (at < 0) return estimate(TokenEstimate.of(messages) + overhead, scale)
        val a = anchorOf(messages[at])!!
        val later = TokenEstimate.of(messages.subList(at + 1, messages.size)) + (overhead - a.overhead)
        return a.usage.inputTokens + resent(a.usage) + estimate(later, scale)
    }

    /** 次回入力として送信される応答の部分: 出力から個別に報告された思考トークンを差し引いたもの。 */
    fun resent(u: Usage): Int = (u.outputTokens - u.reasoningTokens).coerceAtLeast(0)

    fun estimate(tokens: Int, scale: Double): Int = ceil(tokens * scale).toInt()
}
