package com.wangzan101.mika.cap.phone.shell

/**
 * メイン画面 (画面 0) に「先頭に表示される」タスクの記録は、タスク リスト ([scan]) を表示するたびに、組み込みアシスタントによって供給されます。
 * は事実を記録するだけであり、それがエージェントのアクションによって引き起こされたかどうかは判断しません (つまり、Mika 側のアクションです。MainScreenSpill を参照)。
 * メイン画面の上部でタスクを変更すると、エントリが記録されます。同じ作業ですが、ホーム画面のトップページが変更され、エントリが記録されます([Arrival.sameTask])。
 * 各ストリップでは、このタスクが最後に表示された画面 (以前に表示されていない場合は [Arrival.from]、[NEW]) が別の画面から移動されたかどうか (テイクオーバー)、それに応じて認識されます。
 * は純粋なロジックであり、クロックは呼び出し元によって与えられます。
 */
internal class MainScreenLog(private val keepMs: Long = KEEP_MS) {
    /** によって表示されるタスク: [topActivity] はトップ ページの完全なクラス名で、[standard] は通常のアプリ (デスクトップ、最近のタスク、アシスタントなどではありません) のページです。 */
    data class Task(
        val taskId: Int,
        val displayId: Int,
        val packageName: String?,
        val userId: Int,
        val topActivity: String?,
        val standard: Boolean = true,
    )

    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val topActivity: String? = null,
    )

    private var top: Task? = null
    private var displays: Map<Int, Int> = emptyMap()
    private val arrivals = ArrayDeque<Arrival>()

    /**
     * タスク リストを 1 回フィードします ([tasks]: システムの getTasks と同じ順序で、最近の使用順に並べ替えられた画面上のすべてのタスク)。
     * [record] が false の場合、現在の見た目のみが記録され、見た目は記録されません（見つめていない間に変更されました。いつ変更されたかはわかりません）。
     */
    fun scan(tasks: List<Task>, now: Long, record: Boolean) {
        val mainTop = tasks.firstOrNull { it.displayId == MAIN }
        val last = top
        if (record && mainTop != null && last != null) {
            when {
                mainTop.taskId != last.taskId -> add(mainTop, now, displays[mainTop.taskId] ?: NEW, sameTask = false)
                mainTop.topActivity != last.topActivity -> add(mainTop, now, MAIN, sameTask = true)
            }
        }
        top = mainTop
        displays = tasks.associate { it.taskId to it.displayId }
        while (arrivals.isNotEmpty() && now - arrivals.first().at > keepMs) arrivals.removeFirst()
    }

    private fun add(t: Task, now: Long, from: Int, sameTask: Boolean) {
        arrivals.addLast(Arrival(t.taskId, t.packageName, t.userId, now, from, sameTask, t.standard, t.topActivity))
    }

    /** 現時点でホーム画面の一番上のタスク。表示されていない場合、またはホーム画面にタスクがない場合は -1 です。 */
    val topTaskId: Int get() = top?.taskId ?: -1

    /** [since] (両端を含む) 以降のレコードが順番に配置されます。 */
    fun since(since: Long): List<Arrival> = arrivals.filter { it.at >= since }

    /** このタスクが最後に表示された画面。表示されなくなると -1。 */
    fun displayOf(taskId: Int): Int = displays[taskId] ?: -1

    companion object {
        const val MAIN = 0
        const val NEW = -1
        const val KEEP_MS = 60_000L
    }
}
