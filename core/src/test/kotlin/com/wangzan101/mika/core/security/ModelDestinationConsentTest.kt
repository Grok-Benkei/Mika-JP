package com.wangzan101.mika.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDestinationConsentTest {
    @Test fun firstHostAndLoopbackDoNotNeedConfirmation() {
        assertFalse(ModelDestinationConsent.needsConfirmation(null, "https://api.deepseek.com"))
        assertFalse(ModelDestinationConsent.needsConfirmation("api.deepseek.com", "https://api.deepseek.com/v1"))
        assertFalse(ModelDestinationConsent.needsConfirmation("api.deepseek.com", "http://127.0.0.1:11434/v1"))
    }

    @Test fun aDifferentHostNeedsConfirmation() {
        assertTrue(ModelDestinationConsent.needsConfirmation("api.deepseek.com", "https://evil.example/v1"))
        assertTrue(ModelDestinationConsent.needsConfirmation("api.deepseek.com", "not a url"))
    }
}
