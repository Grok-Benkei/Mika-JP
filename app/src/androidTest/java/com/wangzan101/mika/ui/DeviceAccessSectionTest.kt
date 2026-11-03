package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator

class DeviceAccessSectionTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "device-access-ui-test")
    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun credentialEditorRequiresConfirmationAndNeverShowsSavedValue() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings = DeviceAccessSettings(dir, AesGcmKeyCipher { key })
        compose.setContent { MikaTheme(dark = false, reduceMotion = true) {
                PageScaffold(appString(R.string.mcp_title), {}) { DeviceAccessSection(settings) }
        } }
        compose.onNodeWithText(appString(R.string.device_access_credential)).performClick()
        compose.onNodeWithText(appString(R.string.device_access_save)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("device-access-credential").performScrollTo().performTextInput("123456")
        compose.onNodeWithText(appString(R.string.device_access_save)).assertIsNotEnabled()
        compose.onNodeWithTag("device-access-repeat").performScrollTo().performTextInput("123456")
        compose.onNodeWithText(appString(R.string.device_access_save)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { settings.credentialConfigured }
        compose.onNodeWithText(appString(R.string.device_access_pin_saved)).performScrollTo().assertIsDisplayed()
        settings.credential()!!.use { assertEquals("123456", String(it.value)) }
        compose.onNodeWithText("123456").assertDoesNotExist()
    }
}
