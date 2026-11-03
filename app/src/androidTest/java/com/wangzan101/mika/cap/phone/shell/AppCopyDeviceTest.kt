package com.wangzan101.mika.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機: 利用可能な別のユーザー (クローン、仕事用プロファイル) がある場合、そこにインストールされているアプリをミカの画面で開きます。一番上のタスクはそのユーザーに属し、ホーム画面は変更されません。
 * テスト対象はパラメータで決める: `-Pandroid.testInstrumentationRunnerArguments.copyPackage=<パッケージ名>`（追加で copyUser=<ユーザー番号> も指定できる）;
 * 指定がなければ一覧の最初のコピーを使う。システムが最初にどれを開くか尋ねる場合は、開く手順の側で選択肢をタップして目的のコピーを特定する。
 * 別のユーザーがいない、またはそこに目的の App がない場合はスキップする。開くだけで、App 内では何も操作しない。
 */
@RunWith(AndroidJUnit4::class)
class AppCopyDeviceTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun launchesTheCopyInItsUserOnMikasDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val users = shell.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS)
        assumeTrue("no usable other user on this device: ${shell.appCopyUsers()?.getString(ShellService.KEY_COPY_SUMMARY)}", users != null && users.isNotEmpty())
        val args = InstrumentationRegistry.getArguments()
        val wantPackage = args.getString("copyPackage")
        val wantUser = args.getString("copyUser")?.toIntOrNull()
        val copies = agent.screen.appCopies()
        val copy = copies.firstOrNull { (wantPackage == null || it.packageName == wantPackage) && (wantUser == null || it.userId == wantUser) }
        assumeTrue("no such copy (asked for ${wantPackage ?: "any"} in user ${wantUser ?: "any"}); listed: $copies", copy != null)
        copy!!
        val main = DeviceSupport.mainScreenState(shell)
        val r = agent.screen.openAppCopy(copy.packageName, copy.userId)
        assertTrue("open ${copy.packageName} in user ${copy.userId}: $r", r is ActionResult.Ok)
        r as ActionResult.Ok
        assertEquals("result must say which copy is showing: $r", copy.userId, r.profileUser)
        val top = shell.topTaskId(agent.display.displayId)
        assertEquals(copy.packageName, shell.packageOfTask(top))
        assertEquals(copy.userId, shell.userOfTask(top))
        assertEquals(copy.userId, agent.screen.target.value?.userId)
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
        // 利用可能でないユーザーは一切起動しない
        assertEquals(ShellService.START_USER_NOT_ALLOWED, shell.startAppInUser(agent.display.displayId, copy.packageName, 99_999, false))
    }
}
