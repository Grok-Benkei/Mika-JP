package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PrefixStabilityTest] の同じ偽のタスクは、他のいくつかのインターフェイス (Responses、Anthropic、Gemini、および明示的なキャッシュ タグを含む) のアダプターに置き換えられます。
 * OpenAI 互換インターフェイス)、オンライン バイトで同じ項目をチェックします。エリア 0 (システム プロンプト ワード、ツール) はグループが展開されたときにのみ変更され、最初にランク付けされたツールは展開時に単語ごとに変更されません。
 * 履歴は追加されるだけで、圧縮された履歴のみが書き換えられます。再生思考 (暗号化思考、署名思考、思考署名) のすべてのステップが実行されます。
 * エリア0とメッセージ以外の最上位フィールド（思考装置、出力上限等）は毎回同じです。
 * キャッシュ タグ (cache_control) は毎回最後のタグに移動されます。タグ自体はキャッシュ キーには入力されず、比較前に削除されます。 Anthropic が一度に要求するタグは 4 つまでです。
 */
class PrefixStabilityAdaptersTest {
    private val servers = mutableListOf<MockWebServer>()
    private val pad = "第几项内容".repeat(30)

    @After fun tearDown() = servers.forEach { it.shutdown() }

    /** インターフェイス: アダプターの構築方法、リクエストをゾーン 0 と個々のメッセージに分割する方法、ツール呼び出しと最終応答を返す方法。 */
    private interface Api {
        fun model(url: String, vision: Boolean, contextTokens: Int): ChatModel
        /** エリア 0 および各メッセージが占める最上位フィールド。残りの最上位フィールドは、各リクエストで同じである必要があります。 */
        val keys: Set<String>
        /** 偽の応答の各ステップの再生値のプレフィックス (n 番目のステップはプレフィックスに n を加えたものです)。再生のないインターフェイスは null です。 */
        val replay: String?
        /** 80K ウィンドウ (圧縮) でのプレフィックスの書き換えリクエスト。[PrefixStabilityTest.compaction] を参照してください。 */
        val compactions: List<Int>
        fun zone0(body: JsonObject): String
        /** メッセージ (エントリ) ごとに、キャッシュ マークが削除されます。 */
        fun messages(body: JsonObject): List<String>
        /** 各ツールの元のバイトと名前。 */
        fun tools(body: JsonObject): List<Pair<String, String>>
        fun call(step: Int, name: String, args: JsonObject, promptTokens: Int): MockResponse
        fun answer(promptTokens: Int): MockResponse
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "data: $it\n\n" })

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "cache_control" }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    private val responses = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) = ResponsesModel(
            "$url/v1", BearerToken.fixed("k"), "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium",
        )
        override val keys = setOf("instructions", "tools", "input")
        override val replay = "ENC"
        override val compactions = listOf(9, 18, 27)
        override fun zone0(body: JsonObject) = body["instructions"].toString() + body["tools"]
        override fun messages(body: JsonObject) = body["input"]!!.jsonArray.map { it.toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.map { it.toString() to it.jsonObject.str("name")!! }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int): MockResponse {
            val call = buildJsonObject {
                put("type", "function_call"); put("id", "fc_$step"); put("call_id", "call_$step"); put("name", name); put("arguments", args.toString())
            }
            return sse(
                """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_$step","summary":[],"encrypted_content":"ENC$step"}}""",
                """{"type":"response.output_item.done","item":$call}""",
                completed(promptTokens),
            )
        }
        override fun answer(promptTokens: Int) = sse(
            """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"確認が終わりました"}]}}""",
            completed(promptTokens),
        )
        private fun completed(promptTokens: Int) =
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":$promptTokens,"output_tokens":10}}}"""
    }

    private val anthropic = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) =
            AnthropicModel(url, "k", "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium")
        override val keys = setOf("system", "tools", "messages")
        override val replay = "SIG"
        override val compactions = listOf(10, 18, 27)
        override fun zone0(body: JsonObject) = strip(body["system"]!!).toString() + strip(body["tools"]!!)
        override fun messages(body: JsonObject) = body["messages"]!!.jsonArray.map { strip(it).toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.map { strip(it).toString() to it.jsonObject.str("name")!! }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) = json(
            """{"content":[{"type":"thinking","thinking":"想第${step}步","signature":"SIG$step"},""" +
                """{"type":"tool_use","id":"tu_$step","name":"$name","input":$args}],""" +
                """"stop_reason":"tool_use","usage":{"input_tokens":$promptTokens,"output_tokens":10}}""",
        )
        override fun answer(promptTokens: Int) = json(
            """{"content":[{"type":"text","text":"確認が終わりました"}],"stop_reason":"end_turn","usage":{"input_tokens":$promptTokens,"output_tokens":10}}""",
        )
    }

    private val gemini = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) =
            GeminiModel(url, "k", "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium")
        override val keys = setOf("systemInstruction", "tools", "contents")
        override val replay = "TS"
        // リクエスト バイトは他の 3 つよりわずかに少なく、トリガー ポイントからわずかに離れています。プロンプトの単語またはツールの説明の長さが変わると、トリガー ポイントを超えるステップは他の 3 つのステップから 1 ステップ離れている可能性があります。
        // 日本語の定義と保守的な推定下限で実測した圧縮点。キャッシュ率と追加のみの区間も検査します。
        override val compactions = listOf(10, 19, 28)
        override fun zone0(body: JsonObject) = body["systemInstruction"].toString() + body["tools"]
        override fun messages(body: JsonObject) = body["contents"]!!.jsonArray.map { it.toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.flatMap { t ->
            t.jsonObject["functionDeclarations"]!!.jsonArray.map { it.toString() to it.jsonObject.str("name")!! }
        }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) =
            reply("""{"functionCall":{"name":"$name","args":$args},"thoughtSignature":"TS$step"}""", promptTokens)
        override fun answer(promptTokens: Int) = reply("""{"text":"確認が終わりました"}""", promptTokens)
        private fun reply(part: String, promptTokens: Int) = json(
            """{"candidates":[{"content":{"role":"model","parts":[$part]},"finishReason":"STOP"}],""" +
                """"usageMetadata":{"promptTokenCount":$promptTokens,"candidatesTokenCount":10}}""",
        )
    }

    /** OpenAI 互換インターフェイスはキャッシュをマークする必要があります (一部のサービス プロバイダーの互換インターフェイスは次のようなものです): 毎回マークを最後のものに移動します。 */
    private val explicitCompat = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) = OpenAiCompatModel(
            "$url/v1", "k", "m", Capabilities(true, vision, contextTokens, false, promptCaching = PromptCaching.Explicit), OkHttpClient(),
        )
        override val keys = setOf("messages", "tools")
        override val replay = null
        override val compactions = listOf(10, 18, 27)
        override fun zone0(body: JsonObject) = strip(body["messages"]!!.jsonArray.first()).toString() + body["tools"]
        override fun messages(body: JsonObject) = body["messages"]!!.jsonArray.drop(1).map { plain(strip(it).jsonObject).toString() }
        override fun tools(body: JsonObject) =
            body["tools"]!!.jsonArray.map { it.toString() to it.jsonObject["function"]!!.jsonObject.str("name")!! }

        /**
         * がマークされている場合、文字列の内容はテキスト ブロックに置き換えられます。最後の文字列でなくなると、再び文字列になります。比較すると同じ文字列として扱われます。特定のサービスプロバイダーの互換インターフェイスに基づく
         * コンテキスト キャッシュ ドキュメントの例 (2026 年 11 月 1 日に確認): 配列タグを含むシステム メッセージを初めて送信します。
         * 同じコンテンツが文字列として 2 回目に送信されると、やはりヒットします。この文書ではシステム メッセージについてのみ言及されており、他の役割も同様に処理されると推測されます。
         */
        private fun plain(m: JsonObject): JsonObject {
            val block = (m["content"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return m
            if (block.keys != setOf("type", "text") || block.str("type") != "text") return m
            return JsonObject(m + ("content" to block["text"]!!))
        }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) = json(
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call_$step","type":"function",""" +
                """"function":{"name":"$name","arguments":${JsonPrimitive(args.toString())}}}]}}],""" +
                """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}""",
        )
        override fun answer(promptTokens: Int) =
            json("""{"choices":[{"finish_reason":"stop","message":{"content":"確認が終わりました"}}],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}""")
    }

    /** は、[PrefixStabilityTest] の偽のタスクを実行し、サーバーが受信したリクエストを返します。 */
    private fun run(api: Api, vision: Boolean = false, contextTokens: Int = 1_000_000): List<JsonObject> {
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val server = MockWebServer().also { servers += it }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val raw = request.body.readUtf8()
                    val body = Json.parseToJsonElement(raw).jsonObject
                    bodies += body
                    val msgs = api.messages(body)
                    val step = STEP.findAll(msgs.last()).last().groupValues[1].toInt()
                    val tokens = TokenEstimate.of(raw)
                    if (step > STEPS) return api.answer(tokens)
                    val (name, args) = when {
                        step % 3 == 1 -> "phone_observe" to buildJsonObject {}
                        step % 3 == 2 -> lastRead(msgs.joinToString("")).let { (ref, snap) ->
                            "phone_tap" to buildJsonObject { put("ref", ref); put("snapshotId", snap) }
                        }
                        else -> "run_command" to buildJsonObject { put("command", "cat part$step.txt") }
                    }
                    return api.call(step, name, args, tokens)
                }
            }
            start()
        }
        val model = api.model(server.url("/").toString().trimEnd('/'), vision, contextTokens)
        val screens = (1..40).map { n -> Screen("com.x", (1..12).map { label("@e$it", "第${n}屏第${it}项$pad", 100 + it * 50) }) }
        val scripted = ScriptedScreen(screens)
        val port: ScreenPort = if (!vision) scripted else object : ScreenPort by scripted {
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3)
        }
        val sb = FakeSandbox().apply { next = { r -> CommandResult(0, false, false, 10, "输出${r.argv}" + "行".repeat(6000), 0, false) } }
        runTest {
            AgentLoop(model, toolDispatcher(port, sandbox = sb, workspace = { "w" }, visionEnabled = vision),
                SystemPrompt.identity(), STEPS + 5, clock = { 0L }, zone = ZoneOffset.UTC,
            ).run("把每一屏都看一遍").also { assertTrue("$it", it is TaskState.Succeeded) }
        }
        return bodies.toList()
    }

    /** 最新の画面読み取りの最初の要素の番号と画面読み取り番号。そのため、オンライン形式ごとにエスケープ層の数が異なり、エスケープすることで無制限に検索することができます。 */
    private fun lastRead(text: String): Pair<String, String> {
        val at = ELEMENTS.findAll(text).last().range.first
        return REF.find(text, at)!!.groupValues[1] to SNAPSHOT.findAll(text.substring(0, at)).last().groupValues[1]
    }

    private val entries = setOf(
        "phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command",
    )
    private val markers = listOf(
        HistoryPruner.OMITTED, HistoryPruner.OMITTED_READ, HistoryPruner.olderImage(0).substringBefore(";"), HistoryPruner.OMITTED_IMAGE, ContextCompressor.HEADER,
    )

    /**
     * 2 つのメッセージの最初の違いは、コンテンツがブロック (または部分) のリストである場合、詳細はブロックまでであることです。同じ役割の連続するメッセージを 1 つにマージするためのインターフェース (Anthropic、Gemini)
     * 概要を冒頭のユーザー メッセージにマージします。これには展開コンテキストも含まれます。十分に詳細な場合にのみ、変更されたのが概要なのか展開コンテキストなのかを判断できます。
     */
    private fun firstDiff(old: String, new: String): Pair<String, String> {
        val (a, b) = Json.parseToJsonElement(old).jsonObject to Json.parseToJsonElement(new).jsonObject
        val key = listOf("content", "parts").firstOrNull { a[it] is JsonArray && b[it] is JsonArray } ?: return old to new
        val (x, y) = a[key]!!.jsonArray to b[key]!!.jsonArray
        val same = x.zip(y).takeWhile { (p, q) -> p == q }.size
        return x.getOrNull(same).toString() to y.getOrNull(same).toString()
    }

    private class Result(val changed: List<Int>, val share: Double, val opened: List<Int>)

    /** は [PrefixStabilityTest] と同じです。 [appendOnly]: トリガーポイントを超えていないため、一度でも書き換えてはなりません。 */
    private fun check(api: Api, bodies: List<JsonObject>, label: String, appendOnly: Boolean = true): Result {
        val zone0 = bodies.map(api::zone0)
        val msgs = bodies.map(api::messages)
        val opened = (1 until bodies.size).filter { zone0[it] != zone0[it - 1] }
        assertTrue("$label: ゾーン0の変更が多すぎます: $opened", opened.size <= 2)
        for (i in opened) {
            val (a, b) = api.tools(bodies[i - 1]) to api.tools(bodies[i])
            val same = a.zip(b).takeWhile { (x, y) -> x.first == y.first }.size
            assertTrue("$label の${i + 1}回目のリクエスト: 展開時に先行ツール群が変わっています: ${a.getOrNull(same)?.second}", same == a.size || a[same].second in entries)
            assertTrue("$label の${i + 1}回目のリクエスト: 展開時は追加だけを行い、既存のツールを減らさないでください", b.size > a.size)
        }
        val changed = mutableListOf<Int>()
        for (i in 1 until bodies.size) {
            val (a, b) = msgs[i - 1] to msgs[i]
            val same = a.zip(b).takeWhile { (x, y) -> x == y }.size
            if (same == a.size) continue
            changed += i
            // 最初に書き換えられるのは、切り取られたアブストラクト、またはアブストラクトそのものです（アブストラクトは再度押すと新しいアブストラクトに置き換えられます）
            val (old, new) = firstDiff(a[same], b[same])
            assertTrue("$label の${i + 1}回目のリクエストの $same 件目の書き換えが圧縮ではありません: ${new.take(300)}",
                markers.any { it in new && it !in old } || ContextCompressor.HEADER in new)
        }
        if (appendOnly) assertEquals("$label: 発動条件に達していないのにプレフィックスが書き換わっています", emptyList<Int>(), changed)
        val rest = bodies.map { b -> JsonObject(b.filterKeys { it !in api.keys }) }.distinct()
        assertEquals("$label: ゾーン0とメッセージ以外のフィールドが変わっています", 1, rest.size)
        val full = bodies.indices.map { zone0[it] + msgs[it].joinToString("") }
        val pairs = full.zipWithNext().withIndex().filter { (i, _) -> i + 1 !in opened }.map { it.value }
        val share = pairs.sumOf { (a, b) -> a.commonPrefixWith(b).length.toLong() }.toDouble() / pairs.sumOf { (_, b) -> b.length.toLong() }
        return Result(changed, share, opened)
    }

    /** は最後に 30 ステップすべての再生を要求しました。前後 2 回含まれておらず、上記の項目が見つかりません。 */
    private fun replaysAll(api: Api, bodies: List<JsonObject>, label: String) {
        val prefix = api.replay ?: return
        val last = bodies.last().toString()
        val missing = (1..STEPS).filter { "\"$prefix$it\"" !in last }
        assertEquals("$label: 最後のリクエストに次のステップの再生情報がありません", emptyList<Int>(), missing)
    }

    // 画面読み取り、大きな結果 (および画面): 30 ステップに 1 回書き換えられず、エリア 0 は電話を初めて使用するとき、またはサンドボックスを初めて使用するときにのみ拡張されます。
    private fun appendOnly(api: Api, label: String) {
        for (vision in listOf(false, true)) {
            val l = if (vision) "$label vision" else label
            val bodies = run(api, vision = vision)
            val r = check(api, bodies, l)
            assertEquals(l, listOf(1, 3), r.opened)
            assertTrue("$l ${r.share}", r.share > 0.95)
            replaysAll(api, bodies, l)
        }
    }

    // 80K ウィンドウ: 圧縮中に数回書き換えられただけで、シーケンス番号は [PrefixStabilityTest.compaction] のアルゴリズムと同じです (Gemini、その圧縮を参照)
    private fun compaction(api: Api, label: String) {
        val bodies = run(api, contextTokens = 80_000)
        val r = check(api, bodies, "$label compaction", appendOnly = false)
        assertTrue(bodies.any { ContextCompressor.HEADER in it.toString() })
        assertEquals(api.compactions, r.changed)
        assertTrue("${r.share}", r.share > 0.85)
    }

    @Test fun responsesAppendOnly() = appendOnly(responses, "responses")
    @Test fun responsesCompaction() = compaction(responses, "responses")
    @Test fun anthropicAppendOnly() = appendOnly(anthropic, "anthropic")
    @Test fun anthropicCompaction() = compaction(anthropic, "anthropic")
    @Test fun geminiAppendOnly() = appendOnly(gemini, "gemini")
    @Test fun geminiCompaction() = compaction(gemini, "gemini")
    @Test fun explicitCompatAppendOnly() = appendOnly(explicitCompat, "compat explicit")
    @Test fun explicitCompatCompaction() = compaction(explicitCompat, "compat explicit")

    // システム プロンプト ワード、最後のツール、および最後のユーザー メッセージ。 Anthropic は一度に最大 4 つのリクエストをリクエストできます。それ以上の場合、リクエスト全体が拒否されます (400)
    @Test fun anthropicCacheMarksStayWithinLimit() {
        for (body in run(anthropic, contextTokens = 80_000)) {
            assertEquals("キャッシュマーカーの個数", 3, CACHE_MARK.findAll(body.toString()).count())
        }
    }

    private companion object {
        const val STEPS = 30
        val STEP = Regex("Step: (\\d+) of at most")
        val ELEMENTS = Regex("""elements\\*":\[\{""")
        val REF = Regex("""ref\\*":\\*"([^"\\]+)""")
        val SNAPSHOT = Regex("""snapshotId\\*":\\*"([^"\\]+)""")
        val CACHE_MARK = Regex("\"cache_control\"")
    }
}
