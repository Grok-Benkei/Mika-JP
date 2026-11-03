package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.ChineseUi
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.web.SiteEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 設定ページ「Web サイト ログイン」: リスト、単一サイト終了 (「まだ存在する可能性があります」プロンプト付き)、すべての終了には 2 回目の確認が必要です。 */
@RunWith(AndroidJUnit4::class)
class WebSitesSectionTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(open: ((String) -> Unit)? = { calls += "open $it" }) {
        compose.setContent {
            Column {
                WebSitesSection(
                    WebSitesState(
                        sites = listOf(SiteEntry("shop.example.com", 1_000, true), SiteEntry("news.example.org", 2_000, false)),
                        onOpenLogin = open,
                        onLogout = { calls += "logout $it"; 2 },
                        onLogoutAll = { calls += "all" },
                    ),
                )
            }
        }
    }

    @Test
    fun listsSitesAndDataNote() {
        show()
        compose.onNodeWithText("shop.example.com").assertIsDisplayed()
        compose.onNodeWithText("news.example.org").assertIsDisplayed()
        compose.onNodeWithText(WEB_DATA_NOTE).assertIsDisplayed()
    }

    @Test
    fun logoutOneSiteWarnsItMayPersist() {
        show()
        compose.awaitOne("shop.example.com").performClick()
        compose.clickInDialog(appString(R.string.web_sign_out_site))
        assertEquals(listOf("logout shop.example.com"), calls)
        compose.onNodeWithText(appString(R.string.web_signed_out, "shop.example.com", WEB_LOGOUT_PARTIAL)).assertIsDisplayed()
    }

    @Test
    fun openLoginFromTheSiteDialog() {
        show()
        compose.awaitOne("news.example.org").performClick()
        compose.clickInDialog(appString(R.string.web_open_login))
        assertEquals(listOf("open news.example.org"), calls)
    }

    @Test
    fun logoutAllNeedsConfirmation() {
        show()
        compose.awaitOne(appString(R.string.web_logout_all_title)).performClick()
        assertEquals(emptyList<String>(), calls)
        compose.clickInDialog(appString(R.string.web_logout_all))
        assertEquals(listOf("all"), calls)
    }
}
