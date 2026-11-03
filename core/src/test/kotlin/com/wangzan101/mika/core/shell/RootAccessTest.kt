package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessTest {
    /** PATHを先に調べ、標準候補を後に調べる。見つからなければ null。 */
    @Test
    fun findsSuOnPathThenInUsualPlaces() {
        assertEquals("/product/bin/su", SuProbe.find("/product/bin:/system/bin") { it == "/product/bin/su" || it == "/system/bin/su" })
        assertEquals("/system/xbin/su", SuProbe.find(null) { it == "/system/xbin/su" })
        assertEquals("/debug_ramdisk/su", SuProbe.find("", setOf("/debug_ramdisk/su")::contains))
        assertNull(SuProbe.find("/system/bin:/vendor/bin") { false })
        // PATHの相対パスと空要素は除き、末尾のスラッシュを取り除く。
        assertEquals("/system/bin/su", SuProbe.find("bin::/system/bin/") { it == "bin/su" || it == "/system/bin/su" })
    }

    @Test
    fun readsTheUidFromIdOutput() {
        assertEquals(0, SuProbe.uid("uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0"))
        assertEquals(2000, SuProbe.uid("uid=2000(shell) gid=2000(shell)"))
        assertEquals(2000, SuProbe.uid("2000\n"))
        assertEquals(0, SuProbe.uid("some banner\n0\n"))
        assertNull(SuProbe.uid(""))
        assertNull(SuProbe.uid("Permission denied"))
    }

    /** 要求したUID/GIDでの実行が確認できれば承認、不一致なら切替え失敗、無出力ならタイムアウトの有無で区別する。 */
    @Test
    fun answerFromOutputAndTimeout() {
        assertEquals(SuAnswer.Granted, SuProbe.answer("uid=0(root)", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.Granted, SuProbe.answer("uid=2000(shell) gid=2000(shell)", timedOut = false, expectUid = 2000))
        assertEquals(SuAnswer.WrongUid, SuProbe.answer("0", timedOut = false, expectUid = 2000))
        assertEquals(SuAnswer.Denied, SuProbe.answer("Permission denied", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.Denied, SuProbe.answer("", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.NoAnswer, SuProbe.answer("", timedOut = true, expectUid = 0))
    }

    /** 通常ヘルパーはUIDだけでは許可せず、実GIDもshellであることを確認する。rootコマンド経路は別に保つ。 */
    @Test
    fun helperRequiresBothShellUidAndShellGid() {
        for (output in listOf("2000", "uid=2000(shell)", "uid=2000(shell) gid=0(root)", "uid=0(root) gid=2000(shell)")) {
            assertEquals(output, SuAnswer.WrongUid, SuProbe.answer(output, timedOut = false, expectUid = 2000))
        }
        assertEquals(SuAnswer.Granted, SuProbe.answer("案内\nuid=2000(shell) gid=2000(shell) groups=2000(shell)", false, 2000))
        assertEquals(SuAnswer.Granted, SuProbe.answer("uid=0(root) gid=0(root)", false, 0))
        assertEquals(2000, SuProbe.gid("uid=2000(shell) gid=2000(shell)"))
        assertNull(SuProbe.gid("uid=2000(shell)"))
    }

    /** shell指定は-cより前、コマンドは一つの引数として維持する。 */
    @Test
    fun suArguments() {
        assertEquals(listOf("/system/bin/su", "-c", "ls -l /data"), SuProbe.rootArgs("/system/bin/su", "ls -l /data"))
        assertEquals(listOf("/sbin/su", "2000", "-c", "id -u; read n"), SuProbe.shellArgs("/sbin/su", "id -u; read n"))
    }

    /** 明示スイッチ・su・前回応答からroot起動の可否を決め、利用できない原因を保持する。 */
    @Test
    fun rootStartGate() {
        assertEquals(RootStart.Off, RootStart.of(enabled = false, suPresent = true, last = SuAnswer.Granted))
        assertEquals(RootStart.Ready, RootStart.of(enabled = true, suPresent = true, last = null))
        assertEquals(RootStart.Ready, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.Granted))
        assertEquals(RootStart.Gone, RootStart.of(enabled = true, suPresent = false, last = SuAnswer.Granted))
        assertEquals(RootStart.Denied, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.Denied))
        assertEquals(RootStart.NoAnswer, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.NoAnswer))
        assertEquals(RootStart.NoShellUid, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.WrongUid))
        assertNull(RootStart.Off.failure)
        assertNull(RootStart.Ready.failure)
        assertEquals(BuiltinShellFailure.RootDenied, RootStart.Denied.failure)
        assertEquals(BuiltinShellFailure.RootUnavailable, RootStart.Gone.failure)
        // 拒否や無応答は本人の再試行を待ち、su不在やUID/GID不一致は別の起動方法を案内する。
        assertEquals(BuiltinShellFix.Retry, BuiltinShellFailure.RootDenied.defaultFix)
        assertEquals(BuiltinShellFix.Retry, BuiltinShellFailure.RootNoAnswer.defaultFix)
        assertEquals(BuiltinShellFix.RePair, BuiltinShellFailure.RootUnavailable.defaultFix)
        assertTrue(!BuiltinShellFailures.retryable(BuiltinShellFailure.RootDenied))
    }

    @Test
    fun grantStateFollowsTheAnswer() {
        assertEquals(RootGrant.Unknown, RootGrant.of(null))
        assertEquals(RootGrant.Granted, RootGrant.of(SuAnswer.Granted))
        assertEquals(RootGrant.Denied, RootGrant.of(SuAnswer.Denied))
        assertEquals(RootGrant.NoAnswer, RootGrant.of(SuAnswer.NoAnswer))
        assertEquals(RootGrant.NoShellUid, RootGrant.of(SuAnswer.WrongUid))
    }

    /** 起動コマンドは最初にidを返し、合言葉をコマンド行へ含めない。nohupで切断時の終了を避ける。 */
    @Test
    fun rootCommandPrintsTheUidFirstAndKeepsTheNonceOffTheCommandLine() {
        val c = HelperHandoff.rootCommand("/data/app/x/base.apk", "com.wangzan101.mika", 10123, "ab")
        assertTrue(c.startsWith("id; read n; "))
        assertTrue(c, c.contains(" nohup setsid app_process "))
        assertEquals(
            c,
            "id; " + HelperHandoff.command("/data/app/x/base.apk", "com.wangzan101.mika", 10123, "ab").replace(" setsid ", " nohup setsid "),
        )
    }
}
