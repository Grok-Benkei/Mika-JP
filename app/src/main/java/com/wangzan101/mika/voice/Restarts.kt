package com.wangzan101.mika.voice

/**
 * 認識サービスをしばらく押し続けても言葉が聞こえない場合 (一致しない、開かない、結果が空である)、再起動する必要がありますか?また、どれくらいの時間がかかりますか?
 * 通常の空のセグメント (ユーザーが数秒間一時停止する) はすぐに再度開かれます。一部のサービスは再起動するとすぐに空になり、再起動するとすぐに再び空になります。
 * 何度も再起動しないで、メインスレッドが開いたまま、間違ったまま、開いたまま回転しないように、聞こえた最後を押してください。純粋な論理では、時刻は呼び出し側によって与えられます。
 */
class Restarts {
    sealed interface Decision {
        data class Restart(val delayMs: Long) : Decision
        data object GiveUp : Decision
    }

    private var quick = 0
    private var last = Long.MIN_VALUE

    /** は別の空のセグメントで、[now] は現時点 (ミリ秒、単調クロック) です。 */
    fun onEmpty(now: Long): Decision {
        quick = if (last != Long.MIN_VALUE && now - last <= WINDOW_MS) quick + 1 else 1
        last = now
        return when {
            quick > MAX_QUICK -> Decision.GiveUp
            quick > QUICK -> Decision.Restart(BACKOFF_MS)
            else -> Decision.Restart(0)
        }
    }

    /** はその言葉を聞きました: 数え直します。 */
    fun onSpeech() {
        quick = 0
        last = Long.MIN_VALUE
    }

    companion object {
        /** が前回ショートしてからそれほど時間が経っていない場合は、「すぐにまたショートする」と見なされます。 */
        const val WINDOW_MS = 2_000L
        /** は、何度も連続して実行するとすぐにゼロに戻り、しばらくすると再び開き始めました。 */
        const val QUICK = 3
        /** は何度もすぐにショートポジションに戻り、再びオープンすることはありません。 */
        const val MAX_QUICK = 8
        const val BACKOFF_MS = 250L
    }
}
