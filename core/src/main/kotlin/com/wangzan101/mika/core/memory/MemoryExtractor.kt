package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelJson
import com.wangzan101.mika.core.model.ModelJson.field
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Suppress("EnumEntryName")
enum class NoteKind { added, updated, forgot, pending }

/**
 * 結果カードの下の行。タイプと番号のみが含まれ、メモリ内容は含まれません。会話ファイルはプレーン テキストであり、内容は毎回暗号化されたメモリからフェッチされます ([text])。
 * [undoToken] は [MemoryStore.undo] に引き継がれます。 [pendingId] は承認待ちです (「OK」 = 承認、「しない」 = 取り消しまたは削除)。
 */
data class MemoryNote(val kind: NoteKind, val memoryId: String, val undoToken: String?, val pendingId: String?) {
    /** [item] は現時点では [memoryId] です。それがなくなった（削除された、クリアされた）場合は、「削除されたメモリ」と書きます。 */
    fun text(item: MemoryItem?): String {
        val content = item?.content ?: DELETED
        return when (kind) {
            NoteKind.added -> "覚えました:$content"
            NoteKind.updated -> "更新しました:$content" + (item?.replaces?.let { "（前は:$it）" } ?: "")
            NoteKind.forgot -> if (item == null) "1件のメモリを忘れました" else "忘れました:$content"
            NoteKind.pending -> "今後もこのようにしますか？$content"
        }
    }

    companion object {
        const val DELETED = "削除済みのメモリ"
    }
}

/**
 * タスク後の別の呼び出し (軽量モデル、ツールなし): 今回ユーザーが言ったことと関連する既存の記憶のみを確認し、追加、変更、削除を提供します。
 * モデルで指定されたすべての項目は、コードによって再度チェックされます。モデルがエラーを起こした場合、または応答が不適格だった場合、何も行われず、タスクの結果は影響を受けません。
 */
class MemoryExtractor(private val model: ChatModel) {
    suspend fun extract(utterances: List<String>, related: List<MemoryItem>, store: MemoryStore, source: (String) -> MemorySource): List<Change> {
        if (utterances.none { it.isNotBlank() }) return emptyList()
        val labels = related.take(MAX_RELATED).mapIndexed { i, m -> "#${i + 1}" to m }.toMap()
        val ops = try {
            ModelJson.completeParsed(model, PROMPT, listOf(Message(Role.User, listOf(Part.Text(request(utterances, labels))), Author.Mika)), ::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val said = UserUtterances().apply { utterances.forEach(::add) }
        return ops.take(MAX_OPS).mapNotNull { apply(it, labels, said, store, source) }
    }

    private fun apply(o: JsonObject, labels: Map<String, MemoryItem>, said: UserUtterances, store: MemoryStore, source: (String) -> MemorySource): Change? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val quote = str("quote")?.takeIf { said.contains(it) } ?: return null
        val keywords = (o["keywords"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
        val target = str("target")?.let { t -> labels[t] ?: labels["#$t"] }
        fun fromUser(content: String) = (listOf(content) + keywords).all { said.unsupportedSpecifics(it, quote).isEmpty() }
        return when (str("op")) {
            "add" -> {
                val kind = MemoryKind.entries.find { it.name == str("kind") } ?: return null
                val content = str("content")?.takeIf(::fromUser) ?: return null
                store.add(kind, content, keywords, source(quote)).getOrNull()
            }
            "update" -> {
                val t = target ?: return null
                // 1 回限りのパラメータでは長期的な設定を変更できません
                if (ONE_OFF.containsMatchIn(quote)) return null
                val content = str("content")?.takeIf(::fromUser) ?: return null
                store.update(t.id, content, keywords.takeIf { it.isNotEmpty() }, source(quote)).getOrNull()
            }
            "forget" -> {
                val t = target ?: return null
                if (!FORGET.containsMatchIn(quote)) return null
                store.forget(t.id).getOrNull()
            }
            else -> null
        }
    }

    private fun request(utterances: List<String>, labels: Map<String, MemoryItem>) = buildString {
        appendLine("ユーザーが今回言ったこと:")
        utterances.filter { it.isNotBlank() }.forEachIndexed { i, u -> appendLine("${i + 1}. $u") }
        appendLine()
        if (labels.isEmpty()) append("関連する既存のメモリ:なし")
        else {
            appendLine("関連する既存のメモリ:")
            labels.forEach { (label, m) -> appendLine("$label [${m.kind.name}] ${m.content}") }
        }
    }.trimEnd()

    companion object {
        const val MAX_OPS = 5
        const val MAX_RELATED = 10
        // ユーザーの言葉の照合用データ:日本語を正とし、中国語の旧表現もデータとして保持する
        private val FORGET = Regex("忘|覚えない|削除|删除|消して|别记|不用记|forget", RegexOption.IGNORE_CASE)
        private val ONE_OFF = Regex("今回|今日は|この回|这次|这一次|今天先|this time", RegexOption.IGNORE_CASE)

        val PROMPT: String by lazy { Prompts.role("memory_extract") }

        /** 今回話された内容に関連する既存の記憶（同じアプローチを繰り返さないように承認待ちの記憶も表示されます）。 */
        fun related(utterances: List<String>, items: List<MemoryItem>): List<MemoryItem> =
            MemoryIndex(items).search(utterances.joinToString("\n"), MAX_RELATED, includePending = true).map { it.first }

        /** 結果カードの下のプロンプト ラインを変更します。 */
        fun notes(changes: List<Change>): List<MemoryNote> = changes.map { c ->
            when (c) {
                is Change.Added ->
                    if (c.item.status == MemoryStatus.pending) MemoryNote(NoteKind.pending, c.item.id, c.undoToken, c.item.id)
                    else MemoryNote(NoteKind.added, c.item.id, c.undoToken, null)
                is Change.Updated ->
                    if (c.item.status == MemoryStatus.pending) MemoryNote(NoteKind.pending, c.item.id, c.undoToken, c.item.id)
                    else MemoryNote(NoteKind.updated, c.item.id, c.undoToken, null)
                is Change.Forgot -> MemoryNote(NoteKind.forgot, c.before.id, c.undoToken, null)
            }
        }

        /** 応答内の最初の読み取り可能な JSON オブジェクト ([ModelJson] を参照)。修飾されていない場合は null を返します。 */
        internal fun parse(text: String): List<JsonObject>? {
            val obj = ModelJson.firstObject(text) ?: return null
            return (obj.field("ops", "operations") as? JsonArray)?.mapNotNull { it as? JsonObject }
        }
    }
}
