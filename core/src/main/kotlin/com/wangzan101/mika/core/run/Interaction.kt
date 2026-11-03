package com.wangzan101.mika.core.run

/** 質問は AgentLoop から送信され、現在のチャネルによって配信されます。答えは依然として RunInbox からのみ返されます。 */
interface Interaction {
    suspend fun publish(ask: Delivery.Ask): DeliveryState
    fun resolved(askId: String)
}

/** 今日のインターフェイスと通知は、アプリ ステータスのサブスクリプションによって表示され、公開されると表示されるとみなされます。 */
object LocalInteraction : Interaction {
    override suspend fun publish(ask: Delivery.Ask) = DeliveryState.Delivered
    override fun resolved(askId: String) = Unit
}

class ChannelInteraction(
    private val outbox: Outbox,
    private val channel: Channel,
    private val peer: ChannelPeer,
) : Interaction {
    override suspend fun publish(ask: Delivery.Ask): DeliveryState = outbox.deliver(channel, peer, ask).state
    override fun resolved(askId: String) = Unit
}
