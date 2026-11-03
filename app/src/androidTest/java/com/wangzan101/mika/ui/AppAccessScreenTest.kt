package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAccessScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun appAccessLoadsWithoutModelAndRecommendedCanBeCancelled() {
        compose.setContent { com.wangzan101.mika.ui.theme.MikaTheme { AppAccessScreen(onBack = {}, classifyApps = false) } }
        compose.onNodeWithText(appString(R.string.appaccess_title)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.appaccess_recommend), substring = true).performScrollTo().performClick()
        compose.onNodeWithText(appString(R.string.appaccess_recommend_title)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.appaccess_cancel)).performClick()
        compose.onNodeWithText(appString(R.string.appaccess_allow_all)).performScrollTo().assertIsDisplayed()
    }
}
