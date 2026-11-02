package com.wangzan101.mika.core.model

data class Capabilities(
    val toolCalls: Boolean,
    val vision: Boolean,
    val contextTokens: Int,
    val reasoning: Boolean,
    /**
     * 画像の位置決め (スクリーンショット上の要素のボックス化) の精度が検証されました。モデル フレームに表示されている検証されていないポイントは、追加の裏付け証拠がある場合にのみ find_visually に渡されます。
     */
    val groundingVerified: Boolean = false,
    /** サーバーが最初に同じプロンプト ワードを再利用する方法 (コストを節約し、速度を上げるため)。リクエストにキャッシュ マークを含めるかどうかはアダプターの判断になります。 */
    val promptCaching: PromptCaching = PromptCaching.Implicit,
    /** サービスプロバイダーが明示する、リクエストされる 1 つの画像の上限。不明な場合は推測しないでください。 */
    val maxImages: Int = Int.MAX_VALUE,
    /** リクエスト全体のトークンの数に基づく、オプションのローカル圧縮トリガー ポイント。 */
    val compactionTriggerTokens: Int? = null,
)

/**
 * [Implicit]: サーバーは同じ先頭を自動的に再利用します。リクエストにタグは必要ありません。 [Explicit]: メッセージをキャッシュする場所 (cache_control) を指定するには、
 * は、マークが付けられていない限り再利用されません。 [None]: 再利用されません。
 */
enum class PromptCaching(val wire: String) {
    Implicit("implicit"), Explicit("explicit"), None("none");

    companion object {
        fun of(wire: String?): PromptCaching = entries.firstOrNull { it.wire == wire } ?: Implicit
    }
}

enum class ModelRole { Main, Vision, Light }
