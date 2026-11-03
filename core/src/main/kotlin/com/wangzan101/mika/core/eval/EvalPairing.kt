package com.wangzan101.mika.core.eval

/** 並行評価のためのグループ化: 同じグループ内の質問は同時に実行されます。 */
object EvalPairing {
    /**
     * 質問の順番に貪欲: 各グループは、まだ分割されていない最初の質問から開始し、[n] が完了するまで、使用するアプリとグループ間で交差しない質問を探します。
     * 同じアプリを同時に 2 つの画面に表示することはできないため、アプリには異なるグループに重複する質問があります。 `apps`と書かれていない問題は何に使われるか分からないので別グループにしました。
     */
    fun groups(tasks: List<EvalTask>, n: Int): List<List<EvalTask>> {
        val left = tasks.toMutableList()
        val out = mutableListOf<List<EvalTask>>()
        while (left.isNotEmpty()) {
            val first = left.removeAt(0)
            val group = mutableListOf(first)
            if (first.apps.isNotEmpty()) {
                val used = first.apps.toMutableSet()
                val it = left.iterator()
                while (group.size < n && it.hasNext()) {
                    val t = it.next()
                    if (t.apps.isEmpty() || t.apps.any { a -> a in used }) continue
                    group += t
                    used += t.apps
                    it.remove()
                }
            }
            out += group
        }
        return out
    }
}
