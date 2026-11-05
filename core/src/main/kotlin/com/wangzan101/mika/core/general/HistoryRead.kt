package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.security.EgressPolicy
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunRecords
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val HISTORY_READ = "history_read"

/**
 * この会話の前のラウンドの記録を確認します: 順番なしで前のラウンドをリストします (新しい順: どのラウンド、ユーザーが言ったこと、終わり、時間)、
 * ターンを使用して、そのラウンドで書き留められたもの (完全なエンディング、ミカの進行状況の説明、読み上げられた原文) を読み、記事ごとにページ分割します。
 * は、この実行の開始によってもたらされたラウンド ([com.wangzan101.mika.core.run.RunSpec.priorTurns]、同じ会話) のみを読み取り専用で認識します。
 * 元のテキストは、スクリーンショットなしで、実行記録から取得されたものです (ダウンロード時に感度が低くされ、切り詰められています)。読まれた原文は検証のために提出され、回答に引用することができます。
 * は、ラン記録が与えられ、ラウンド内で以前に減点ランがあった場合にのみ提供されます。
 */
internal object HistoryRead : AgentTool {
    /** リストの最大ラウンド数 (新しいものから順)。 */
    const val LIST_MAX = 20
    /** 1 ページの元のテキストを合計するとおよそ何ワードになります (少なくとも 1 つ与えてください)。 */
    const val PAGE_CHARS = 8_000
    /** 元のテキストに残される最大単語数。 */
    const val ITEM_CHARS = 2_000
    /** 1回の読み上げでエンディングに何語残せるか。 */
    const val OUTCOME_CHARS = 3_000
    /** ユーザーの元の単語と末尾にリストに残っている単語の数。 */
    private const val GIST_CHARS = 200

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

    override val verb = Verb.HistoryRead
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(HISTORY_READ,
        Prompts.tool(HISTORY_READ, "page" to PAGE_CHARS),
        mapOf(
            "turn" to int("Which earlier turn to read: 1 = the turn just before this one, 2 = the one before that, and so on. Leave out to list the earlier turns.", 1),
            "offset" to int("Item to start from, from nextOffset of the previous call (default 0).", 0),
        ), emptyList())

    override fun available(ctx: ToolContext): Boolean =
        ctx.config.runRecords != null && ctx.config.runSpec?.priorTurns.orEmpty().any { it.runId != null }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val turns = ctx.config.runSpec!!.priorTurns.asReversed()
        val records = ctx.config.runRecords!!
        val n = a.int("turn", 1, Int.MAX_VALUE)
        val offset = a.int("offset", 0, Int.MAX_VALUE) ?: 0
        if (n == null) return list(turns, records)
        val turn = turns.getOrNull(n - 1)
            ?: return fail("turn_not_found", Feedback.format("history.turn_not_found", "count" to turns.size))
        return read(n, turn, records, offset)
    }

    private suspend fun list(turns: List<Turn>, records: RunRecords): ToolExecution {
        val shown = turns.take(LIST_MAX)
        val zone = ZoneId.systemDefault()
        val summaries = shown.map { t -> t.runId?.let { id -> safe(null) { records.summary(id) } } }
        return ToolExecution(okObj {
            put("turns", JsonArray(shown.mapIndexed { i, t ->
                val summary = summaries[i]
                buildJsonObject {
                    put("turn", i + 1)
                    put("said", clip(t.userText, GIST_CHARS))
                    put("status", t.status)
                    put("outcome", clip(t.outcome, GIST_CHARS))
                    summary?.let { put("time", time(it.startedAtMs, zone)) }
                    put("recorded", summary != null)
                }
            }))
            if (turns.size > shown.size) put("older", turns.size - shown.size)
        })
    }

    private suspend fun read(n: Int, t: Turn, records: RunRecords, offset: Int): ToolExecution {
        val id = t.runId
        val summary = id?.let { safe(null) { records.summary(it) } }
        val items = if (summary != null) items(safe(emptyList()) { records.events(id) }) else emptyList()
        val page = mutableListOf<Item>()
        var chars = 0
        for (item in items.drop(offset)) {
            if (page.isNotEmpty() && chars + item.text.length > PAGE_CHARS) break
            page += item
            chars += item.text.length
        }
        val next = offset + page.size
        val content = okObj {
            put("turn", n)
            put("said", t.userText)
            put("status", t.status)
            put("outcome", clip(t.outcome, OUTCOME_CHARS))
            if (t.steers.isNotEmpty()) put("userAddedDuringTask", JsonArray(t.steers.map { JsonPrimitive(clip(it, GIST_CHARS)) }))
            summary?.let { put("time", time(it.startedAtMs, ZoneId.systemDefault())) }
            put("recorded", summary != null)
            if (summary == null) put("message", Feedback["history.not_recorded"])
            else {
                put("totalItems", items.size)
                put("items", JsonArray(page.mapIndexed { i, item ->
                    buildJsonObject {
                        put("n", offset + i)
                        put("kind", item.kind)
                        val replay = EgressPolicy.replayBody(item.untrusted, item.source, item.where)
                        val body = if (replay) item.text else "egress_blocked:replay"
                        if (replay) item.where?.let { put("where", it) }
                        put("text", if (item.untrusted) Untrusted.wrap(body) else body)
                    }
                }))
                if (next < items.size) put("nextOffset", next)
            }
        }
        // 私が読んだ原文は検証のために提出されます。回答が以前のラウンドで読んだ内容を使用している場合、それは十分に文書化されています（出典も歴史として記録され、当時の場面を参照していません）
        val observations = page.mapNotNull { item ->
            val body = if (EgressPolicy.replayBody(item.untrusted, item.source, item.where)) item.text else "egress_blocked:replay"
            item.obsKind?.let { Observation(it, body, source = SOURCE, title = if (body.startsWith("egress_blocked")) null else item.where, trust = if (item.untrusted) Trust.Untrusted else Trust.Trusted) }
        }
        return ToolExecution(content, observations = observations)
    }

    /** 記録内の読み取り可能なもの: ミカの進行状況の説明 (メモ、電話の応答の言葉)、または読み取られた元のテキスト (観察の種類)。 */
    private class Item(val kind: String, val text: String, val where: String?, val untrusted: Boolean, val obsKind: ObsKind?, val source: String? = null)

    private fun items(events: List<RunEvent>): List<Item> {
        val seen = mutableSetOf<String>()
        return events.mapNotNull { e ->
            when (e) {
                // 電話をかけずに返信したものがそのラウンドの回答であり、すでに結果に含まれています。
                is RunEvent.ModelReply -> e.text.trim().takeIf { e.acted && e.tool != null && it.isNotEmpty() && seen.add(it) }
                    ?.let { Item(NOTE, clip(it, ITEM_CHARS), null, false, null) }
                is RunEvent.Observed -> e.text.trim().takeIf { it.isNotEmpty() && seen.add(it) }?.let {
                    val o = e.observation()
                    Item(o.kind.wire, clip(it, ITEM_CHARS), o.title ?: o.place?.name, e.untrusted, o.kind, o.source)
                }
                else -> null
            }
        }
    }

    private fun time(ms: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    /** レコードの読み取り中にエラーが発生しました。存在しない場合、見つからないため、タスクには影響しません。 */
    private suspend fun <T> safe(fallback: T, read: suspend () -> T): T =
        try { read() } catch (e: CancellationException) { throw e } catch (e: Exception) { fallback }

    private const val NOTE = "note"
    private const val SOURCE = "history"
}
