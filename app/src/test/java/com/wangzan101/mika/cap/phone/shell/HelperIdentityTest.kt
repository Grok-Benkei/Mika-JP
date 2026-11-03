package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperIdentityTest {
    @Test fun theNormalHelperRequiresBothShellUidAndShellGid() {
        assertTrue(HelperProcess.acceptsIdentity(2000, 2000))
        assertFalse(HelperProcess.acceptsIdentity(0, 0))
        assertFalse(HelperProcess.acceptsIdentity(0, 2000))
        assertFalse(HelperProcess.acceptsIdentity(2000, 0))
        assertFalse(HelperProcess.acceptsIdentity(1000, 2000))
        assertFalse(HelperProcess.acceptsIdentity(2000, 1000))
    }
}
