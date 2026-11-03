package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.agent.AgentLoop

/**
 * ストリーミング応答の中断後にモデルに送信された履歴 (AgentLoop.keepInterrupted を参照): ユーザーの言葉、
 * 中断されたセクションにはテキスト応答のみが含まれており (考えたり呼び出したりせずに文が半分に分割される場合があります)、その後に中断と実行時ステータスに関する 2 つのユーザー メッセージが続きます。
 * 各サービスプロバイダーのアダプターは、それをそのまま法的要求に変換する必要があります。
 */
internal object InterruptedHistory {
    const val ASK = "详细介绍一下光合作用"
    const val PARTIAL = "光合作用是绿色植物利用光能,把二氧化碳和水"
    val STEER = "${AgentLoop.STEER_MARKER}\n简短点"
    const val RUNTIME = "Current place: no screen or page read yet"

    val messages = listOf(
        Message(Role.User, listOf(Part.Text(ASK)), Author.User),
        Message(Role.Assistant, listOf(Part.Text(PARTIAL)), Author.Mika),
        Message(Role.User, listOf(Part.Text(STEER)), Author.User),
        Message(Role.User, listOf(Part.Text(RUNTIME)), Author.Mika),
    )
}
