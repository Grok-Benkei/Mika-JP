package com.wangzan101.mika.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SandboxDnsTest {
    @Test fun usesDeviceResolversAndDoesNotInventPublicFallbacks() {
        assertEquals("nameserver 192.168.1.1\n", SandboxDns.resolvConf(listOf("192.168.1.1")))
        assertEquals(
            "nameserver 10.0.0.1\nnameserver fd00::1\n",
            SandboxDns.resolvConf(listOf("10.0.0.1", "fd00::1", "10.0.0.1")),
        )
        val empty = SandboxDns.resolvConf(emptyList())
        assertFalse(empty.contains("1.1.1.1"))
        assertFalse(empty.contains("8.8.8.8"))
        assertFalse(SandboxDns.resolvConf(null).contains("nameserver"))
    }

    @Test fun keepsPublicResolversWhenTheDeviceActuallyUsesThem() {
        assertEquals("nameserver 1.1.1.1\n", SandboxDns.resolvConf(listOf(" 1.1.1.1 ")))
    }
}
