package com.wangzan101.mika.cap.web.cdp

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLanguageTest {
    private val engine = listOf(Brand("Chromium", "152", "152.0.7977.64"), Brand("Android WebView", "152", "152.0.7977.64"))
    private val mobileUa = "Mozilla/5.0 (Linux; Android 14; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/152.0.7977.64 Mobile Safari/537.36"
    private val mobileMeta = DesktopProfile.metadata(engine, "152.0.7977.64", "Android", "14.0.0", "", "K", mobile = true, bitness = "", wow64 = false)

    @Test fun headerComesFromTheTag() {
        assertEquals("en-US,en", PageLanguage.acceptLanguage("en-Latn-US"))
        // フォント システムが書き込めない
        assertEquals("sr-RS,sr", PageLanguage.acceptLanguage("sr-Latn-RS"))
        assertEquals("pt-BR,pt", PageLanguage.acceptLanguage("pt-BR"))
        assertEquals("id-ID,id", PageLanguage.acceptLanguage("in-ID"))
        // 地域なし、言語のみ
        assertEquals("fr", PageLanguage.acceptLanguage("fr"))
    }

    @Test fun desktopOverrideGetsTheLanguageOnlyWhenChosen() {
        val o = DesktopProfile.userAgentOverride("152", "152.0.7977.64", DesktopProfile.brands(engine, "152", "152.0.7977.64"))
        assertSame(o, PageLanguage.withLanguage(o, null))
        val chosen = PageLanguage.withLanguage(o, "en-US,en")
        assertEquals("en-US,en", chosen["acceptLanguage"]!!.jsonPrimitive.content)
        // 残りのアイデンティティは変更されません。
        assertEquals(o, chosen - "acceptLanguage")
    }

    @Test fun mobileFollowingTheSystemClearsTheOverride() {
        val o = PageLanguage.mobileOverride(mobileUa, mobileMeta, null)
        assertEquals("", o["userAgent"]!!.jsonPrimitive.content)
        assertEquals(setOf("userAgent"), o.keys)
        // デフォルトの UA を入手できない場合でも、ビルドしないでください。
        assertEquals("", PageLanguage.mobileOverride("", mobileMeta, "en-US,en")["userAgent"]!!.jsonPrimitive.content)
    }

    @Test fun mobileWithAChosenLanguageKeepsTheDefaultIdentity() {
        val o = PageLanguage.mobileOverride(mobileUa, mobileMeta, "en-US,en")
        assertEquals(mobileUa, o["userAgent"]!!.jsonPrimitive.content)
        assertEquals("en-US,en", o["acceptLanguage"]!!.jsonPrimitive.content)
        assertFalse("platform" in o)
        val meta = o["userAgentMetadata"]!!.jsonObject
        assertTrue(meta["mobile"]!!.jsonPrimitive.boolean)
        assertEquals("Android", meta["platform"]!!.jsonPrimitive.content)
        assertEquals("K", meta["model"]!!.jsonPrimitive.content)
        // モバイル バージョンのブランドはカーネルによって与えられ、Android WebView は保持されます。
        assertEquals(listOf("Chromium", "Android WebView"), meta["brands"]!!.jsonArray.map { it.jsonObject["brand"]!!.jsonPrimitive.content })
        // は、デフォルトの UA-CH を取得できない場合に UA と言語のみを受け取ります
        assertEquals(setOf("userAgent", "acceptLanguage"), PageLanguage.mobileOverride(mobileUa, null, "fr-FR,fr").keys)
    }
}
