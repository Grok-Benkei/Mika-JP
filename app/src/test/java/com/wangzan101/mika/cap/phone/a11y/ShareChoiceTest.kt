package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.settings.AppAccessStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** フローティング レイヤーをアクティブに共有するための 3 つのオプションは、契約に従ってアクセス許可のみを変更します。移動せずにキャンセルする、ブロックを解除せずにのみ共有する、およびこのアプリケーションが共有アクセス許可をクリアできるようにするです。 */
class ShareChoiceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = AppAccessStore(java.io.File(temp.root, "access.json"))

    @Test fun allowingShareOnlyKeepsTheAppBlocked() {
        val store = store()
        store.setBlocked("app", true)
        assertTrue(applyShareChoice(1, "app", store))
        assertTrue(store.current().canShare("app"))
        assertEquals("app_blocked", store.current().denial("app")?.code)
    }

    @Test fun removingFromBlocklistRestoresCallsAndDropsTheShareGrant() {
        val store = store()
        store.setBlocked("app", true)
        store.setShareAllowed("app", true)
        assertTrue(applyShareChoice(2, "app", store))
        assertNull(store.current().denial("app"))
        // 独立共有ライセンスがクリアされました: 今後は通常のアプリケーションとなり、個別の認証は必要なくなります
        assertTrue(store.current().shareAllowed.isEmpty())
        assertTrue(store.current().canShare("app"))
    }

    @Test fun cancellingChangesNothing() {
        val store = store()
        store.setBlocked("app", true)
        val before = store.current()
        assertFalse(applyShareChoice(0, "app", store))
        assertFalse(applyShareChoice(7, "app", store))
        assertEquals(before.blocked, store.current().blocked)
        assertEquals(before.shareAllowed, store.current().shareAllowed)
        assertEquals(before.revision, store.current().revision)
    }
}
