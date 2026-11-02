package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

/** 画面読み取り以外の大きな結果 (Web ページの読み取り、コマンド出力、ファイルの読み取りなど) は、同じ切断線に従って切り取られます。つまり、行の前には短いフィールドだけが残ります。 */
class HistoryPrunerBigReadsTest {
    private fun round(id: String, tool: String, content: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, tool, "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id, content, false)), Author.Mika),
    )

    private fun page(i: Int, chars: Int, filler: String) = buildJsonObject {
        put("ok", true); put("url", "https://example.com/$i"); put("text", filler.repeat(chars / filler.length)); put("nextOffset", chars)
    }.toString()

    private fun results(ms: List<Message>) = ms.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()

    @Test fun fivePageReadsOf30kFitA64kBudget() {
        val history = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mika)) +
            (1..5).flatMap { round("c$it", "web_read_page", page(it, 30_000, "网页正文内容")) }
        assertTrue(TokenEstimate.of(history) > 64_000)
        val out = ContextCompressor(TOOLS).compress(history, contextTokens = 64_000).messages
        assertTrue("estimate ${TokenEstimate.of(out)}", TokenEstimate.of(out) < 64_000)
        val rs = results(out)
        // 古い結果を短縮して超過するだけです。古い読み取り専用には短いフィールド (url/nextOffset を押して再度読み取ることができます) があるか、概要に組み込まれます。最新のものはそのままです
        for (r in rs.dropLast(1)) {
            val o = Json.parseToJsonElement(r.content) as JsonObject
            assertEquals(HistoryPruner.OMITTED_READ, o["text"]!!.jsonPrimitive.content)
            assertEquals(30_000, o["nextOffset"]!!.jsonPrimitive.content.toInt())
            assertTrue(o["url"]!!.jsonPrimitive.content.startsWith("https://"))
        }
        assertEquals(results(history).last().content, rs.last().content)
    }

    @Test fun sameRuleForEveryReadingToolAndReferenceIsKept() {
        val big = "x".repeat(10_000)
        val history = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mika)) +
            round("a", "knowledge_read", buildJsonObject { put("content", big) }.toString()) +
            round("b", "run_command", buildJsonObject { put("output", big); put("exitCode", 0) }.toString()) +
            round("c", "read_file", buildJsonObject { put("content", big); put("path", "/workspace/a.txt"); put("nextOffset", 10) }.toString()) +
            round("d", "web_evaluate_js", buildJsonObject { put("result", JsonObject(mapOf("rows" to JsonPrimitive(big)))) }.toString()) +
            round("e", "run_bundled_script", "plain text output " + big) +
            round("f", "search_files", buildJsonObject { put("matches", big) }.toString()) +
            round("g", "phone_tap", "{\"ok\":true}")
        val rs = results(HistoryPruner.prune(history, TOOLS, HistoryPruner.Cut(2))).associateBy { it.callId }
        assertEquals(big, (Json.parseToJsonElement(rs["a"]!!.content) as JsonObject)["content"]!!.jsonPrimitive.content)
        assertTrue(HistoryPruner.OMITTED_READ in rs["b"]!!.content && "\"exitCode\":0" in rs["b"]!!.content)
        assertTrue(HistoryPruner.OMITTED_READ in rs["c"]!!.content && "/workspace/a.txt" in rs["c"]!!.content)
        // 7 往復: 5 番目の切断線以降、その線の前の b ～ e がすべて切断され (参照 a を除く)、線以降の f は変更されません。
        assertTrue(HistoryPruner.OMITTED_READ in rs["d"]!!.content)
        assertEquals(HistoryPruner.OMITTED_READ, rs["e"]!!.content)
        assertEquals(results(history).first { it.callId == "f" }.content, rs["f"]!!.content)
        // 小さい結果が動かない
        val small = listOf(Message(Role.User, listOf(Part.Text("t")), Author.Mika)) + (1..9).flatMap { round("s$it", "run_command", "{\"output\":\"ok\"}") }
        assertEquals(small, HistoryPruner.prune(small, TOOLS, HistoryPruner.Cut(2)))
    }
}
