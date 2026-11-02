package com.wangzan101.mika.capsule

import com.wangzan101.mika.chat.CurrentChat

/**
 * アイドル アイコンをクリックしたときにカードが続く会話 (純粋なロジック): ユーザーがアプリ内で参加している会話 ([current]、[CurrentChat] を参照)。記録がない場合に続く
 * は呼び出し元によって読み取られた最新のものです ([recent]、これはプロセスが開始されたばかりで古いバージョンにレコードがない場合にのみ使用可能です)。どちらも存在しない場合、それは新しい対話カードです。
 *
 * [title] は、会話のタイトル [CurrentChat.Open.id] (ユーザーまたは最初の文によって指定) を与え、まだ聞いていない場合は null を返します。
 */
object CapsuleIdle {
    fun chat(current: CurrentChat?, recent: CardView.Chat?, title: (String) -> String? = { null }): CardView.Chat = when (current) {
        null -> recent ?: CardView.Chat(null)
        CurrentChat.Fresh -> CardView.Chat(null, fresh = true)
        // それが「ユーザーが現在いるもの」であることを忘れないでください。他の会話が終了し、まだ保留中の場合は、カードを取得しないでください ([CapsuleCards.of] を参照)
        is CurrentChat.Open -> CardView.Chat(current.id, title = title(current.id), current = true)
    }
}
