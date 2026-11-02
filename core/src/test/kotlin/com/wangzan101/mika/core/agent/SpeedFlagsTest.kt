package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeedFlagsTest {
    @Test fun parseNoneIsOff() {
        assertEquals(SpeedFlags.OFF, SpeedFlags.parse("none"))
        assertEquals(SpeedFlags.OFF, SpeedFlags.parse(""))
        assertEquals("none", SpeedFlags.OFF.describe())
    }

    @Test fun parseRoundTripsDescribe() {
        val f = SpeedFlags.parse("replay=on,inlineScreen=on,batchSteps=off")
        assertEquals(SpeedFlags(inlineScreen = true, replay = true), f)
        assertEquals("inlineScreen,replay", f.describe())
        assertEquals(f, SpeedFlags.parse(f.describe()))
        val all = SpeedFlags(true, true, true, true, true, true, true)
        assertEquals(all, SpeedFlags.parse(all.describe()))
        assertEquals(SpeedFlags(adaptiveVision = true), SpeedFlags.parse("adaptiveVision"))
    }

    @Test fun unknownKeyRejected() {
        assertThrows(IllegalArgumentException::class.java) { SpeedFlags.parse("warpSpeed=on") }
        assertThrows(IllegalArgumentException::class.java) { SpeedFlags.parse("replay=maybe") }
    }

    @Test fun retiredKeyIgnored() {
        // のステップカットが修正され、残りの古い設定でも他のスイッチを読み取ることができます
        assertEquals(SpeedFlags(replay = true), SpeedFlags.parse("cacheFriendlyPrune,replay"))
    }
}
