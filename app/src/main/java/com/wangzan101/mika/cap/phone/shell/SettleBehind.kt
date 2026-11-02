package com.wangzan101.mika.cap.phone.shell

/**
 * 最後にタスクをメイン画面の後ろに移動し、ユーザーから見えない場所にタスクが実際に退避するまで待ちます (純粋なロジック、システム インターフェイスに触れず、単体テストで直接測定します)。
 *
 * が移動された後、システムは TO_BACK 遷移を行います。この期間中、TaskInfo.isVisible は依然として true です。 「可視」の読み取りを失敗とみなすことはできません。
 * 、つまり [POLL_MS] ごとに見てください。見えない場合は、判断してください。まだ表示される場合は、[REORDER_EVERY_MS] ごとに最後まで再度並べてください。
 * ～ [TIMEOUT_MS] まだ見えているものは目の前に残せないので、逃げ道に任せる([Plan.Done.fallback]): ミカの画面を後ろに移動し、画面ごとオフにします。元に戻せない場合は、直接オフにしてください。
 * （上に移動して音が出たのと同じ経路）、結果は残ってませんでした。ホーム画面に表示されなくなったもの（他のタスクに使用されたり、閉じられたりしたもの）は管理されなくなり、残っているものとしてカウントされません。
 */
object SettleBehind {
    const val TIMEOUT_MS = 2_000L
    const val REORDER_EVERY_MS = 750L
    const val POLL_MS = 100L

    /** 移動されたタスクは次のようになります。 [onMain] はまだメイン画面にあります。メイン画面に [visible] が表示されます (isVisible が読み取れない場合、発信者は「メイン画面の一番上にありますか」を押します)。 */
    data class Seen(val id: Int, val onMain: Boolean, val visible: Boolean)

    sealed interface Plan {
        /** はまだ移行中です。[reorder] はこのラウンドの終了時にランク付けされます (空の場合があります)。[gone] はその後に続きません。 [POLL_MS] を通過して、もう一度見てください。 */
        data class Wait(val reorder: Set<Int>, val gone: Set<Int>) : Plan

        /** が決定されます。[kept] はメイン画面の後ろに残ります。 [fallback] はタイムアウト後も表示されるため、後退する必要があります。 [gone] はメイン画面に表示されなくなりました。 */
        data class Done(val kept: Set<Int>, val fallback: Set<Int>, val gone: Set<Int>) : Plan
    }

    /**
     * このラウンドで何をするか。 [moved] はまだ追跡中のタスク、[seen] は現時点でのタスクの様子 (リストされていないタスクはホーム画面に存在しないとみなされます)、
     * [elapsedMs] は待機開始からの経過時間、[sinceReorderMs] は最後にキューに登録されてからの経過時間です (まだキューに登録されていない場合は null)。
     */
    fun plan(moved: Set<Int>, seen: List<Seen>, elapsedMs: Long, sinceReorderMs: Long?): Plan {
        val byId = seen.associateBy { it.id }
        val gone = moved.filterTo(LinkedHashSet()) { byId[it]?.onMain != true }
        val front = moved.filterTo(LinkedHashSet()) { byId[it]?.let { s -> s.onMain && s.visible } == true }
        val behind = moved - gone - front
        if (front.isEmpty()) return Plan.Done(behind, emptySet(), gone)
        if (elapsedMs >= TIMEOUT_MS) return Plan.Done(behind, front, gone)
        val due = sinceReorderMs == null || sinceReorderMs >= REORDER_EVERY_MS
        return Plan.Wait(if (due) front else emptySet(), gone)
    }

    /**
     * 待機後、ロックに戻り、再度ロックを設定します（待機中はロックが占有されていないため、その間に他のタスクにタスクが奪われてしまう可能性があります）。 [moved] はすべて移行されました、
     * [fallback] はタイムアウト後も表示されます。現時点でのメイン画面の表示は [seen] です。メイン画面にいない人は残りとしてカウントされなくなります。撤退する者は、タイムアウトになり、現時点でまだ見える者に限定されます。
     * 元々は背面に移動していましたが、現在は表示されているものは、ユーザーがクリックしたものであり、そこに残すことはできません。
     */
    fun closeOut(moved: Set<Int>, fallback: Set<Int>, seen: List<Seen>): Plan.Done {
        val byId = seen.associateBy { it.id }
        val gone = moved.filterTo(LinkedHashSet()) { byId[it]?.onMain != true }
        val front = (fallback intersect moved).filterTo(LinkedHashSet()) { byId[it]?.let { s -> s.onMain && s.visible } == true }
        return Plan.Done(moved - gone - front, front, gone)
    }
}
