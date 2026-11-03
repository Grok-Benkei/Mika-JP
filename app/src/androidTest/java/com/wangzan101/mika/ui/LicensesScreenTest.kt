package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.about.MikaLinks
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun japaneseGuidePrecedesTheOriginalAndBackReturnsToTheGuide() {
        var backs = 0
        val urls = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { urls += uri }
            }) {
                MikaTheme { LicensesScreen(onBack = { backs++ }) }
            }
        }
        compose.onNodeWithTag("license-guide").assertIsDisplayed()
        compose.onNodeWithTag("license-original-text").assertDoesNotExist()
        compose.onNodeWithText("対応ソースの配布ページ").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(MikaLinks.RELEASES), urls) }

        compose.onNodeWithText("Apache License 2.0").performScrollTo().performClick()

        compose.onNodeWithTag("license-original-text").assertTextContains("Apache License", substring = true)
        pressBack()
        compose.onNodeWithTag("license-guide").assertIsDisplayed()
        compose.onNodeWithTag("license-original-text").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, backs) }
    }

    @Test fun theBundledGnuOriginalCanBeOpenedWithoutLeavingTheApp() {
        compose.setContent { MikaTheme { LicensesScreen(onBack = {}) } }

        compose.onNodeWithText("GNU LGPL バージョン3").performScrollTo().performClick()

        compose.onNodeWithTag("license-original-text").assertTextContains("GNU LESSER GENERAL PUBLIC LICENSE", substring = true)
        compose.onNodeWithTag("license-original-error").assertDoesNotExist()
    }
}
