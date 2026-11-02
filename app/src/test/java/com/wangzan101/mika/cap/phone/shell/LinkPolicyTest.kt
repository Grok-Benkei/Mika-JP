package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** シェル側でリンクを再度確認します。:core ディストリビュータと同じ一連の使用例で、ディストリビュータをバイパスして startView を直接呼び出すことも停止できます。 */
class LinkPolicyTest {
    private val invalid = ShellService.START_INVALID_LINK

    @Test fun rejectsBlockedSchemes() {
        for (u in listOf("intent://x#Intent;end", "file:///sdcard/a", "content://media/1", "javascript:alert(1)")) {
            assertEquals(u, invalid, ShellLinks.refuse(null, u, "com.x.y"))
        }
    }

    @Test fun communicationLinksAllowed() {
        for (u in listOf("tel:10086", "sms:10086", "smsto:10086", "mailto:a@b.c", "mms:1", "sip:a@b.c", "geo:0,0")) {
            assertNull(ShellLinks.refuse(null, u, "com.example.app"))
        }
    }

    @Test fun httpRequiresPackage() {
        assertEquals(invalid, ShellLinks.refuse(null, "https://example.com", null))
        assertNull(ShellLinks.refuse(null, "https://example.com", "com.example.app"))
    }

    @Test fun unknownSettingsActionRejected() {
        assertEquals(invalid, ShellLinks.refuse("android.settings.SOME_VENDOR_PAGE", null, null))
        assertEquals(invalid, ShellLinks.refuse("android.intent.action.CALL", "tel:1", null))
        assertNull(ShellLinks.refuse("android.settings.WIFI_SETTINGS", null, null))
    }

    @Test fun appDetailsNeedsPackageUri() {
        val a = "android.settings.APPLICATION_DETAILS_SETTINGS"
        assertEquals(invalid, ShellLinks.refuse(a, null, null))
        assertEquals(invalid, ShellLinks.refuse(a, "https://x.com", null))
        assertNull(ShellLinks.refuse(a, "package:com.x.y", null))
    }

    @Test fun paymentLinksAllowed() {
        assertNull(ShellLinks.refuse(null, "someapp://cashier?id=1", "com.example.app"))
        assertNull(ShellLinks.refuse(null, "someapp://wxpay/1", "com.example.app"))
    }

    @Test fun inUseWhenAnyCandidateHasATaskOnTheMainScreen() {
        val main = setOf("com.a", "com.b")
        assertEquals(true, ShellLinks.inUse(listOf("com.c", "com.b"), main))
        assertEquals(false, ShellLinks.inUse(listOf("com.c"), main))
        // パッケージ名が解析できない場合は、パッケージ名を明確に確認できません。使用済みとして扱ってください。
        assertEquals(true, ShellLinks.inUse(emptyList(), main))
    }
}
