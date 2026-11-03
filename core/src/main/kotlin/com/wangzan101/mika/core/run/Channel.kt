package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice

data class ChannelPeer(val channelId: String, val peerId: String, val threadId: String)

sealed interface Delivery {
    val id: String
    val threadId: String

    data class Ask(
        override val id: String, override val threadId: String, val askId: String,
        val kind: Kind, val text: String,
    ) : Delivery {
        enum class Kind { Input, Confirm, Takeover }
    }

    data class Result(
        override val id: String, override val threadId: String, val text: String,
        val restored: Boolean = false, val title: String = "Mika タスクの結果",
    ) : Delivery
    data class Progress(override val id: String, override val threadId: String, val text: String) : Delivery
    data class Notice(override val id: String, override val threadId: String, val text: String) : Delivery
}

sealed interface ChannelInput {
    val peer: ChannelPeer
    data class Text(override val peer: ChannelPeer, val messageId: String, val text: String, val replyToAskId: String? = null) : ChannelInput
    data class Confirm(override val peer: ChannelPeer, val askId: String, val choice: ConfirmChoice) : ChannelInput
    data class Stop(override val peer: ChannelPeer, val runId: RunId) : ChannelInput
}

fun interface ChannelInputSink { suspend fun receive(input: ChannelInput) }

enum class SendOutcome { Delivered, Failed, Uncertain }

/** チャネルは送受信を担当し、誰が所有者であるか、または誰が承認できるかを決定しません。 */
interface Channel {
    val id: String
    /** は、このチャネルで支払い確認に応答できます (支払いのみが要求され、携帯電話でのみ同意されます)。 */
    val confirms: Boolean
    fun isAlive(peer: ChannelPeer): Boolean = true
    fun start(sink: ChannelInputSink): AutoCloseable
    suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome
}

/** ペアリングはマスターと等しくありません。バインドされたピアのみが Run にメッセージを送信できます。 */
interface OwnerIdentity {
    fun isOwner(peer: ChannelPeer): Boolean
}

class MemoryOwnerIdentity : OwnerIdentity {
    private val owners = mutableSetOf<Pair<String, String>>()
    @Synchronized fun bind(peer: ChannelPeer) { owners += peer.channelId to peer.peerId }
    @Synchronized fun unbind(peer: ChannelPeer) { owners -= peer.channelId to peer.peerId }
    @Synchronized override fun isOwner(peer: ChannelPeer): Boolean = peer.channelId to peer.peerId in owners
}
