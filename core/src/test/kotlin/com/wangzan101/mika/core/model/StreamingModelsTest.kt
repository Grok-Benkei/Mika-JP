package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.agent.RetryPolicy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingModelsTest {
    private lateinit var server: MockWebServer
    private val caps = Capabilities(true, false, 32000, false)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun event(data: String) = "data: $data\n\n"
    private fun stream(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { event(it) })

    @Test fun openAiStreamsTextAndAssemblesSplitToolCall() = runTest {
        server.enqueue(stream(
            """{"choices":[{"delta":{"reasoning_content":"private","content":"先"}}]}""",
            """{"choices":[{"delta":{"content":"看","tool_calls":[{"index":0,"id":"c1","function":{"name":"phone_","arguments":"{\"re"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"tap","arguments":"f\":\"a1\"}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3}}""",
            "[DONE]",
        ))
        val updates = mutableListOf<String>()
        val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val reply = model.completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf("先", "先看"), updates)
        val thinking = Part.Thinking("private", ReasoningVia.ReasoningContent.tag(server.url("/v1/chat/completions").toString()))
        assertEquals(listOf(thinking, Part.Text("先看"), Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")), reply.parts)
        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals(Usage(10, 3), reply.usage)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
    }

    /** について考えると、`reasoning` フィールドまたは `content` はブロック配列です。両方を認識し、受信した位置を記録します。 */
    @Test fun openAiStreamReadsReasoningFieldAndChunkedContent() = runTest {
        val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val endpoint = server.url("/v1/chat/completions").toString()
        server.enqueue(stream(
            """{"choices":[{"delta":{"reasoning":"想"}}]}""",
            """{"choices":[{"delta":{"reasoning":"好了","content":"答"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        ))
        val reply = model.completeStreaming("s", user, emptyList()) {}
        assertEquals(listOf(Part.Thinking("想好了", ReasoningVia.Reasoning.tag(endpoint)), Part.Text("答")), reply.parts)

        server.enqueue(stream(
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"先"}]}]}}]}""",
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"想"}]}]}}]}""",
            """{"choices":[{"delta":{"content":"答案"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        ))
        val updates = mutableListOf<String>()
        val chunked = model.completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf(Part.Thinking("先想", ReasoningVia.ThinkingChunks.tag(endpoint)), Part.Text("答案")), chunked.parts)
        assertEquals(listOf("答案"), updates)
    }

    @Test fun anthropicStreamsTextAndToolInput() = runTest {
        server.enqueue(stream(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"phone_tap","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"ref\":\"a1\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}""",
        ))
        val updates = mutableListOf<String>()
        val model = AnthropicModel(server.url("/").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val reply = model.completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf("好"), updates)
        assertEquals(listOf(Part.Text("好"), Part.ToolCall("t1", "phone_tap", """{"ref":"a1"}""")), reply.parts)
        assertEquals(Usage(10, 5), reply.usage)
    }

    /**
     * message_delta の使用量は累積値です。入力とキャッシュもここで更新できます (値の最初のセットはインターフェイス ドキュメントのストリーミング例から取得されます)。含まれるフィールドはその対象となります。
     * が含まれていない場合、値が null または整数でない場合は、message_start が使用されます。 message_start がすべて 0 であるか、使用法が指定されていない場合は、message_delta が使用されます。
     */
    @Test fun anthropicStreamTakesCumulativeUsageFromMessageDelta() = runTest {
        fun reply(start: String, delta: String) = stream(
            """{"type":"message_start","message":{$start}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":$delta}""",
            """{"type":"message_stop"}""",
        )
        server.enqueue(reply(
            """"usage":{"input_tokens":2679,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":3}""",
            """{"input_tokens":10682,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":510}""",
        ))
        server.enqueue(reply(
            """"usage":{"input_tokens":10,"cache_read_input_tokens":100,"output_tokens":1}""",
            """{"output_tokens":5,"cache_read_input_tokens":null,"input_tokens":12.5}""",
        ))
        server.enqueue(reply(
            """"usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0}""",
            """{"input_tokens":5,"cache_creation_input_tokens":20,"cache_read_input_tokens":100,"output_tokens":7}""",
        ))
        server.enqueue(reply("", """{"input_tokens":7,"output_tokens":2}"""))
        assertEquals(Usage(10682, 510), anthropic().completeStreaming("s", user, emptyList()) {}.usage)
        assertEquals(Usage(110, 5, 100), anthropic().completeStreaming("s", user, emptyList()) {}.usage)
        assertEquals(Usage(125, 7, 100), anthropic().completeStreaming("s", user, emptyList()) {}.usage)
        assertEquals(Usage(7, 2), anthropic().completeStreaming("s", user, emptyList()) {}.usage)
    }

    @Test fun geminiStreamsTextAndKeepsExplicitCallId() = runTest {
        server.enqueue(stream(
            """{"candidates":[{"content":{"parts":[{"text":"你"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"好"},{"functionCall":{"id":"call9","name":"phone_tap","args":{}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}""",
        ))
        val updates = mutableListOf<String>()
        val model = GeminiModel(server.url("/").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val reply = model.completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf("你", "你好"), updates)
        assertEquals(listOf(Part.Text("你好"), Part.ToolCall("call9", "phone_tap", "{}")), reply.parts)
        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals("/v1beta/models/m:streamGenerateContent?alt=sse", server.takeRequest().path)
    }

    private fun openAi() = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
    private fun anthropic() = AnthropicModel(server.url("/").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
    private fun gemini() = GeminiModel(server.url("/").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())

    /** 終了前に切断されたストリームは応答とみなされませんが、再試行できる一時的な失敗と見なされます (インターフェイスの構成とは関係ありません)。 */
    private suspend fun assertCut(model: StreamingChatModel) {
        try {
            model.completeStreaming("s", user, emptyList()) {}
            throw AssertionError("不完全な応答は拒否する必要があります")
        } catch (e: ModelError.StreamCut) {
            assertTrue(RetryPolicy.transient(e))
        }
    }

    @Test fun openAiStreamCutAfterOneChunkIsRetryable() = runTest {
        server.enqueue(stream("""{"choices":[{"delta":{"content":"半句"}}]}"""))
        assertCut(openAi())
    }

    @Test fun anthropicStreamCutAfterOneChunkIsRetryable() = runTest {
        server.enqueue(stream(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半句"}}""",
        ))
        assertCut(anthropic())
    }

    @Test fun geminiStreamCutAfterOneChunkIsRetryable() = runTest {
        server.enqueue(stream("""{"candidates":[{"content":{"parts":[{"text":"半句"}]}}]}"""))
        assertCut(gemini())
    }

    /** 空の data 行はキープアライブです。実イベントと [DONE] の間に挟まっても応答を壊しません。 */
    @Test fun openAiIgnoresBlankSseDataLines() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: \n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n" +
                "data:\n\n" +
                "data: [DONE]\n\n",
        ))
        val reply = openAi().completeStreaming("s", user, emptyList()) {}
        assertEquals(listOf(Part.Text("ok")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
    }

    /** 空の data 行だけではイベントがなく、途中切断と同じ再試行可能な失敗です。 */
    @Test fun blankSseDataLinesAloneAreARetryableCut() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: \n\ndata:\n\n"))
        assertCut(openAi())
    }

    /** イベント ストリームは 1 フレームも来る前に中断されました (たとえば、長時間考えているときにゲートウェイによって中断されました)。これは再試行可能な中断でもあります。 */
    @Test fun eventStreamCutBeforeAnyEventIsRetryable() = runTest {
        server.enqueue(stream())
        assertCut(openAi())
    }

    /** は正常に応答しましたが、それはイベント ストリームでもイベントでもありませんでした (アドレスは Web ページを指していました)。これは構成の問題であり、再試行する必要はありませんでした。 */
    @Test fun nonStreamBodyWithoutEventsIsBadResponse() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>hi</html>"))
        try {
            openAi().completeStreaming("s", user, emptyList()) {}
            throw AssertionError("ストリーミングではない内容は拒否する必要があります")
        } catch (e: ModelError.BadResponse) {
            assertTrue(!RetryPolicy.transient(e))
        }
    }

    /** 終了理由を受信して​​も完了です。一部のエンドポイントは最終終了マークを送信しません。 */
    @Test fun openAiFinishReasonWithoutDoneIsComplete() = runTest {
        server.enqueue(stream("""{"choices":[{"delta":{"content":"好了"},"finish_reason":"stop"}]}"""))
        val reply = openAi().completeStreaming("s", user, emptyList()) {}
        assertEquals(listOf(Part.Text("好了")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
    }

    @Test fun anthropicStopReasonWithoutMessageStopIsComplete() = runTest {
        server.enqueue(stream(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好了"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
        ))
        val reply = anthropic().completeStreaming("s", user, emptyList()) {}
        assertEquals(listOf(Part.Text("好了")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
    }

    @Test fun geminiFinishReasonIsComplete() = runTest {
        server.enqueue(stream("""{"candidates":[{"content":{"parts":[{"text":"好了"}]},"finishReason":"STOP"}]}"""))
        val reply = gemini().completeStreaming("s", user, emptyList()) {}
        assertEquals(listOf(Part.Text("好了")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
    }

    @Test fun openAiCompatibleEndpointCanStreamWithoutUsageOption() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("unknown field stream_options"))
        server.enqueue(stream(
            """{"choices":[{"delta":{"content":"继续"},"finish_reason":"stop"}]}""",
            "[DONE]",
        ))
        val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val updates = mutableListOf<String>()
        assertEquals(listOf(Part.Text("继续")), model.completeStreaming("s", user, emptyList(), updates::add).parts)
        assertEquals(listOf("继续"), updates)
        assertTrue(server.takeRequest().body.readUtf8().contains("stream_options"))
        val retried = server.takeRequest().body.readUtf8()
        assertTrue(retried.contains("\"stream\":true"))
        assertTrue(!retried.contains("stream_options"))
    }
}
