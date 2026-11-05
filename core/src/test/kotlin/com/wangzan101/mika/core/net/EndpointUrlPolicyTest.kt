package com.wangzan101.mika.core.net

import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.security.EgressPolicy
import com.wangzan101.mika.core.security.EgressScene
import com.wangzan101.mika.core.security.EgressWindow
import com.wangzan101.mika.core.security.SensitiveApps
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointUrlPolicyTest {
    @After fun lanOff() = CleartextPolicy.setAllowLanHttp(false)

    @Test fun httpsCloudIsAllowed() {
        assertTrue(EndpointUrlPolicy.checkModel("https://api.deepseek.com/v1", false).allowed)
        assertTrue(EndpointUrlPolicy.checkModel("https://api.openai.com/v1", false).allowed)
    }

    @Test fun httpCloudIsRejected() {
        assertEquals("http_public", EndpointUrlPolicy.checkModel("http://api.deepseek.com/v1", true).reason)
        assertEquals("http_public", EndpointUrlPolicy.checkModel("http://203.0.113.7:8000/v1", true).reason)
    }

    @Test fun loopbackHttpIsAllowedWithoutLanSetting() {
        CleartextPolicy.setAllowLanHttp(false)
        for (url in listOf(
            "http://127.0.0.1:11434/v1",
            "http://localhost:1234/v1",
            "http://[::1]:8080/v1",
            "http://127.8.8.8:9/v1",
        )) assertEquals(url, "ok", EndpointUrlPolicy.checkModel(url, false).reason)
    }

    @Test fun lanHttpRequiresExplicitSetting() {
        val url = "http://192.168.1.10:11434/v1"
        assertEquals("http_lan", EndpointUrlPolicy.checkModel(url, false).reason)
        assertEquals("http_lan", EndpointUrlPolicy.checkModel("http://nas.local:11434/v1", false).reason)
        assertEquals("http_lan", EndpointUrlPolicy.checkModel("http://10.0.0.5:1234/v1", false).reason)
        assertTrue(EndpointUrlPolicy.checkModel(url, true).allowed)
        assertTrue(EndpointUrlPolicy.checkModel("http://printer.lan/v1", true).allowed)
    }

    @Test fun badUrlsAreRejected() {
        for (url in listOf("", "   ", "example.com", "ftp://example.com/v1", "javascript:alert(1)", "https://user:pass@api.example/v1", "http://")) {
            assertFalse(url, EndpointUrlPolicy.checkModel(url, true).allowed)
        }
    }

    @Test fun httpDowngradeOnRedirectIsRejected() {
        val verdict = EndpointUrlPolicy.checkRedirect(
            "https://api.deepseek.com/v1/chat/completions",
            "http://api.deepseek.com/v1/chat/completions",
            allowLanCleartext = true,
        )
        assertEquals("http_downgrade", verdict.reason)
        assertEquals(
            "http_downgrade",
            EndpointUrlPolicy.checkRedirect("https://api.example/v1", "http://127.0.0.1/v1", true).reason,
        )
        assertTrue(EndpointUrlPolicy.checkRedirect("https://a.example/v1", "https://b.example/v1", false).allowed)
    }

    @Test fun webSocketSchemes() {
        assertTrue(EndpointUrlPolicy.checkWebSocket("wss://relay.example/device", false).allowed)
        assertTrue(EndpointUrlPolicy.checkWebSocket("https://relay.example/device", false).allowed)
        assertEquals("http_public", EndpointUrlPolicy.checkWebSocket("ws://relay.example/device", true).reason)
        assertEquals("ok", EndpointUrlPolicy.checkWebSocket("ws://127.0.0.1:9/device", false).reason)
        assertEquals("http_lan", EndpointUrlPolicy.checkWebSocket("ws://192.168.1.20:9/device", false).reason)
        assertTrue(EndpointUrlPolicy.checkWebSocket("ws://192.168.1.20:9/device", true).allowed)
        assertEquals("http_loopback", EndpointUrlPolicy.checkWebSocket("ws://localhost/device", false, allowLoopbackInsecure = false).reason)
    }

    @Test fun localServerGetsLoopbackScreenTextAndDropsHttpCloudRedirect() {
        MockWebServer().use { local ->
            local.start()
            val screen = Untrusted.wrap("dummy screen\nsk-test-not-a-real-key")
            local.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(ModelUrlInterceptor())
                .build()
            client.newCall(
                Request.Builder().url(local.url("/chat/completions"))
                    .post(screen.toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
            }
            val sent = local.takeRequest()
            val body = sent.body.readUtf8()
            assertTrue(body.contains("<untrusted>"))
            assertTrue(body.contains("sk-test-not-a-real-key"))
            assertFalse(sent.path!!.contains("sk-"))
            local.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://203.0.113.9/upload"))
            try {
                client.newCall(Request.Builder().url(local.url("/v1/chat/completions")).build()).execute().close()
                throw AssertionError("http cloud redirect was followed")
            } catch (e: Exception) {
                assertTrue(e.toString(), e.toString().contains("http_public") || e.toString().contains("http_downgrade"))
            }
            assertEquals(2, local.requestCount)
        }
    }

    @Test fun sensitivePackagesAndUnknownPackagesWithholdSends() {
        assertTrue(SensitiveApps.blocks("com.google.android.apps.authenticator2"))
        assertTrue(SensitiveApps.blocks("com.onepassword.android"))
        assertTrue(SensitiveApps.blocks("com.x8bit.bitwarden"))
        assertTrue(SensitiveApps.withholdSend(null))
        assertTrue(SensitiveApps.withholdSend(""))
        assertTrue(com.wangzan101.mika.core.security.EgressPolicy.isBrowser("com.android.chrome"))
        assertFalse(EgressPolicy.assess(com.wangzan101.mika.core.security.EgressScene(com.wangzan101.mika.core.security.EgressWindow(webUrl = "https://shop.example", isWeb = true))).allowScreenshot)
        assertTrue(SensitiveApps.withholdScreenshot(null, isWeb = false))
    }
}
