package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppSwitchTest {
    private val pickers = setOf("com.android.permissioncontroller", "com.android.intentresolver", "com.android.documentsui")
    // システム設定によって提供されるページのみがターゲット タスクの一部です。プリインストールされたブラウザとアプリケーション ストアはカウントされません。
    private val settings = setOf("com.android.settings")
    private fun check(current: String?, sameTask: Boolean, target: String? = "com.clock"): AppSwitch.Switch? {
        var asked = false
        val r = AppSwitch.switchedTo(target, current, sameTask = { asked = true; sameTask }, partOfTask = pickers, settingsPage = { it in settings })
        if (current == null || current == target || current in pickers) assertFalse("sameTask queried needlessly", asked)
        return r
    }

    @Test fun systemPageOpenedByTheTargetInItsOwnTaskIsPartOfTheTask() {
        // 時計の「都市の追加」は、システム設定によって提供される選択ページを開きますが、時計自体のタスク内にあります
        assertNull(check("com.android.settings", sameTask = true))
    }

    @Test fun thirdPartyAppInTheTargetsTaskIsStillReportedButAsOpenedByTarget() {
        assertEquals(AppSwitch.Switch("com.share", byTarget = true), check("com.share", sameTask = true))
    }

    @Test fun preinstalledAppInTheTargetsTaskIsStillReported() {
        assertEquals(AppSwitch.Switch("com.vendor.browser", byTarget = true), check("com.vendor.browser", sameTask = true))
    }

    @Test fun systemPickersAndPermissionDialogsArePartOfTheTask() {
        for (p in pickers) assertNull(p, check(p, sameTask = false))
    }

    @Test fun anotherAppInAnotherTaskIsASwitch() {
        assertEquals(AppSwitch.Switch("com.ads", byTarget = false), check("com.ads", sameTask = false))
        // システム アプリですが、ターゲットのタスクに含まれていません: 依然として削除されたものとしてカウントされます
        assertEquals(AppSwitch.Switch("com.android.settings", byTarget = false), check("com.android.settings", sameTask = false))
    }

    @Test fun nothingToCompare() {
        assertNull(check("com.ads", sameTask = false, target = null))
        assertNull(check(null, sameTask = false))
        assertNull(check("com.clock", sameTask = false))
    }

    @Test fun theOtherCopyOfTheTargetAppInFrontIsASwitch() {
        // 同じアプリが 2 回インストールされます。一番上のアプリがターゲットではないアプリに置き換えられます
        assertEquals(AppSwitch.Switch("com.clock", byTarget = false),
            AppSwitch.switchedTo("com.clock", "com.clock", { true }, pickers, { false }, otherCopy = { true }))
        // それはまだターゲット コピーであるか、1 つのコピーだけがインストールされています (他のコピーについては尋ねません): まだカウントされません
        assertNull(AppSwitch.switchedTo("com.clock", "com.clock", { true }, pickers, { false }, otherCopy = { false }))
        assertNull(AppSwitch.switchedTo("com.clock", "com.clock", { true }, pickers, { false }))
    }
}
