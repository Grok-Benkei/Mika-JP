package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.SpeedFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsSpeedTest {
    @Test fun freshInstallTurnsOnDefaultsIncludingReplay() {
        val f = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).speedFlags()
        assertTrue(f.inlineScreen && f.batchSteps && f.adaptiveVision && f.replay)
        assertFalse(f.lightSteps)
    }

    @Test fun earlierUpgradeOnlyAddsReplayOnce() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        // 以前のバージョンがアップグレードされ、ユーザーが連続操作をオフにしました。今回は再生のみが追加され、他には何も触れていません
        prefs.edit().putString("speed_flags", "inlineScreen,adaptiveVision").putBoolean("speed_defaults_applied_v2", true).commit()
        val flags = FeatureFlags(prefs)
        assertEquals(SpeedFlags(inlineScreen = true, adaptiveVision = true, replay = true), flags.speedFlags())
        // アップグレード後、ユーザーが手動で再生をオフにしても、再生は閉じたままになります。
        flags.setSpeed(flags.speedFlags().copy(replay = false))
        assertFalse(FeatureFlags(prefs).speedFlags().replay)
    }
}
