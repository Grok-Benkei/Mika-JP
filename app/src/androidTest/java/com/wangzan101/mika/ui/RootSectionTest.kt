package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.setup.RootControl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** コールバックだけを使い、su の起動や端末設定の変更は行わない。 */
@RunWith(AndroidJUnit4::class)
class RootSectionTest {
    @get:Rule val compose = createComposeRule()
    private val starts = mutableListOf<Boolean>()
    private val commands = mutableListOf<Boolean>()

    private fun show() = compose.setContent {
        RootSection(
            state = RootControl.State(suPresent = true), helperRow = true,
            onStart = { starts += it }, onRetryStart = {}, onCommands = { commands += it },
        )
    }

    @Test fun displayingOrDismissingRootSettingsRequestsNoPermission() {
        show()
        compose.onNodeWithTag("root-start").assertIsOff()
        compose.onNodeWithTag("root-commands").assertIsOff()
        compose.onNodeWithTag("root-commands").performClick()
        compose.onNodeWithText(PhoneSetupCopy.ROOT_COMMANDS_TITLE).assertExists()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), starts)
            assertEquals(emptyList<Boolean>(), commands)
        }

        compose.clickInDialog(appString(R.string.cancel))

        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), starts)
            assertEquals(emptyList<Boolean>(), commands)
        }
    }

    @Test fun onlyExplicitlyConfirmingTheWarningEnablesRootCommands() {
        show()
        compose.onNodeWithTag("root-commands").performClick()

        compose.clickInDialog(PhoneSetupCopy.ROOT_COMMANDS_CONFIRM)

        compose.runOnIdle {
            assertEquals(listOf(true), commands)
            assertEquals(emptyList<Boolean>(), starts)
        }
    }
}
