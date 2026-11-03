package com.wangzan101.mika.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmGuideTest {
    // は、時間厳守の目覚まし時計の許可がない場合に 1 回だけ通知します。許可がある場合は、邪魔しないでください。
    @Test fun promptsOnceOnlyWhenPermissionMissing() {
        assertTrue(ExactAlarmGuide.shouldPrompt(granted = false, prompted = false))
        assertFalse(ExactAlarmGuide.shouldPrompt(granted = false, prompted = true))
        assertFalse(ExactAlarmGuide.shouldPrompt(granted = true, prompted = false))
    }
}
