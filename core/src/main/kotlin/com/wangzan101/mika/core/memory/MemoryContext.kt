package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.agent.TokenEstimate

/** [message] は null です。これは、今回は持ち込むメモリがないことを意味します。 [usedIds] はブロック内に実際にリストされている項目であり、順序はリストと同じです。 */
data class MemoryBlock(val message: String?, val usedIds: List<String>)

/**
 * 各タスクのメモリ ブロック: 上記の会話の後、元のタスク テキストの前にユーザー メッセージとして配置されます。
 *
 * 2 つの給電方法:
 * - [Mode.Relevant]: しきい値を超える上位 + 検索結果 (承認された慣行は別にリストされ、上限があります)、合計の長さは [MAX_TOKENS] の対象となります。たくさんの思い出が詰まった昔ながらの習慣。
 * - [Mode.Full]: それらすべてが指定されているわけではなく (メソッドは依然として [MAX_PROCEDURES] によってバインドされています)、全長は [FULL_MAX_TOKENS] によってバインドされています。
 *
 * プロダクトのデフォルト値は [Mode.Auto] です。合計メモリが [FULL_MAX_TOKENS] を超えない場合、全額が注入され (メソッドにも全額が与えられます)、超えた場合は取得のために返されます。
 * のメモリは小さい (数十項目、数千ワード) ものの、現在のモデル コンテキストには数十万のコストがかかることがよくあります。フル注入しても漏れません。
 * プレフィックスはまだ固定されています。コンテキスト キャッシュにアクセスする方が、「毎回異なるものを取得する」よりもコストがかかりません。
 * ただし、メモリが数万語に達すると、メモリがいっぱいになると文脈が食い尽くされるため、検索は退避として保持されます。
 */
object MemoryContext {
    const val MAX_TOKENS = 800
    /** フル噴射の合計上限。それを超えた場合は、検索に戻る時期が来たことを意味します ([Mode.Auto] を参照)。 */
    const val FULL_MAX_TOKENS = 4_000
    const val MAX_PINNED = 5
    const val MAX_RELEVANT = 8
    const val MAX_PROCEDURES = 3
    const val MIN_SCORE = 0.5

    /** [Relevant]: 固定および取得されたもののみが含まれます。 [Full]：全量（予算を超えた場合は古いものを廃棄します）。 [Auto]: 可能であれば全量、そうでない場合は [Relevant] が返されます。 */
    enum class Mode { Relevant, Full, Auto }

    private const val FACTS_HEADER = "Memories about the user (things the user told you; may be out of date; when they conflict with what the user says now, follow what they say now):"
    private const val PROCEDURES_HEADER = "Ways of doing things the user approved (when they conflict with what the user says now, follow what they say now):"

    /**
     * [rewrite]: メソッド内で言及されている古いツール名を現在のものに置き換えます (LegacyNames.rewrite を参照)。デフォルトはそのままです。
     * [mode] は、デフォルトで古い検索動作を維持します。製品パス (AgentLoop) は [Mode.Auto] を明示的に渡します。
     */
    fun build(
        items: List<MemoryItem>,
        goal: String,
        lastUserTurn: String?,
        rewrite: (String) -> String = { it },
        mode: Mode = Mode.Relevant,
    ): MemoryBlock {
        val active = items.filter { it.status == MemoryStatus.active }
        if (active.isEmpty()) return MemoryBlock(null, emptyList())
        if (mode == Mode.Full) full(active, rewrite)?.let { return it }
        // Auto: 収まるなら、全力を尽くしてください。収まらない場合は (すでにたくさんの記憶があるため)、文脈に何万もの単語を詰め込まないで、検索に戻ってください。
        if (mode == Mode.Auto) fullIfFits(active, rewrite)?.let { return it }

        val query = listOfNotNull(goal, lastUserTurn).joinToString("\n")
        val ranked = MemoryIndex(active).search(query, limit = active.size)
        val score = ranked.associate { it.first.id to it.second }

        // メソッドの上限には上位のものも含まれます。
        var procedures = 0
        fun procedureFits(m: MemoryItem) = m.kind != MemoryKind.procedure || procedures < MAX_PROCEDURES

        val pinned = mutableListOf<MemoryItem>()
        for (m in active.filter { it.pinned }
            .sortedWith(compareByDescending<MemoryItem> { score[it.id] ?: 0.0 }.thenByDescending { it.updatedAt })) {
            if (pinned.size == MAX_PINNED) break
            if (!procedureFits(m)) continue
            if (m.kind == MemoryKind.procedure) procedures++
            pinned += m
        }
        val relevant = mutableListOf<MemoryItem>()
        for ((item, s) in ranked) {
            if (s < MIN_SCORE || relevant.size == MAX_RELEVANT) break
            if (item.pinned || !procedureFits(item)) continue
            if (item.kind == MemoryKind.procedure) procedures++
            relevant += item
        }

        // 保持の優先順位は高から低の順です。(スコアに従って) 一番上が最初で、(スコアに従って) 関連性のあるものが最後です。非常に長いものは端から削除されます
        val keep = (pinned + relevant).toMutableList()
        while (keep.isNotEmpty()) {
            val (message, ids) = render(keep, rewrite)
            if (TokenEstimate.of(message) <= MAX_TOKENS) return MemoryBlock(message, ids)
            keep.removeAt(keep.lastIndex)
        }
        return MemoryBlock(null, emptyList())
    }

    /**
     * フルボリューム: 一番上のものが最初で、残りは更新時間の降順です。メソッドの最大数は [MAX_PROCEDURES] です。
     * が [FULL_MAX_TOKENS] を超えると、最も古い共通エントリが最初に失われ、次にメソッドが失われます。すべて失われた場合は、null が返されます (検索に任せられます)。
     */
    private fun full(active: List<MemoryItem>, rewrite: (String) -> String): MemoryBlock? {
        val (allFacts, allProcedures) = ordered(active)
        val facts = allFacts.toMutableList()
        val procedures = allProcedures.toMutableList()
        while (facts.isNotEmpty() || procedures.isNotEmpty()) {
            val (message, ids) = render(facts + procedures, rewrite)
            if (TokenEstimate.of(message) <= FULL_MAX_TOKENS) return MemoryBlock(message, ids)
            if (facts.isNotEmpty()) facts.removeAt(facts.lastIndex) else procedures.removeAt(procedures.lastIndex)
        }
        return null
    }

    /**
     * は、ロードできる場合にのみいっぱいになります。ロードできない場合は null が返され、呼び出し元は検索を返します。
     * も同じアプローチに従います。更新時間に基づいて最初のいくつかのエントリを切り取ると、このタスクに関連するエントリが破棄されます (切り捨ては検索の関連性に基づいてのみ行われます)。
     */
    private fun fullIfFits(active: List<MemoryItem>, rewrite: (String) -> String): MemoryBlock? {
        val (facts, procedures) = ordered(active, maxProcedures = Int.MAX_VALUE)
        val (message, ids) = render(facts + procedures, rewrite)
        return if (TokenEstimate.of(message) <= FULL_MAX_TOKENS) MemoryBlock(message, ids) else null
    }

    private fun ordered(active: List<MemoryItem>, maxProcedures: Int = MAX_PROCEDURES): Pair<List<MemoryItem>, List<MemoryItem>> {
        val sorted = active.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })
        return sorted.filter { it.kind != MemoryKind.procedure } to
            sorted.filter { it.kind == MemoryKind.procedure }.take(maxProcedures)
    }

    private fun render(items: List<MemoryItem>, rewrite: (String) -> String): Pair<String, List<String>> {
        val (procedures, facts) = items.partition { it.kind == MemoryKind.procedure }
        val sections = listOf(FACTS_HEADER to facts, PROCEDURES_HEADER to procedures).filter { it.second.isNotEmpty() }
        val message = sections.joinToString("\n\n") { (header, list) ->
            header + "\n" + list.withIndex().joinToString("\n") { (i, m) ->
                "${i + 1}. ${if (m.kind == MemoryKind.procedure) rewrite(m.content) else m.content}"
            }
        }
        return message to (facts + procedures).map { it.id }
    }
}
