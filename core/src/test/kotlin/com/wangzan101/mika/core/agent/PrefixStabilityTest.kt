package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.label
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * 30 ステップの偽のタスク (画面を読み取り、クリックし、コマンドを順番に実行する) が、実際の OpenAI 互換アダプターを通じて偽のサーバーに送信され、2 つの隣接するリクエストがオンライン バイトに従って比較されます。
 * システム プロンプトの単語とツールは、グループを展開した後のリクエストでのみ変更されます (ステップ 1: 画面を読んで電話機を展開し、ステップ 3: コマンドを実行してサンドボックスを展開します (グループごとに 1 回))。
 * が変更されると、ツール リストでグループの前にランク付けされているパーツはそのまま変更されず、残りのリクエストはそのまま同じになります。
 * 履歴は追加されるだけです。圧縮トリガーポイントを超えていないと推定される場合は一度も書き換えられず、圧縮要求のみが書き換えられます。
 * であり、最初に書き換えられたメッセージは短縮された (または要約に折りたたまれた) メッセージです。
 */
class PrefixStabilityTest {
    private val servers = mutableListOf<MockWebServer>()
    private val pad = "第几项内容".repeat(30)

    @After fun tearDown() = servers.forEach { it.shutdown() }

    /** フェイク サーバー: リクエスト終了時のランタイム ステータスのステップ数に基づいてどのステップであるかを判断し (アシスタント メッセージは折りたたみ後に小さくなります)、スクリプトに従って応答します。 [fail] の最初のステップは 429 を返します。 */
    private inner class Wire(val fail: Set<Int> = emptySet()) {
        val bodies = CopyOnWriteArrayList<JsonObject>()
        private val tried = mutableSetOf<Int>()
        val server = MockWebServer().also { servers += it }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    bodies += body
                    val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
                    val step = STEP.findAll(msgs.last().toString()).last().groupValues[1].toInt()
                    if (step in fail && tried.add(step)) return MockResponse().setResponseCode(429).setHeader("retry-after", "0")
                    return MockResponse().setBody(reply(step, msgs, TokenEstimate.of(body.toString())).toString())
                }
            }
            start()
        }

        fun model(vision: Boolean, contextTokens: Int) =
            OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", Capabilities(true, vision, contextTokens, false), OkHttpClient())
    }

    /** [promptTokens]: 実際のサービス プロバイダーと同様に、このリクエストのサイズに従って入力使用量をレポートします (圧縮タイミングは実際の使用量に従って計算されます。[UsageMeter] を参照)。 */
    private fun reply(step: Int, msgs: List<JsonObject>, promptTokens: Int): JsonObject {
        // 最後のステップでは呼び出しはなく、テキスト応答のみです。これが最終的な応答です
        if (step > STEPS) return buildJsonObject {
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("finish_reason", "stop")
                    putJsonObject("message") { put("content", "確認が終わりました") }
                })
            }
            putJsonObject("usage") { put("prompt_tokens", promptTokens); put("completion_tokens", 10) }
        }
        val (name, args) = when {
            step % 3 == 1 -> "phone_observe" to buildJsonObject {}
            step % 3 == 2 -> lastRead(msgs).let { (ref, snap) -> "phone_tap" to buildJsonObject { put("ref", ref); put("snapshotId", snap) } }
            else -> "run_command" to buildJsonObject { put("command", "cat part$step.txt") }
        }
        return buildJsonObject {
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("finish_reason", "tool_calls")
                    putJsonObject("message") {
                        put("content", JsonPrimitive(null as String?))
                        putJsonArray("tool_calls") {
                            add(buildJsonObject {
                                put("id", "call_$step"); put("type", "function")
                                putJsonObject("function") { put("name", name); put("arguments", args.toString()) }
                            })
                        }
                    }
                })
            }
            putJsonObject("usage") { put("prompt_tokens", promptTokens); put("completion_tokens", 10) }
        }
    }

    /** 最新の画面読み取りの最初の要素の番号と画面読み取り番号 (差分画面使用時に番号が並べ替えられるため、ハードコーディングできません)。 */
    private fun lastRead(msgs: List<JsonObject>): Pair<String, String> {
        val c = msgs.filter { it.str("role") == "tool" }.map { it.str("content").orEmpty() }.last { "\"elements\":[{" in it }
        return c.substringAfter("\"ref\":\"").substringBefore('"') to c.substringAfter("\"snapshotId\":\"").substringBefore('"')
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private class Setup(val vision: Boolean = false, val inline: Boolean = false, val fail: Set<Int> = emptySet(),
                        val light: Boolean = false, val contextTokens: Int = 1_000_000,
                        /** 前回のラウンドで使用したグループ: 先頭で展開します。 */
                        val prior: List<String> = emptyList())

    /** は 1 回実行され、各サーバーが受信したリクエストを返します (メイン モデルが最初)。 */
    private fun run(s: Setup): List<List<JsonObject>> {
        val screens = (1..40).map { n -> Screen("com.x", (1..12).map { label("@e$it", "第${n}屏第${it}项$pad", 100 + it * 50) }) }
        val scripted = ScriptedScreen(screens)
        val port: ScreenPort = if (!s.vision) scripted else object : ScreenPort by scripted { override suspend fun screenshotPng() = byteArrayOf(1, 2, 3) }
        val sb = FakeSandbox().apply { next = { r -> CommandResult(0, false, false, 10, "输出${r.argv}" + "行".repeat(6000), 0, false) } }
        val main = Wire(s.fail)
        val light = if (s.light) Wire() else null
        val mainModel = main.model(s.vision, s.contextTokens)
        val lightModel = light?.model(false, s.contextTokens)
        runTest {
            AgentLoop(mainModel, toolDispatcher(port, sandbox = sb, workspace = { "w" }, inlineScreen = s.inline, visionEnabled = s.vision),
                SystemPrompt.identity(), STEPS + 5, clock = { 0L }, zone = ZoneOffset.UTC,
                flags = SpeedFlags(inlineScreen = s.inline, lightSteps = s.light), router = lightModel?.let { ModelRouter(mainModel, it) },
            ).run("把每一屏都看一遍", if (s.prior.isEmpty()) emptyList() else listOf(Turn("上一轮", "好", "succeeded", groups = s.prior))).also { assertTrue("$it", it is TaskState.Succeeded) }
        }
        return listOfNotNull(main, light).map { it.bodies.toList() }
    }

    /** オンライン リクエスト: エリア 0 (システム プロンプト ワード、ツール) および個々のメッセージは、送信された元のバイトです。 [tools] は各ツールの元のバイトです。 */
    private class Req(val zone0: String, val msgs: List<String>, val raw: List<JsonObject>, val tools: List<String>)

    private fun wire(body: JsonObject): Req {
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        return Req(msgs.first().toString() + body["tools"].toString(), msgs.drop(1).map { it.toString() }, msgs.drop(1),
            body["tools"]!!.jsonArray.map { it.toString() })
    }

    /** はオンライン ツールによって定義された名前です。 */
    private fun nameOf(tool: String) = Json.parseToJsonElement(tool).jsonObject["function"]!!.jsonObject.str("name")

    /** エントリ ツールは閉じているときにも表示されます。展開したときに変更する最初のツールは、そのうちの 1 つ (または最後の新しいツール) のみです。 */
    private val entries = setOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command")

    private val markers = listOf(HistoryPruner.OMITTED, HistoryPruner.OMITTED_READ, "(更早的画面已省略", HistoryPruner.OMITTED_IMAGE, ContextCompressor.HEADER)

    /**
     * オンライン バイト単位の 2 つの隣接するリクエストの共通プレフィックス: Σ 共通プレフィックス長 ÷ Σ リクエスト長 (文字数)。グループが展開される回数（[skip]、設計上プレフィックスは1回無効）はカウントされません。
     * と [check] は、そのグループから始まる部分のみを変更したことを確認しました。
     */
    private fun prefixShare(reqs: List<Req>, skip: List<Int> = emptyList()): Double {
        val full = reqs.map { it.zone0 + it.msgs.joinToString("") }
        val pairs = full.zipWithNext().withIndex().filter { (i, _) -> i + 1 !in skip }.map { it.value }
        val common = pairs.sumOf { (a, b) -> a.commonPrefixWith(b).length.toLong() }
        return common.toDouble() / pairs.sumOf { (_, b) -> b.length.toLong() }
    }

    private class Result(val pairs: Int, val changed: List<Int>, val share: Double, val opened: List<Int>)

    /** [appendOnly]: トリガーポイントを超えていないため、一度も書き換えるべきではありません。呼び出し元は圧縮時に正確な期待値を与えます。 */
    private fun check(bodies: List<JsonObject>, label: String, appendOnly: Boolean = true): Result {
        val reqs = bodies.map(::wire)
        // エリア 0 は、グループが最大 2 回展開されるときにのみ変更されます (携帯電話、サンドボックス)。変更する場合、以前のツールはそのまま残り、最初に変更されるのはそのグループの入り口です
        val opened = (1 until reqs.size).filter { reqs[it].zone0 != reqs[it - 1].zone0 }
        assertTrue("$label: ゾーン0の変更が多すぎます: $opened", opened.size <= 2)
        for (i in opened) {
            val (a, b) = reqs[i - 1].tools to reqs[i].tools
            val same = a.zip(b).takeWhile { (x, y) -> x == y }.size
            assertTrue("$label の${i + 1}回目のリクエスト: 展開時に先行ツール群が変わっています: ${a.getOrNull(same)?.let(::nameOf)}", same == a.size || nameOf(a[same]) in entries)
            assertTrue("$label の${i + 1}回目のリクエスト: 展開時は追加だけを行い、既存のツールを減らさないでください", b.size > a.size)
        }
        val changed = mutableListOf<Int>()
        for (i in 1 until reqs.size) {
            val (a, b) = reqs[i - 1] to reqs[i]
            val same = a.msgs.zip(b.msgs).takeWhile { (x, y) -> x == y }.size
            if (same == a.msgs.size) continue
            changed += i
            // 最初に書き換えられた項目は、切り抜き線で切り取られた (または要約に折り込まれた) 項目です。
            val first = b.msgs[same]
            assertTrue("$label の${i + 1}回目のリクエストの $same 件目の書き換えが圧縮ではありません: $first", markers.any { it in first && it !in a.msgs[same] } || ContextCompressor.HEADER in first)
        }
        if (appendOnly) assertEquals("$label: 発動条件に達していないのにプレフィックスが書き換わっています", emptyList<Int>(), changed)
        return Result(reqs.size - 1, changed, prefixShare(reqs, opened), opened)
    }

    // 画面読み取り、大きな結果、画面は追加のみです。30 ステップに 1 回書き換えはありません。
    @Test fun textOnly() {
        val r = check(run(Setup()).single(), "text")
        // ステップ 1 の画面を読んだ後の電話機の拡張リクエスト、およびステップ 3 のコマンドを実行した後のサンドボックスの拡張
        assertEquals(listOf(1, 3), r.opened)
        assertTrue("${r.share}", r.share > 0.95)
    }

    // は最終ラウンドで携帯電話とサンドボックスを使用しました。最初から開始され、エリア 0 は 30 ステップで一度も変化しませんでした。
    @Test fun preloadedGroupsNeverChangeZoneZero() {
        val r = check(run(Setup(prior = listOf("phone", "sandbox"))).single(), "preloaded")
        assertEquals(emptyList<Int>(), r.opened)
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun withVisionImages() {
        val r = check(run(Setup(vision = true)).single(), "vision")
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun withInlineScreens() {
        val r = check(run(Setup(inline = true)).single(), "inline")
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun retriedRequestsAreIdentical() {
        val bodies = run(Setup(fail = setOf(5, 14))).single()
        // 再試行時刻と拒否時刻はそのまま表示されます。
        assertEquals(33, bodies.size)
        check(bodies, "retry")
    }

    @Test fun lightModelRouting() {
        val (main, light) = run(Setup(light = true))
        assertTrue("軽量モデルがいくつかのステップを担当する必要があります", light.isNotEmpty())
        check(main, "main")
        check(light, "light")
    }

    @Test fun compaction() {
        val bodies = run(Setup(contextTokens = 80_000)).single()
        val r = check(bodies, "compaction", appendOnly = false)
        assertTrue(bodies.any { ContextCompressor.HEADER in it.toString() })
        // 推定下限を保ち、圧縮の境界を超えたときだけ履歴を書き換えます。それ以外の追加は既存のプレフィックスを保ちます。
        assertEquals(listOf(10, 18, 27), r.changed)
        assertTrue("${r.share}", r.share > 0.85)
    }

    private companion object {
        const val STEPS = 30
        val STEP = Regex("Step: (\\d+) of at most")
    }
}
