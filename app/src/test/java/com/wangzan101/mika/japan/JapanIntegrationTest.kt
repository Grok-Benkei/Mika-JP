package com.wangzan101.mika.japan

import com.wangzan101.mika.cap.web.LoginProbe
import com.wangzan101.mika.cap.web.LoginSignals
import com.wangzan101.mika.cap.web.LoginState
import com.wangzan101.mika.settings.ProviderPresets
import org.junit.Assert.*
import org.junit.Test

class JapanIntegrationTest {
    @Test fun chineseServicesAreOptionalIncludingTheirOverseasEndpoints() {
        assertEquals("OpenAI", ProviderPresets.recommended.first().label)
        mapOf("Qwen Global" to "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "Kimi Global" to "https://api.moonshot.ai/v1", "MiniMax Global" to "https://api.minimax.io/v1").forEach { (label, url) ->
            assertEquals(label, url, ProviderPresets.otherRegions.single { it.label == label }.baseUrl)
            assertEquals(label, ProviderPresets.forBaseUrl(url)?.label)
        }
        val chinese = setOf("DeepSeek", "Qwen Global", "Kimi Global", "MiniMax Global", "阶跃星辰", "通义千问", "Kimi", "智谱", "智谱 Coding Plan", "硅基流动", "火山方舟", "MiniMax", "腾讯混元", "百度千帆")
        assertTrue(ProviderPresets.recommended.none { it.label in chinese })
        assertEquals(chinese, ProviderPresets.otherRegions.map { it.label }.toSet())
        assertTrue(ProviderPresets.otherRegions.any { it.label == "Kimi" && it.baseUrl == "https://api.moonshot.cn/v1" })
        assertTrue(ProviderPresets.recommended.intersect(ProviderPresets.otherRegions.toSet()).isEmpty())
        assertEquals(ProviderPresets.all.toSet(), (ProviderPresets.recommended + ProviderPresets.otherRegions).toSet())
    }

    @Test fun japaneseAccountSignalsDoNotTreatHelpAsARealLogin() {
        assertEquals(LoginState.SignedIn, LoginProbe.classify(LoginSignals("https://example.jp/", false, listOf("ログアウト"), 0)))
        assertEquals(LoginState.SignedIn, LoginProbe.classify(LoginSignals("https://example.jp/", false, listOf("マイページ"), 0)))
        assertEquals(LoginState.SignedOut, LoginProbe.classify(LoginSignals("https://example.jp/", false, listOf("ログイン"), 0)))
        assertEquals(LoginState.Unknown, LoginProbe.classify(LoginSignals("https://example.jp/help", false, listOf("ログアウトの方法"), 0)))
    }
}
