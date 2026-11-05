package com.wangzan101.mika.capsule

/**
 * カプセルの単押しとダブルタップを判定します。同じ入力元が [timeoutMs] 以内に [slopPx] の範囲へ触れたときだけダブルタップです。
 * 時間切れは単押し、離れた位置・別の入力元・時計の巻戻しは新しい1回目として扱います。
 */
class DoubleTap(val timeoutMs: Long, private val slopPx: Float) {
    enum class Tap {
        /** 初回: [timeoutMs] を待ってから、[settle] を待ちます。 */
        First,
        Double,
    }

    private data class Point(val at: Long, val x: Float, val y: Float, val source: Int)

    private var first: Point? = null

    fun tap(now: Long, x: Float, y: Float, source: Int): Tap {
        val previous = first
        val elapsed = previous?.let { now - it.at }
        val samePlace = previous != null && run {
            val dx = x - previous.x
            val dy = y - previous.y
            dx * dx + dy * dy <= slopPx * slopPx
        }
        return if (previous != null && elapsed != null && elapsed in 0..timeoutMs && samePlace && source == previous.source) {
            first = null
            Tap.Double
        } else {
            first = Point(now, x, y, source)
            Tap.First
        }
    }

    /** 最初のクリック後にタイムアップです。まだ 2 番目のクリックを待っています (true を返します。1 回だけ戻ります)。ダブルクリックされた場合、またはまだ到着していない場合は false を返します。 */
    fun settle(now: Long): Boolean {
        val previous = first ?: return false
        if (now - previous.at < timeoutMs) return false
        first = null
        return true
    }

    /** もう待つ必要はありません (カプセルをしまって別のものに変更してください)。 */
    fun reset() {
        first = null
    }
}
