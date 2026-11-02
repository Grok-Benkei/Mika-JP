package com.wangzan101.mika.core.model

interface ChatModel {
    val capabilities: Capabilities

    /** モデル名、録画専用。 */
    val name: String get() = ""

    /** は失敗時に [ModelError] をスローします。 */
    suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply
}

/** は段落ごとのモデルをサポートしています。サポートされていない場合は、引き続き [ChatModel.complete] を実装してください。 */
interface StreamingChatModel : ChatModel {
    /** [onText] 受信されるのは、このリクエストによって生成された表示テキストの全文です。モデルの個人的な考えやツールのパラメータは含まれません。 */
    suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply
}
