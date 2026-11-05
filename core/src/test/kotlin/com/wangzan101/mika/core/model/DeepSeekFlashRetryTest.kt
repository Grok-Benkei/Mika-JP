package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.agent.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
