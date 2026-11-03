package com.wangzan101.mika.cap.web

/**
 * プロセス内のすべてのタスクで共有される Web ページ タグの上限 (グローバルで最大 [max]): 各タスクにはタグ プールがあり、各プールは [Member] として追加されます。
 * 「最近使用したもの」 ここで同じ時計を使用して、タスク全体を比較して、誰が最も長い間使用していないかを確認することもできます。すべてのメンバー メソッドはメイン スレッドで呼び出されます。
 */
class TabBudget(val max: Int = DEFAULT_MAX) {
    interface Member {
        fun tabCount(): Int
        /** は、長期間使用されていないため、オフにできるタグです (数値、最終使用時間)。ロック (テイクオーバー中) および [except] はカウントされません。どれもヌルではありません。 */
        fun oldestSpare(except: String?): Pair<String, Long>?
        /** グローバル上限があるためオフにします (「制限」イベントを送信)。 */
        fun evictForBudget(tabId: String)
    }

    private val members = mutableListOf<Member>()
    private var clock = 0L

    @Synchronized fun tick(): Long = ++clock

    @Synchronized fun join(m: Member) {
        if (members.none { it === m }) members += m
    }

    @Synchronized fun leave(m: Member) {
        members.removeAll { it === m }
    }

    fun total(): Int = snapshot().sumOf { it.tabCount() }

    fun full(): Boolean = total() >= max

    /** すべてのタスクの中で最も長期間使用されておらず、閉じることができるラベルを閉じます。閉じることができるものが存在しない場合は false が返されます。 */
    fun evictOldest(except: String?): Boolean {
        val victim = snapshot().mapNotNull { m -> m.oldestSpare(except)?.let { m to it } }.minByOrNull { it.second.second } ?: return false
        victim.first.evictForBudget(victim.second.first)
        return true
    }

    @Synchronized private fun snapshot() = members.toList()

    companion object {
        const val DEFAULT_MAX = 8
    }
}
