package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 内蔵ブラウザでネットワーク プロキシを使用する方法: アカウントとパスワードを持つ SOCKS は使用できません。現在の HTTP プロキシのアカウントとパスワードのみを提供します。 */
class WebProxyTest {
    private val saved = NetProxy.sharedSource

    @After fun restore() {
        NetProxy.sharedSource = saved
        NetProxy.shared.changed()
    }

    @Test fun modeFollowsSettingAndWebViewSupport() {
        assertEquals(WebProxy.Mode.Off, WebProxy.mode(null, true))
        val http = ProxyAddress.parse("http://u:p@10.0.0.2:8080")
        assertEquals(WebProxy.Mode.Applied, WebProxy.mode(http, true))
        assertEquals(WebProxy.Mode.Unsupported, WebProxy.mode(http, false))
        assertEquals(WebProxy.Mode.Applied, WebProxy.mode(ProxyAddress.parse("socks5://10.0.0.2:1080"), true))
        assertEquals(WebProxy.Mode.SocksWithCredentials, WebProxy.mode(ProxyAddress.parse("socks5://u:p@10.0.0.2:1080"), true))
    }

    @Test fun rulesNeverCarryCredentials() {
        assertEquals(listOf("http://10.0.0.2:8080"), WebProxy.rules(ProxyAddress.parse("http://u:p@10.0.0.2:8080")!!))
        assertEquals(listOf("socks5://[::1]:1080", "socks://[::1]:1080"), WebProxy.rules(ProxyAddress.parse("socks5h://[::1]:1080")!!))
    }

    @Test fun wrongCredentialsStopAfterThreeAnswersPerPage() {
        NetProxy.sharedSource = { "http://alice:wrong@proxy.example.test:3128" }
        val auth = WebProxyAuth()
        repeat(3) { assertEquals("alice" to "wrong", auth.credentialsFor("proxy.example.test")) }
        repeat(3) { assertNull(auth.credentialsFor("proxy.example.test")) }
    }

    @Test fun nextNavigationRetriesWithCorrectedCredentialsInTheSameTab() {
        NetProxy.sharedSource = { "http://alice:wrong@proxy.example.test:3128" }
        val auth = WebProxyAuth()
        repeat(3) { auth.credentialsFor("proxy.example.test") }
        assertNull(auth.credentialsFor("proxy.example.test"))

        NetProxy.sharedSource = { "http://alice:correct@proxy.example.test:3128" }
        auth.onPageStarted()
        repeat(3) { assertEquals("alice" to "correct", auth.credentialsFor("proxy.example.test")) }
        assertNull(auth.credentialsFor("proxy.example.test"))

        // 即使设置没改,下一页也能在代理会话失效后再次认证。
        auth.onPageStarted()
        assertEquals("alice" to "correct", auth.credentialsFor("proxy.example.test"))
    }

    @Test fun unrelatedAuthChallengesDoNotConsumeProxyRetries() {
        NetProxy.sharedSource = { "http://alice:pw@proxy.example.test:3128" }
        val auth = WebProxyAuth()
        repeat(4) {
            assertNull(auth.credentialsFor("site.example.test"))
            assertNull(auth.credentialsFor(null))
        }
        repeat(3) { assertEquals("alice" to "pw", auth.credentialsFor("proxy.example.test")) }
        assertNull(auth.credentialsFor("proxy.example.test"))
    }

    @Test fun eachTabHasItsOwnRetryBudget() {
        NetProxy.sharedSource = { "http://alice:pw@proxy.example.test:3128" }
        val first = WebProxyAuth()
        val second = WebProxyAuth()
        repeat(3) { first.credentialsFor("proxy.example.test") }
        assertNull(first.credentialsFor("proxy.example.test"))
        assertEquals("alice" to "pw", second.credentialsFor("proxy.example.test"))
        second.onPageStarted()
        assertNull(first.credentialsFor("proxy.example.test"))
    }

    @Test fun answersOnlyTheConfiguredHttpProxy() {
        NetProxy.sharedSource = { "http://alice:pw@proxy.example.test:3128" }
        NetProxy.shared.changed()
        assertEquals("alice" to "pw", WebProxy.credentialsFor("proxy.example.test"))
        assertNull("サイト自身のログインダイアログには認証情報を返さない", WebProxy.credentialsFor("site.example.test"))
        NetProxy.sharedSource = { "socks5://alice:pw@proxy.example.test:1080" }
        assertNull(WebProxy.credentialsFor("proxy.example.test"))
        NetProxy.sharedSource = { "" }
        assertNull(WebProxy.credentialsFor("proxy.example.test"))
    }
}
