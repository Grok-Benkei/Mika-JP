package com.wangzan101.mika.cap.root

import com.wangzan101.mika.core.cap.root.RootRun
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootCommandsTest {
    @Test fun disablingRootAfterTaskCreationPreventsTheNextSuProcess() = runTest {
        var allowed = true
        var suLookedUp = false
        var refused = false
        val commands = RootCommands(
            su = { suLookedUp = true; error("無効化後に su を起動してはいけません") },
            allowed = { allowed },
            refused = { refused = true },
        )
        allowed = false

        val result = commands.run("id", 1_000)

        assertEquals(RootRun.Denied, result)
        assertFalse(suLookedUp)
        assertFalse(refused)
    }
}
