package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.agent.ModelCaller
import com.wangzan101.mika.core.agent.RetryPolicy
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Regression tests for transient DeepSeek API failures. */
class DeepSeekFlashRetryTest {
    @Test fun retryAfterSeconds() {
        assertEquals(5000L, HttpSupport.retryAfterMs("5"))
        assertEquals(1500L, HttpSupport.retryAfterMs("1.5"))
        assertNull(HttpSupport.retryAfterMs("invalid"))
    }

    @Test fun retryAfterHttpDate() {
        val whenUtc = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60).withNano(0)
        val header = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).format(whenUtc)
        val delay = HttpSupport.retryAfterMs(header)!!
        org.junit.Assert.assertTrue("$header -> $delay", delay in 55_000L..60_000L)
        assertEquals(0L, HttpSupport.retryAfterMs("Sun, 06 Nov 1994 08:49:37 GMT"))
    }

    @Test fun fiveRetriesThenStop() {
        val policy = RetryPolicy(jitter = { 1.0 })
        val expected = listOf(2000L, 4000L, 8000L, 16000L, 30000L)
        assertEquals(expected, (0..4).map { policy.delayFor(ModelError.Server(503), it) })
        assertNull(policy.delayFor(ModelError.Server(503), 5))
        assertNull(policy.delayFor(ModelError.Auth(), 0))
        assertNull(policy.delayFor(ModelError.QuotaExceeded(), 0))
    }

    @Test fun rateLimitThenEventStreamSendsExactlyTwoRequests() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0").setBody("later"))
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok-dummy\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
                ),
            )
            val model = OpenAiCompatModel(
                server.url("/v1").toString().trimEnd('/'),
                "sk-test-not-real",
                "deepseek-chat",
                Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false),
                OkHttpClient(),
            )
            val caller = ModelCaller(model, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy(jitter = { 1.0 }), {}, { 0L })
            caller.reset("system")
            val seen = mutableListOf<String>()
            val reply = caller.ask(
                listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika)),
                emptyList(),
                1,
                onText = { seen += it },
            )
            assertEquals(2, server.requestCount)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("Bearer sk-test-not-real", first.getHeader("Authorization"))
            assertEquals("text/event-stream", second.getHeader("Accept"))
            assertTrue(reply.parts.filterIsInstance<Part.Text>().any { it.text == "ok-dummy" } || seen.any { it.contains("ok-dummy") })
            assertNull(server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            server.shutdown()
        }
    }
}
