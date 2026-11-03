package com.wangzan101.mika.ui

import com.wangzan101.mika.JapaneseUi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ClipboardManager
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.about.UpdateState
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val japanese = JapaneseUi()

    @get:Rule val compose = createComposeRule()

    private val r020 = ReleaseInfo(
        "0.2.0", "Mika 0.2.0", "### 新增\n- 附件面板", "2026-11-05",
        "https://github.com/wangzan101/Mika/releases/tag/v0.2.0", "https://github.com/x/mika-0.2.0.apk",
    )

    private class Calls {
        val urls = mutableListOf<String>()
        var checks = 0
        var saves = 0
        var licenses = 0
    }

    private val sampleDiagnostics = "Mika JP 診断情報\n\n[App]\nversion: 0.1.0 (build abc1234)\n"

    private fun about(update: UpdateState = UpdateState()): Calls {
        val calls = Calls()
        compose.setContent {
            MikaTheme {
                AboutScreen(
                    versionName = "0.1.0", build = "abc1234", beta = true, update = update, issueUrl = "issue://prefilled",
                    onCheck = { calls.checks++ }, onOpenUrl = { calls.urls += it },
                    onOpenLicense = { calls.licenses++ }, onBack = {},
                    diagnostics = { sampleDiagnostics },
                )
            }
        }
        return calls
    }

    private fun scrollTo(text: String) {
        compose.awaitOne(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun showsHeroRowsNoteAndFooter() {
        about()
        compose.onNodeWithText("Mika JP").assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.TAGLINE).assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.version("0.1.0")).assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithText("· " + AboutCopy.buildLabel("abc1234")).assertIsDisplayed()
        for (t in listOf(AboutCopy.CHECK, AboutCopy.CHANGELOG, AboutCopy.GROUP, AboutCopy.FEEDBACK, AboutCopy.FOLLOW, AboutCopy.REPO, AboutCopy.GUIDE, AboutCopy.DATA_NOTE, AboutCopy.AUTHOR)) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        // ライセンスへのリンクはフッター内にまとめ、独立した行は表示しない。
        compose.onAllNodesWithText(AboutCopy.LICENSE_LINK).assertCountEquals(0)
    }

    @Test
    fun externalRowsOpenLinks() {
        val calls = about()
        for (t in listOf(AboutCopy.FOLLOW, AboutCopy.REPO, AboutCopy.GUIDE)) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf(MikaLinks.X, MikaLinks.REPO, MikaLinks.README), calls.urls)
    }

    @Test
    fun footerOpensLicense() {
        val calls = about()
        compose.onNodeWithTag("about-license").performScrollTo().performClick()
        assertEquals(1, calls.licenses)
    }

    @Test
    fun feedbackSheetHasThreeOptions() {
        val calls = about()
        compose.onNodeWithText(AboutCopy.FEEDBACK).performClick()
        compose.awaitOne(AboutCopy.FEEDBACK_SHEET_SUB)
        compose.onAllNodesWithTag("feedback-option").assertCountEquals(3)
        compose.onNodeWithText(AboutCopy.DIAGNOSTICS).assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.FEEDBACK_GROUP).assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.FEEDBACK_GITHUB).performClick()
        compose.waitForIdle()
        assertEquals(listOf("issue://prefilled"), calls.urls)
    }

    /** 診断情報は先に全文をプレビューし、コピー操作でクリップボードへ渡す。 */
    @Test
    fun feedbackDiagnosticsPreviewsThenCopies() {
        about()
        compose.onNodeWithText(AboutCopy.FEEDBACK).performClick()
        compose.awaitOne(AboutCopy.DIAGNOSTICS).performClick()
        compose.awaitOne(AboutCopy.DIAGNOSTICS_NOTE)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(sampleDiagnostics).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diagnostics-text").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-share").assertExists()
        compose.onNodeWithTag("diagnostics-copy").performClick()
        compose.waitForIdle()
        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
            clip = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(sampleDiagnostics, clip)
    }

    @Test
    fun feedbackGroupOpensGroupSheet() {
        val calls = about()
        compose.onNodeWithText(AboutCopy.FEEDBACK).performClick()
        // FEEDBACK_GROUP と GROUP は同じ文言のため、シート内の祖先で範囲を絞る
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("feedback-sheet").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(
            androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("feedback-sheet")) and
                androidx.compose.ui.test.hasText(AboutCopy.FEEDBACK_GROUP),
            useUnmergedTree = true,
        ).performClick()
        compose.awaitOne(AboutCopy.GROUP_SHEET_SUB)
        compose.onAllNodesWithTag("wechat-qr").assertCountEquals(0)
        compose.onNodeWithTag("support-issues").performClick()
        assertEquals(listOf(MikaLinks.X_GROUP), calls.urls)
    }

    /** サポートの行からシートを開き、このフォークの問い合わせ先と原作へのリンクを表示する。 */
    @Test
    fun groupRowOpensGroupSheet() {
        val calls = about()
        compose.onNodeWithText(AboutCopy.GROUP).performClick()
        compose.awaitOne(AboutCopy.GROUP_SHEET_SUB).assertIsDisplayed()
        compose.onAllNodesWithTag("wechat-qr").assertCountEquals(0)
        compose.onNodeWithTag("support-issues").assertIsDisplayed()
        compose.onNodeWithTag("support-upstream").performClick()
        compose.waitForIdle()
        assertEquals(listOf(MikaLinks.UPSTREAM), calls.urls)
    }

    /** 日本語のサポートシートに、フォークの問い合わせ先と原作へのリンクを表示する。 */
    @Test
    fun supportSheetShowsTheForkAndUpstream() {
        compose.setContent { MikaTheme { SupportSheet(onIssues = {}, onUpstream = {}, onDismiss = {}) } }
        compose.awaitOne(AboutCopy.GROUP_SHEET_SUB)
        compose.onNodeWithTag("support-issues").assertIsDisplayed()
        compose.onNodeWithTag("support-upstream").assertIsDisplayed()
        compose.onAllNodesWithTag("wechat-qr").assertCountEquals(0)
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    @Test
    fun checkRowStartsCheckWhenNoUpdate() {
        val calls = about(UpdateState(latest = r020.copy(version = "0.1.0")))
        compose.onNodeWithText(AboutCopy.UP_TO_DATE).assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.CHECK).performClick()
        assertEquals(1, calls.checks)
        compose.onAllNodesWithTag("update-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun checkingShowsSpinner() {
        about(UpdateState(status = CheckStatus.Checking))
        compose.onNodeWithTag("update-spinner").assertExists()
    }

    @Test
    fun offlineShowsReason() {
        about(UpdateState(status = CheckStatus.Failed))
        compose.onNodeWithText(AboutCopy.OFFLINE).assertIsDisplayed()
    }

    @Test
    fun updateRowOpensUpdateSheet() {
        val calls = about(UpdateState(latest = r020, update = r020, status = CheckStatus.Done))
        compose.onNodeWithText(AboutCopy.available("0.2.0")).assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(AboutCopy.CHECK).performClick()
        compose.awaitOne(AboutCopy.updateTitle("0.2.0"))
        compose.onNodeWithText(AboutCopy.updateSub("0.1.0", true, "2026-11-05")).assertExists()
        compose.onNodeWithText(AboutCopy.UPDATE_LATER).assertExists()
        compose.onNodeWithText(appString(com.wangzan101.mika.R.string.about_update_download_in_app)).assertExists()
        compose.waitForIdle()
        assertEquals(0, calls.checks)
        assertTrue(calls.urls.isEmpty())
    }

    @Test
    fun updateWithoutApkStillOpensReleasePage() {
        val r = r020.copy(apkUrl = null)
        val calls = about(UpdateState(latest = r, update = r))
        compose.onNodeWithText(AboutCopy.CHECK).performClick()
        compose.awaitOne(AboutCopy.UPDATE_DOWNLOAD).performClick()
        assertEquals(listOf(r.pageUrl), calls.urls)
    }

    @Test
    fun downloadedUpdateOffersInstallAndDelete() {
        var installs = 0
        var deletes = 0
        compose.setContent {
            MikaTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.wangzan101.mika.about.ApkDownloadState(com.wangzan101.mika.about.DownloadPhase.Ready),
                        false, null, {}, { installs++ }, { deletes++ })
                }
            }
        }
        compose.onNodeWithText(appString(com.wangzan101.mika.R.string.about_update_install)).performClick()
        compose.onNodeWithText(appString(com.wangzan101.mika.R.string.about_update_cancel)).performClick()
        assertEquals(1, installs)
        assertEquals(1, deletes)
    }

    @Test
    fun downloadingShowsProgressAndCanBeCancelled() {
        var deletes = 0
        compose.setContent {
            MikaTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.wangzan101.mika.about.ApkDownloadState(com.wangzan101.mika.about.DownloadPhase.Downloading, 50, 100),
                        false, null, {}, {}, { deletes++ })
                }
            }
        }
        compose.onNodeWithText("ダウンロード中 50%").assertIsNotEnabled()
        compose.onNodeWithText(appString(com.wangzan101.mika.R.string.about_update_cancel)).performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun failedDownloadCanBeRetried() {
        var retries = 0
        compose.setContent {
            MikaTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.wangzan101.mika.about.ApkDownloadState(com.wangzan101.mika.about.DownloadPhase.Failed),
                        false, null, { retries++ }, {}, {})
                }
            }
        }
        compose.onNodeWithText(appString(com.wangzan101.mika.R.string.about_update_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun changelogSheetShowsLatestNotes() {
        val calls = about(UpdateState(latest = r020))
        compose.onNodeWithText(AboutCopy.CHANGELOG).performClick()
        compose.awaitOne(AboutCopy.CHANGELOG_ALL)
        compose.onNodeWithText("0.2.0 · 2026-11-05").assertExists()
        compose.onNodeWithText(AboutCopy.CHANGELOG_ALL).performClick()
        compose.waitForIdle()
        assertEquals(listOf(MikaLinks.RELEASES), calls.urls)
    }

    /** サイドバー下端は設定を左、アプリ情報を右に配置し、更新があれば右端に更新マークを表示する。 */
    @Test
    fun drawerFooterHasSettingsAndAbout() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {},
                    onSettings = { calls += "settings" }, onOpen = {},
                    onAbout = { calls += "about" }, aboutDot = true,
                )
            }
        }
        compose.onNodeWithText(appString(com.wangzan101.mika.R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
        val footer = compose.onNodeWithTag("drawer-footer").getUnclippedBoundsInRoot()
        // クリック可能な行はテキストより幅が広いので、左右の配置は未結合ツリーのテキスト自体の境界で確認する。
        val settings = compose.onNodeWithText(appString(com.wangzan101.mika.R.string.settings_title), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val about = compose.onNodeWithText(AboutCopy.TITLE, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val dot = compose.onNodeWithTag("update-dot", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val mid = (footer.left + footer.right) / 2
        assertTrue(settings.right < mid)
        assertTrue(about.left > mid)
        // 更新マークは右側のアプリ情報に付ける。右端からの余白は行の12 dpと項目の12 dpを合わせた分。
        assertTrue(footer.right - dot.right <= 25.dp)
        compose.onNodeWithText(appString(com.wangzan101.mika.R.string.settings_title)).performClick()
        compose.onNodeWithText(AboutCopy.TITLE).performClick()
        assertEquals(listOf("settings", "about"), calls)
    }

    @Test
    fun drawerFooterNoDotWithoutUpdate() {
        compose.setContent {
            MikaTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {}, onSettings = {}, onOpen = {},
                )
            }
        }
        compose.onNodeWithText(AboutCopy.TITLE).assertIsDisplayed()
        compose.onAllNodesWithTag("update-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    /** 設定画面のアプリ情報の行には、更新があるとバージョン付きの案内と更新マークを表示する。 */
    @Test
    fun settingsAboutRowShowsUpdate() {
        compose.setContent {
            MikaTheme {
                SettingsContent(
                    status = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true),
                    androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                    versionName = "0.1.0", updateVersion = "0.2.0",
                )
            }
        }
        scrollTo(AboutCopy.available("0.2.0"))
        compose.onNodeWithText(AboutCopy.available("0.2.0")).assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
    }
}
