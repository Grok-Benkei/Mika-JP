package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApprovalStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun unreadableFileFallsBackToAsking() {
        val f = tmp.root.resolve("a.json").apply { writeText("{not json") }
        assertFalse(ApprovalStore(f).autonomous.value)
    }

    @Test fun olderFilesKeepTheirAutonomousSwitch() {
        // 古いバージョンのファイルには、承認レベルと長期承認も含まれています: もはや認識されず、読み取り専用の問い合わせ/独立
        val f = tmp.root.resolve("a.json").apply {
            writeText("""{"mode":"AUTO","autonomous":true,"grants":[{"action":"send","app":"com.chat","target":"张三","element":"发送"}]}""")
        }
        assertTrue(ApprovalStore(f).autonomous.value)
        ApprovalStore(f).setAutonomous(false)
        assertFalse("mode" in f.readText() || "grants" in f.readText())
    }

    private fun control() = ApprovalControl(ApprovalStore(tmp.root.resolve("a.json")))

    @Test fun autonomousIsOffByDefaultAndSurvivesARestart() {
        val f = tmp.root.resolve("a.json")
        assertFalse(ApprovalStore(f).autonomous.value)
        ApprovalControl(ApprovalStore(f)).setAutonomous(true)
        val again = ApprovalStore(f)
        assertTrue(again.autonomous.value)
        assertFalse(tmp.root.resolve("a.json.tmp").exists())
        ApprovalControl(again).setAutonomous(false)
        assertFalse(ApprovalStore(f).autonomous.value)
    }

    @Test fun runsInAskHaveAGateAndRunsInAutonomousDoNot() {
        val c = control()
        val ask = c.start()
        assertFalse(ask.startedAutonomous)
        assertFalse(ask.autonomous())
        assertNotNull(ask.gate())
        c.setAutonomous(true)
        val auto = c.start()
        assertTrue(auto.startedAutonomous)
        assertTrue(auto.autonomous())
        assertNull(auto.gate())
    }

    @Test fun remoteRunsAlwaysAskAndIgnoreTheSwitch() {
        val c = control()
        c.setAutonomous(true)
        // コンピュータに割り当てられたタスク: スイッチをオンにしても、コンピュータはクエリに従って動作を開始し、途中でスイッチを変更しても追従しません。
        val remote = c.start(remote = true)
        assertFalse(remote.startedAutonomous)
        assertFalse(remote.autonomous())
        assertNotNull(remote.gate())
        c.setAutonomous(false)
        c.setAutonomous(true)
        assertFalse(remote.autonomous())
    }

    @Test fun switchingFollowsEveryRunningTask() {
        val c = control()
        val a = c.start()
        val b = c.start()
        c.setAutonomous(true)
        // 実行中のタスクの次のステップから支払いを求める必要はありません。仕事を始めるときにカードがありますが、これは変わりません。
        assertTrue(a.autonomous())
        assertTrue(b.autonomous())
        assertFalse(a.startedAutonomous)
        c.setAutonomous(false)
        assertFalse(a.autonomous())
        assertFalse(b.autonomous())
        // 完了後、タスクを変更し、そのタスクには触れなくなります。新しいスイッチを押して次のタスクを開始します。
        c.end(a)
        c.setAutonomous(true)
        assertFalse(a.autonomous())
        assertTrue(b.autonomous())
        assertTrue(c.start().startedAutonomous)
    }
}
