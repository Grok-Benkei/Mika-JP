package com.wangzan101.mika.capsule

/**
 * カプセルをクリックしてダブルクリックします (純粋なロジック、クロックは呼び出し元によって与えられます)。クリックしてカードを展開し、ダブルクリックして現在の画面を送信します。
 * ([tap]) を 1 回押すと、クリックとしてカウントされません。[timeoutMs] をもう一度押すと、ダブルクリックとしてカウントされます。 2 回目のクリック ([settle]) はクリックとしてカウントされません。
 */
class DoubleTap(val timeoutMs: Long) {
    enum class Tap {
        /** 初回: [timeoutMs] を待ってから、[settle] を待ちます。 */
        First,
        Double,
    }

    private var firstAt: Long? = null

    fun tap(now: Long): Tap {
        val first = firstAt
        return if (first != null && now - first <= timeoutMs) {
            firstAt = null
            Tap.Double
        } else {
            firstAt = now
            Tap.First
        }
    }

    /** 最初のクリック後にタイムアップです。まだ 2 番目のクリックを待っています (true を返します。1 回だけ戻ります)。ダブルクリックされた場合、またはまだ到着していない場合は false を返します。 */
    fun settle(now: Long): Boolean {
        val first = firstAt ?: return false
        if (now - first < timeoutMs) return false
        firstAt = null
        return true
    }

    /** もう待つ必要はありません (カプセルをしまって別のものに変更してください)。 */
    fun reset() {
        firstAt = null
    }
}
