package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiteLooksTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L

    private fun store() = SiteLooks(tmp.root, clock = { now })

    @Test
    fun fallbackNameDropsWwwSubdomainsAndSuffix() {
        assertEquals("example", SiteNames.fallback("www.example.com"))
        assertEquals("example", SiteNames.fallback("shop.example.com"))
        assertEquals("example", SiteNames.fallback("mail.example.com.cn"))
        assertEquals("localhost", SiteNames.fallback("localhost"))
        assertEquals("192.168.1.1", SiteNames.fallback("192.168.1.1"))
    }

    @Test
    fun initialIsFirstCharacterUppercased() {
        assertEquals("E", SiteNames.initial("example"))
        assertEquals("购", SiteNames.initial("购物"))
        assertEquals("?", SiteNames.initial(" "))
    }

    @Test
    fun tileColorIsStablePerHostAndFromThePalette() {
        val a = SiteTiles.color("shop.example.com")
        assertEquals(a, SiteTiles.color("shop.example.com"))
        assertEquals(SiteTiles.color("example.com"), SiteTiles.color("www.example.com"))
        assertTrue(a in SiteTiles.PALETTE)
        // 通常、異なるホスト名は異なる色で分散されます。
        val colors = (1..40).map { SiteTiles.color("site$it.example.com") }.toSet()
        assertTrue(colors.size >= 6)
    }

    @Test
    fun pickPrefersDeclaredNameThenTitlePart() {
        assertEquals("购物" to SiteNames.RANK_DECLARED, SiteNames.pick("购物", "随便什么 - 别的", false))
        assertEquals("购物" to SiteNames.RANK_HOME_TITLE, SiteNames.pick(null, "购物 - 好东西都在这里", true))
        assertEquals("购物" to SiteNames.RANK_DECLARED, SiteNames.pick("购物", "登录 - 购物", false))
        assertEquals("12306-铁路" to SiteNames.RANK_HOME_TITLE, SiteNames.pick(null, "12306-铁路", true))
        assertNull(SiteNames.pick(null, "  ", true))
        assertEquals(SiteNames.MAX_LEN, SiteNames.pick("一二三四五六七八九十一二三四五", null, true)!!.first.length)
    }

    @Test
    fun otherPagesTitlesDoNotNameTheSite() {
        // ログインや記事などのページタイトルのどの段落がウェブサイト名であるか不明です: Unnamed
        assertNull(SiteNames.pick(null, "示例 - 统一登录", false))
        assertNull(SiteNames.pick(" ", "清新薄荷牙膏 | 购物", false))
        // ホームページ上の同じタイトルの最初の段落を取得します
        assertEquals("示例" to SiteNames.RANK_HOME_TITLE, SiteNames.pick(null, "示例 - 统一登录", true))
    }

    @Test
    fun namesFromOtherPagesTitlesSavedEarlierAreDropped() {
        tmp.root.resolve("browser_sites.json").writeText(
            """{"example.com":{"name":"统一登录","rank":1,"iconAt":5},"shop.example.com":{"name":"购物","rank":2}}""",
        )
        val s = store()
        assertEquals(SiteLook(null, 0, 5), s.entries.value["example.com"])
        assertEquals("example", s.face("m.example.com").name)
        assertEquals("购物", s.face("shop.example.com").name)
        // ホームページの名前が追加されます。
        s.named("example.com", "示例", SiteNames.RANK_HOME_TITLE)
        assertEquals("示例", s.face("m.example.com").name)
    }

    @Test
    fun homePageIsRootPath() {
        assertTrue(SiteNames.isHomePage("https://example.com"))
        assertTrue(SiteNames.isHomePage("https://example.com/?from=x"))
        assertEquals(false, SiteNames.isHomePage("https://example.com/a"))
    }

    @Test
    fun parseReadsTheScriptResult() {
        assertEquals("购物" to "首页", SiteNames.parse("\"{\\\"site\\\":\\\"购物\\\",\\\"title\\\":\\\"首页\\\"}\""))
        assertEquals(null to "首页", SiteNames.parse("\"{\\\"site\\\":\\\"\\\",\\\"title\\\":\\\"首页\\\"}\""))
        assertNull(SiteNames.parse("null"))
        assertNull(SiteNames.parse(null))
    }

    @Test
    fun namesKeepTheMostReliableAndReachTheRegistrableDomain() {
        val s = store()
        s.named("shop.example.com", "购物频道", SiteNames.RANK_HOME_TITLE)
        assertEquals("购物频道", s.face("shop.example.com").name)
        assertEquals("购物频道", s.face("m.example.com").name)
        s.named("example.com", "购物", SiteNames.RANK_DECLARED)
        // 他のサブドメインのホームページのタイトルを取得したくない場合は、より信頼性の高い名前をドメイン名に登録できます。
        s.named("m.example.com", "移动版", SiteNames.RANK_HOME_TITLE)
        assertEquals("购物", s.face("example.com").name)
        assertEquals("移动版", s.face("m.example.com").name)
        // ホスト名自体: 元のホスト名と変わらない場合は変更します。
        s.named("shop.example.com", "购物商城", SiteNames.RANK_DECLARED)
        s.named("shop.example.com", "首页标题", SiteNames.RANK_HOME_TITLE)
        assertEquals("购物商城", s.face("shop.example.com").name)
        // が保存されました
        assertEquals(s.entries.value, store().entries.value)
    }

    @Test
    fun faceFallsBackWhenNothingKnown() {
        val f = store().face("news.example.org")
        assertEquals("example", f.name)
        assertEquals("E", f.initial)
        assertEquals(SiteTiles.color("news.example.org"), f.color)
        assertNull(f.icon)
    }

    @Test
    fun iconsGoToHostAndToRegistrableOnlyWhenMissing() {
        val s = store()
        assertEquals(listOf("m.example.com", "example.com"), s.iconTargets("m.example.com"))
        s.iconFile("m.example.com").apply { parentFile?.mkdirs(); writeText("x") }
        s.iconSaved("m.example.com")
        s.iconSaved("example.com")
        assertEquals(listOf("shop.example.com"), s.iconTargets("shop.example.com"))
        // 今保存したものは再度保存されません。しばらくしてから再度保存してください。 独自のアイコンがない
        assertEquals(emptyList<String>(), s.iconTargets("example.com"))
        assertEquals(emptyList<String>(), s.iconTargets("m.example.com"))
        // サブドメインは、登録可能なドメイン名を使用します。
        val f = s.face("shop.example.com")
        assertEquals(s.iconFile("example.com"), f.icon)
        assertEquals(1_000L, f.iconAtMs)
        now = 1_000L + SiteLooks.ICON_REFRESH_MS
        assertEquals(listOf("m.example.com"), s.iconTargets("m.example.com"))
        s.iconSaved("shop.example.com")
        assertEquals(s.iconFile("shop.example.com"), s.face("shop.example.com").icon)
        assertNotEquals(f.iconAtMs, s.face("shop.example.com").iconAtMs)
    }

    @Test
    fun corruptFileStartsEmpty() {
        tmp.root.resolve("browser_sites.json").writeText("{not json")
        assertEquals(emptyMap<String, SiteLook>(), store().entries.value)
    }
}
