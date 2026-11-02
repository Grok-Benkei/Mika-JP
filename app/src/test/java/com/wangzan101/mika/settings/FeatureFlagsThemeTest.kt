package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 外観が変わったら（設定ページやタスクの中で）UI はその場で追随して変わり、UI の再構築には頼らない。 */
class FeatureFlagsThemeTest {
    @Test fun defaultsToSystemAndUnknownFallsBack() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        assertEquals(ThemeMode.System, FeatureFlags(prefs).themeMode())
        prefs.edit().putString("theme_mode", "Sepia").commit()
        assertEquals(ThemeMode.System, FeatureFlags(prefs).themeMode())
    }

    @Test fun changesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val seen = mutableListOf<ThemeMode>()
        val job = launch { flags.themeModeChanges().take(3).toList(seen) }
        runCurrent()
        flags.setThemeMode(ThemeMode.Dark)
        runCurrent()
        SelfConfigImpl(flags).set("appearance", "light")
        job.join()
        assertEquals(listOf(ThemeMode.System, ThemeMode.Dark, ThemeMode.Light), seen)
    }
}
