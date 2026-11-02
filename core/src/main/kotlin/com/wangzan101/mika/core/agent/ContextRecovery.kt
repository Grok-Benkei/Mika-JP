package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * 圧縮後の確実性：ユーザーのオリジナルの言葉（中断を含む）と固定された知識/スキルのルールは消えません。
 * は、比較するときに実行時ステータスを参照しません (フォールディングすると削除されますが、同じメッセージは引き続きカウントされます)。本当に欠落している場合は、完全な履歴の中での位置に戻され、最後まで接続されなくなります。
 */
internal object ContextRecovery {
    fun ensure(full: List<Message>, compressed: List<Message>): List<Message> {
        val userCounts = compressed.filter { it.author == Author.User }.groupingBy(::userKey).eachCount().toMutableMap()
        val ruleCounts = compressed.mapNotNull(::ruleKey).groupingBy { it }.eachCount().toMutableMap()
        fun <K> MutableMap<K, Int>.take(key: K): Boolean {
            val left = this[key] ?: 0
            if (left > 0) this[key] = left - 1
            return left > 0
        }
        val missing = full.indices.filter { i ->
            val m = full[i]
            if (m.author == Author.User) !userCounts.take(userKey(m)) else ruleKey(m)?.let { !ruleCounts.take(it) } ?: false
        }
        if (missing.isEmpty()) return compressed
        val origins = origins(full, compressed)
        val out = compressed.toMutableList()
        val at = origins.toMutableList<Int?>()
        for (i in missing) {
            // は、その後に来る最初のメッセージの前に配置されます。呼び出しと結果の間に挟まれることはありません。
            var pos = at.indexOfFirst { it != null && it > i }.let { if (it < 0) out.size else it }
            while (pos < out.size && out[pos].role == Role.Tool) pos++
            out.add(pos, ContextBuilder.withoutRuntimeState(full[i]))
            at.add(pos, i)
        }
        return out
    }

    /** 各圧縮メッセージが完全な履歴のどの部分に由来するか（請求記号またはそのままで識別されます。サマリなど新たに作成されたものは null）。 */
    private fun origins(full: List<Message>, compressed: List<Message>): List<Int?> {
        val byId = HashMap<String, Int>()
        val byKey = HashMap<Message, ArrayDeque<Int>>()
        full.forEachIndexed { i, m ->
            ids(m).forEach { byId.putIfAbsent(it, i) }
            byKey.getOrPut(userKey(m)) { ArrayDeque() }.addLast(i)
        }
        return compressed.map { m -> ids(m).firstNotNullOfOrNull(byId::get) ?: byKey[userKey(m)]?.removeFirstOrNull() }
    }

    private fun ids(m: Message): List<String> = m.parts.mapNotNull {
        when (it) {
            is Part.ToolCall -> it.id
            is Part.ToolResult -> it.callId
            else -> null
        }
    }

    private fun userKey(m: Message): Message = ContextBuilder.withoutRuntimeState(m)

    private fun ruleKey(message: Message): Pair<ContextKind, String?>? =
        ContextBuilder.kindOf(message)?.takeIf { it.pinned }?.let { it to ContextBuilder.sourceOf(message) }
}
