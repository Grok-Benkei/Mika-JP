package com.wangzan101.mika.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserIdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun userBrowsingDefaultsToMobile() {
        assertTrue(BrowserIdentity.mobile(loginForMika = false, agentMobile = false, userDesktop = false))
        // エージェント 通常のブラウジングに影響を与えずに使用できるバージョンはどれですか?
        assertTrue(BrowserIdentity.mobile(loginForMika = false, agentMobile = true, userDesktop = false))
    }

    @Test
    fun perSiteOverrideSwitchesToDesktop() {
        assertFalse(BrowserIdentity.mobile(loginForMika = false, agentMobile = true, userDesktop = true))
    }

    @Test
    fun loginForMikaUsesTheAgentIdentity() {
        assertFalse(BrowserIdentity.mobile(loginForMika = true, agentMobile = false, userDesktop = false))
        assertTrue(BrowserIdentity.mobile(loginForMika = true, agentMobile = true, userDesktop = true))
    }

    @Test
    fun onlyNotLoggedInFavoritesGoThroughLogin() {
        assertTrue(BrowserIdentity.loginFlow(FavoriteSite("example.com", loggedIn = false)))
        assertFalse(BrowserIdentity.loginFlow(FavoriteSite("example.com", loggedIn = true)))
    }

    @Test
    fun userSiteModesFollowParentDomainAndPersist() {
        val m = UserSiteModes(tmp.root)
        assertFalse(m.isDesktop("example.com"))
        m.set("www.example.com", true)
        assertTrue(m.isDesktop("example.com"))
        assertTrue(m.isDesktop("m.example.com"))
        assertFalse(m.isDesktop("example.org"))
        assertTrue(UserSiteModes(tmp.root).isDesktop("shop.example.com"))
        // サブドメイン名をモバイル バージョンに戻します。上位レコードも削除します。
        m.set("m.example.com", false)
        assertFalse(m.isDesktop("example.com"))
        assertFalse(UserSiteModes(tmp.root).isDesktop("m.example.com"))
    }
}
