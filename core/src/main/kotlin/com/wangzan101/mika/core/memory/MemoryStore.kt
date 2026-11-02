package com.wangzan101.mika.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** は 1 回変更されます。 [undoToken] は [MemoryStore.undo] に引き継がれ、変更前に復元されます。 */
sealed interface Change {
    val undoToken: String

    data class Added(val item: MemoryItem, override val undoToken: String) : Change
    data class Updated(val item: MemoryItem, val before: MemoryItem, override val undoToken: String) : Change
    data class Forgot(val before: MemoryItem, override val undoToken: String) : Change
}

/** はコピー全体の読み取りと書き込みを行います。暗号化されたディスクはアプリ層によって実装されます。 */
interface MemoryPersistence {
    fun load(): List<MemoryItem>
    fun save(items: List<MemoryItem>)
}

/** [code]:`sensitive`|`too_long`|`memory_full`|`not_found`|`unavailable` (メモリファイルは一時的に読み取れません)。 */
class MemoryRejected(val code: String) : Exception(code)

/**
 * はメモリの唯一の書き込みポートです。すべての書き込みメソッドは同じロックを共有します。つまり、並列タスクの書き込みシリアル化です。
 * は [SensitiveFilter] より前に書き込まれました。ディスク全体が毎回変更されます。失効トークンは最新の [UNDO_KEEP] のみをメモリに保持します。
 */
class MemoryStore(
    private val persistence: MemoryPersistence,
    val clock: () -> Long,
    private val ids: () -> String,
    val limit: Int = 500,
) {
    private val lock = Any()
    // を読み取れない (キーストアが一時的に利用できない) 場合、空として使用されますが、書き込みは許可されません。書き込まれると、実メモリ ファイルは空のテーブルで上書きされます。
    @Volatile private var loaded = false
    private val _items = MutableStateFlow(tryLoad() ?: emptyList())
    val items: StateFlow<List<MemoryItem>> = _items.asStateFlow()

    /** 今度はメモリ ファイルが読み取られました。 false の場合、書き込みは拒否されます (`unavailable`)、[reloadIfNeeded]、次回再試行します。 */
    val available: Boolean get() = loaded

    /** 以前に読み取られていない場合は、再度読み取ります (各タスクの開始時と各書き込み前に呼び出されます)。読み取られると true を返します。 */
    fun reloadIfNeeded(): Boolean = synchronized(lock) {
        if (loaded) return true
        val got = tryLoad() ?: return false
        _items.value = got
        true
    }

    private fun tryLoad(): List<MemoryItem>? = try {
        persistence.load().also { loaded = true }
    } catch (e: Exception) {
        null
    }

    // 広告掲載オーダーは古い順と新しい順です。それを超えると、最も古いものが破棄されます。
    private val undos = LinkedHashMap<String, Change>()

    fun add(kind: MemoryKind, content: String, keywords: List<String>, source: MemorySource): Result<Change.Added> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val text = clean(content)
        val kws = cleanKeywords(keywords)
        check(text, kws, source)?.let { return Result.failure(it) }
        if (_items.value.size >= limit) return Result.failure(MemoryRejected("memory_full"))
        val now = clock()
        val item = MemoryItem(
            id = ids(), kind = kind, content = text, keywords = kws, source = source,
            status = if (kind == MemoryKind.procedure) MemoryStatus.pending else MemoryStatus.active,
            createdAt = now, updatedAt = now,
        )
        commit(_items.value + item)
        Result.success(remember { Change.Added(item, it) })
    }

    /**
     * [keywords]/[source] が null の場合、元の値が使用されます。新しい非手書きソース (モデル抽出、memory_save) によって変更された後は、プラクティスを再承認する必要があります。
     * ユーザーが設定 (手動) で行った変更、またはソースなしで加えられた変更は、元の状態のままになります。
     */
    fun update(id: String, content: String, keywords: List<String>?, source: MemorySource?): Result<Change.Updated> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val before = _items.value.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        val text = clean(content)
        val kws = keywords?.let(::cleanKeywords) ?: before.keywords
        val src = source ?: before.source
        check(text, kws, src)?.let { return Result.failure(it) }
        val reapprove = before.kind == MemoryKind.procedure && source != null && source.type != "manual"
        val item = before.copy(
            content = text, keywords = kws, source = src, updatedAt = clock(),
            replaces = if (text != before.content) before.content else before.replaces,
            status = if (reapprove) MemoryStatus.pending else before.status,
        )
        commit(_items.value.map { if (it.id == id) item else it })
        Result.success(remember { Change.Updated(item, before, it) })
    }

    fun forget(id: String): Result<Change.Forgot> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val before = _items.value.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        commit(_items.value.filter { it.id != id })
        Result.success(remember { Change.Forgot(before, it) })
    }

    /**
     * トークンが不明、期限切れ、または使用中 → false。更新の取り消しは、エントリがこの変更と同じである場合にのみ行われます (その後、変更/忘れられた場合 → false)。
     * は、その後のすべての変更が消去されることを回避します。配置が失敗した場合にスローされ、トークンは保持され、再試行できます。
     */
    fun undo(token: String): Boolean = synchronized(lock) {
        val change = undos[token] ?: return false
        val cur = _items.value
        val next = when (change) {
            is Change.Added -> cur.filter { it.id != change.item.id }
            is Change.Updated -> {
                // は、今回は変更されたフィールドのみを確​​認します。その後の使用回数とピンは「再度変更された」としてカウントされず、復元時にも保持されます。
                val now = cur.find { it.id == change.item.id }
                if (now == null || now.content != change.item.content || now.keywords != change.item.keywords || now.source != change.item.source) return false
                val restored = change.before.copy(useCount = now.useCount, lastUsedAt = now.lastUsedAt, pinned = now.pinned)
                cur.map { if (it.id == restored.id) restored else it }
            }
            is Change.Forgot -> cur.filter { it.id != change.before.id } + change.before
        }
        commit(next)
        undos.remove(token)
        true
    }

    fun approve(id: String): Boolean = synchronized(lock) {
        if (!loaded) return false
        val cur = _items.value
        if (cur.none { it.id == id && it.status == MemoryStatus.pending }) return false
        commit(cur.map { if (it.id == id) it.copy(status = MemoryStatus.active) else it })
        true
    }

    fun setPinned(id: String, pinned: Boolean): Unit = synchronized(lock) {
        if (!loaded) return
        val cur = _items.value
        if (cur.none { it.id == id }) return
        commit(cur.map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun markUsed(ids: List<String>): Unit = synchronized(lock) {
        if (ids.isEmpty() || !loaded) return
        val set = ids.toSet()
        val now = clock()
        commit(_items.value.map { if (it.id in set) it.copy(useCount = it.useCount + 1, lastUsedAt = now) else it })
    }

    /** ユーザーはすべてをクリアしたいと考えています。読み取ることができない元のファイルも空のテーブルで上書きされます。これはまさにユーザーが望んでいることです。 */
    fun clearAll(): Unit = synchronized(lock) {
        undos.clear()
        loaded = true
        commit(emptyList())
    }

    private fun check(content: String, keywords: List<String>, source: MemorySource): MemoryRejected? {
        if (content.isEmpty() || content.length > MAX_CONTENT) return MemoryRejected("too_long")
        val texts = listOf(content) + keywords + listOfNotNull(source.quote)
        if (texts.any { SensitiveFilter.hit(it) != null }) return MemoryRejected("sensitive")
        return null
    }

    // 改行、連続空白は 1 つのスペースに押し込まれます。メモリ ブロックには行に従って番号が付けられ、コンテンツ内に改行を入れることはできません。
    private fun clean(content: String) = WHITESPACE.replace(content.trim(), " ")

    private fun cleanKeywords(keywords: List<String>) =
        keywords.map(::clean).filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)

    private fun commit(next: List<MemoryItem>) {
        // 結論: 元のファイルを読み込まずに書き込みを行うことはできません。
        check(loaded) { "memory not loaded" }
        persistence.save(next)
        _items.value = next
    }

    private fun <C : Change> remember(make: (String) -> C): C {
        val change = make(ids())
        undos[change.undoToken] = change
        while (undos.size > UNDO_KEEP) undos.remove(undos.keys.first())
        return change
    }

    companion object {
        const val MAX_CONTENT = 200
        const val MAX_KEYWORDS = 8
        const val UNDO_KEEP = 50
        private val WHITESPACE = Regex("\\s+")
    }
}
