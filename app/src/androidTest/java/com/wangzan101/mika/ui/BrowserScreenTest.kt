package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.ChineseUi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.browser.AddressInput
import com.wangzan101.mika.browser.BrowserPage
import com.wangzan101.mika.browser.FavoriteSite
import com.wangzan101.mika.browser.RecentPage
import com.wangzan101.mika.browser.SiteLook
import com.wangzan101.mika.browser.SiteLooks
import com.wangzan101.mika.browser.StartPageModel
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** サイドバーの「ブラウザ」入口。スタート ページのいくつかのセクション (検索、頻繁に使用する Web サイト、組織、最近開いたもの)、Web ページのトップ バーとツールバー、[詳細] パネル、および [Mika へのログイン] バナー。 */
@RunWith(AndroidJUnit4::class)
class BrowserScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val looks = mapOf("shop.example.com" to SiteLook("购物", 3, null), "example.org" to SiteLook("新闻", 3, null))
    private val face = { host: String -> SiteLooks.faceOf(host, looks) { null } }
    private val sites = listOf(FavoriteSite("shop.example.com", true), FavoriteSite("news.example.org", false))
    private val recent = StartPageModel.recentRows(
        listOf(
            RecentPage(AddressInput.search("周末去哪玩"), "", 3_000),
            RecentPage("https://docs.example.net/guide", "使用指南", 2_000),
        ),
    ) { face(it).name }

    private fun start() {
        compose.setContent {
            MikaTheme {
                BrowserStart(
                    sites, recent, face,
                    onBack = { calls += "back" },
                    onSubmit = { calls += "submit $it" },
                    onOpenUrl = { calls += "open $it" },
                    onOpenSite = { calls += "site ${it.host}" },
                    onRemoveSite = { calls += "remove ${it.host}" },
                    onAddHint = { calls += "add" },
                    onClearRecent = { calls += "clear" },
                    now = 3_000,
                )
            }
        }
    }

    @Test
    fun drawerHasBrowserEntryThatOpensIt() {
        compose.setContent {
            MikaTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {},
                    onBrowser = { calls += "browser" },
                    onSettings = {}, onOpen = {},
                )
            }
        }
        compose.onNodeWithText(appString(R.string.drawer_browser)).assertIsDisplayed().performClick()
        assertEquals(listOf("browser"), calls)
    }

    @Test
    fun startPageShowsSearchSitesAndRecent() {
        start()
        compose.onNodeWithText(BROWSER_SEARCH_HINT).assertIsDisplayed()
        compose.onNodeWithText(BROWSER_ENGINE_NOTE).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.browser_favorites)).assertIsDisplayed()
        compose.onNodeWithText(BROWSER_LOGIN_LEGEND).assertIsDisplayed()
        compose.onAllNodesWithTag("browser-site-logged-in", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText(appString(R.string.browser_recent_source)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.browser_search_colon, "周末去哪玩")).assertIsDisplayed()
        compose.onNodeWithText("使用指南").performClick()
        compose.onNodeWithText(appString(R.string.clear)).performClick()
        compose.onNodeWithText(appString(R.string.add)).performClick()
        assertEquals(listOf("open https://docs.example.net/guide", "clear", "add"), calls)
    }

    @Test
    fun typingShowsSuggestions() {
        start()
        compose.onNodeWithTag("browser-search").performTextInput("周末")
        compose.onNodeWithTag("browser-suggestions").assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.browser_search_q, "周末")).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.browser_search_colon, "周末去哪玩")).performClick()
        assertEquals(listOf("open ${AddressInput.search("周末去哪玩")}"), calls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardGoAndEnterSubmitTheSearch() {
        start()
        compose.onNodeWithTag("browser-search").performTextInput("weather")
        compose.onNodeWithTag("browser-search").performImeAction()
        compose.onNodeWithTag("browser-search").performTextInput("天气")
        compose.onNodeWithTag("browser-search").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("submit weather", "submit 天气"), calls)
    }

    @Test
    fun editModeRemovesAndFinishes() {
        start()
        compose.onNodeWithText(appString(R.string.browser_edit)).performClick()
        compose.onNodeWithText(appString(R.string.browser_edit_favorites)).assertIsDisplayed()
        compose.onNodeWithContentDescription(appString(R.string.browser_remove_site, "购物"), useUnmergedTree = true).performClick()
        compose.onNodeWithText(appString(R.string.action_hand_back)).performClick()
        compose.onNodeWithText(appString(R.string.drawer_browser)).assertIsDisplayed()
        assertEquals("remove shop.example.com", calls.first())
    }

    @Test
    fun longPressEntersEditMode() {
        start()
        compose.onAllNodesWithTag("browser-sites")[0].performTouchInput { longClick(center.copy(x = width / 8f, y = 40f)) }
        compose.onNodeWithText(appString(R.string.browser_edit_favorites)).assertIsDisplayed()
    }

    @Test
    fun pageBarAndToolbar() {
        val page = BrowserPage("https://shop.example.com/", "购物", 40, loading = true, canBack = false, canForward = false, desktop = true)
        compose.setContent {
            MikaTheme {
                Column {
                    BrowserPageBar(page, face("shop.example.com"), onClose = { calls += "close" }, onSubmit = {}, onReload = {}, onStop = { calls += "stop" })
                    BrowserToolbar(page, onBackPage = {}, onForward = {}, onHome = { calls += "home" }, onShare = { calls += "share" }, onMore = { calls += "more" })
                }
            }
        }
        compose.onNodeWithText(BROWSER_DESKTOP_TAG).assertIsDisplayed()
        compose.onNodeWithTag("browser-progress").assertIsDisplayed()
        compose.onNodeWithContentDescription(appString(R.string.back)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(appString(R.string.chat_stop)).performClick()
        compose.onNodeWithContentDescription(appString(R.string.browser_home)).performClick()
        compose.onNodeWithContentDescription(appString(R.string.share)).performClick()
        compose.onNodeWithContentDescription(appString(R.string.more)).performClick()
        compose.onNodeWithTag("browser-address-pill").performClick()
        compose.onNodeWithText(appString(R.string.cancel)).assertIsDisplayed()
        assertEquals(listOf("stop", "home", "share", "more"), calls)
    }

    @Test
    fun moreSheetOffersSiteActions() {
        compose.setContent {
            MikaTheme {
                BrowserMoreSheet(
                    face("shop.example.com"), "shop.example.com", favorite = false, desktop = false,
                    onDismiss = { calls += "dismiss" }, onReload = {}, onCopy = { calls += "copy" }, onOpenOutside = {},
                    onToggleFavorite = { calls += "fav" }, onToggleDesktop = { calls += "desktop" },
                )
            }
        }
        compose.onNodeWithText(appString(R.string.browser_copy_link)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.browser_open_outside)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.browser_favorite)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.browser_keep_desktop)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.browser_to_desktop)).performClick()
        assertEquals(listOf("dismiss", "desktop"), calls)
    }

    @Test
    fun loginBannerConfirms() {
        compose.setContent { MikaTheme { LoginBanner(desktop = true, onDone = { calls += "done" }) } }
        compose.onNodeWithTag("browser-login-banner").assertIsDisplayed()
        compose.onNodeWithText(BROWSER_LOGIN_DONE).performClick()
        assertEquals(listOf("done"), calls)
    }

    @Test
    fun siteTileFallsBackToInitial() {
        compose.setContent { MikaTheme { SiteTile(face("news.example.org"), 54.dp, RoundedCornerShape(16.dp), MikaType.Title) } }
        compose.onNodeWithText("新").assertIsDisplayed()
    }
}
