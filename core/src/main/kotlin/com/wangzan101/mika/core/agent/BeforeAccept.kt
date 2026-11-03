package com.wangzan101.mika.core.agent

/**
 * 電話なしの返信が最終的な回答として受け入れられる直前 (継ぎ目): タスクの進行中に、外部からユーザーからの別のメッセージなどの文をここに挿入することもできます。 通常どおり
 * [Accept] を受け入れます (もう一度確認してください)。 [Continue] は受け入れず、リマインダーとして [note] をモデルに与え、タスクを続行します。
 */
sealed interface BeforeAccept {
    data object Accept : BeforeAccept
    data class Continue(val note: String) : BeforeAccept
}
