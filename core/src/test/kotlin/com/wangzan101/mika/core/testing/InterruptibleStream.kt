package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/**
 * は、中断割り込みをテストするために、[inner] のストリーミング出力のレイヤーを設定します。最初のリクエストは、順番に [chunks] に送信されます (各段落は、この時点までに表示されるフルテキストです)
 * (空の文字列は再起動、クリアを意味します)、削除されるまで停止します。このリクエストは [inner] には記録されません。以降のリクエストは [inner] のスクリプトに従って応答され、全文が一度に送信されます。
 */
class InterruptibleStream(private val inner: FakeChatModel, private val chunks: List<String>) : StreamingChatModel {
    override val name: String get() = inner.name
    override val capabilities: Capabilities get() = inner.capabilities
    /** 最初のリクエストが送信されました。 [chunks]、停止しました。 */
    val entered = CompletableDeferred<Unit>()
    /** 最初のリクエストは取り下げられました。 */
    var cancelled = false
        private set
    private var first = true

    /** ストリーミングなしで要求すると (インターフェイスはリアルタイム テキストを表示しません)、最初の要求も停止しますが、単語は送信されません。 */
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        if (first) {
            first = false
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        return inner.complete(system, messages, tools)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        if (first) {
            first = false
            chunks.forEach(onText)
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        return inner.complete(system, messages, tools).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
    }
}
