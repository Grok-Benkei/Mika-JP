package com.wangzan101.mika.task

import com.wangzan101.mika.DeviceTaskState
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.shell.IMikaShell
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 実機テスト: スイッチParallelTask​​sをオンにすると、2つのダイアログタスクが同時に実行され、それぞれが仮想画面を占有します。
 * には、Android 14 以降、内蔵アシスタントが実行されており、Mika アクセシビリティ サービスがオンになっている必要があります。スクリプト モデルは、開始順に 2 つのタスクに分割されます。
 */
@RunWith(AndroidJUnit4::class)
class ParallelDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    private val models = listOf(ScriptedModel(), ScriptedModel())
    private val handedOut = AtomicInteger()
    private lateinit var priorFlags: SpeedFlags

    @Before
    fun setUp() {
        assumeTrue("parallel displays need Android 14+", Build.VERSION.SDK_INT >= ParallelCapability.MIN_SDK)
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val flags = FeatureFlags.get(context)
        // キル プロセス テストの 2 回目の実行: 最初にキルされたとき、エンディングは実行されず、スイッチはまだオンでした。現時点ではなく、最初の開始前に書き留められた内容に復元する必要があります。
        priorFlags = killRecord.takeIf { it.exists() }?.let { runCatching { SpeedFlags.parse(JSONObject(it.readText()).getString(FLAGS_KEY)) }.getOrNull() }
            ?: flags.speedFlags()
        flags.setSpeed(priorFlags.copy(parallelTasks = true))
        testTasks.modelFactory = { _, _ -> models[handedOut.getAndIncrement().coerceAtMost(1)] }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        if (!::priorFlags.isInitialized) return
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        FeatureFlags.get(context).setSpeed(priorFlags)
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun clockPackage(): String =
        context.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
            ?: error("no clock app handles ACTION_SHOW_ALARMS")

    /** は、新しいダイアログ タスクを開き、そのモデルが初めて呼び出されるのを待ちます。ダイアログ番号を返します。 */
    private fun startTask(goal: String, model: ScriptedModel): String {
        val before = DeviceTaskState.conversationId.value
        TaskService.start(context, goal)
        assertEquals(1, model.awaitCall().number)
        return pollUntil(5_000) { DeviceTaskState.conversationId.value?.takeIf { it != before } } ?: error("task did not start")
    }

    private fun displayOf(conversationId: String): Int =
        pollUntil(10_000) { DeviceTaskState.run(conversationId)?.displayId?.value?.takeIf { it >= 0 } } ?: -1

    private fun finish(model: ScriptedModel) = model.answer("done")

    private fun ended(conversationId: String): TaskState? {
        val run = DeviceTaskState.run(conversationId) ?: return TaskState.Cancelled
        return run.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled }
    }

    /** 2 つのタスクが 2 つの画面で同時に実行され、完了できます。この期間中、ユーザーのホーム画面は影響を受けません。 */
    @Test
    fun twoTasksRunOnTwoDisplays() {
        val mainBefore = DeviceSupport.mainScreenState(shell)
        val (a, b) = models
        val first = startTask("设置版本号", a)
        val second = startTask("在时钟里新建一个带标记的闹钟", b)
        assertEquals(2, DeviceTaskState.runs.value.size)

        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"${clockPackage()}"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        val displayA = displayOf(first)
        val displayB = displayOf(second)
        assertTrue(displayA > 0 && displayB > 0)
        assertNotEquals(displayA, displayB)
        assertTrue(shell.topTaskId(displayA) >= 0)
        assertTrue(shell.topTaskId(displayB) >= 0)
        // 2 つの画面は独立して動作し、メイン画面と最上位のタスクのフォーカスは変わりません。
        a.feed("phone_back")
        b.feed("phone_back")
        assertEquals(3, a.awaitCall().number)
        assertEquals(3, b.awaitCall().number)
        assertEquals(mainBefore, DeviceSupport.mainScreenState(shell))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(20_000) { ended(first) })
        assertNotNull(pollUntil(20_000) { ended(second) })
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
        assertFalse("Mika display left behind", "mika-agent" in sh("dumpsys display"))
    }

    /** 両方のタスクで設定を開く必要があります。後で開いたタスクは app_in_use_by_other_task を取得し、最初のタスクから設定を取り除きません。 */
    @Test
    fun sameAppSecondTaskGetsConflictError() {
        val (a, b) = models
        val first = startTask("设置版本号", a)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, a.awaitCall().number)
        val displayA = displayOf(first)
        val settingsTask = shell.topTaskId(displayA)

        startTask("设置里看存储", b)
        b.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        val result = b.awaitCall().messages.last().toString()
        assertTrue(result, "app_in_use_by_other_task" in result)
        assertEquals(settingsTask, shell.topTaskId(displayA))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
    }

    /**
     * レビュー フォーカス 5: 2 つのタスク中に、Mika プロセスが強制終了されました。両方のパッケージの PLAY_AUDIO は、次回 2 つのタスクが開始されるときに復元されます。ミカの仮想画面はもう存在しません。
     * テストは、Mika プロセスで実行されます。プロセスを強制終了するとテストも一緒に終了するため、2 回に分けて実行され、途中でプロセスが外部から強制終了されます。
     * が初めて配置され、files/Parallel-kill-test.json (元の PLAY_AUDIO と開始前のスイッチの 2 つのパッケージ) に記録が残され、待機します。
     * レコードを確認した後、開発マシンで `adb shell am force-stop <Mika 包名>` を実行します (今回はクラッシュが報告されます)。
     * 2 回目 (プロセスは起動時に再起動され復元されます)、レコード チェックを押してスイッチをレコード内のスイッチに復元します (最初に強制終了されたときはエンディングは実行されませんでした)。
     * 注: 強制停止により、有効なアクセシビリティ サービス (システム動作) からミカが削除されるため、2 回目に実行する前に再度有効にする必要があります。
     */
    private val killRecord get() = File(context.filesDir, "parallel-kill-test.json")

    @Ignore("two-run manual procedure: needs an external `adb shell am force-stop` while this test waits; a normal run would sit for 120 s and then fail")
    @Test
    fun killDuringTwoTasksRecovers() {
        val record = killRecord
        if (record.exists()) {
            val saved = JSONObject(record.readText())
            record.delete()
            try {
                for (pkg in saved.keys()) {
                    if (pkg == FLAGS_KEY) continue
                    assertEquals(pkg, saved.getString(pkg), pollUntil(60_000) { shell.getAudioMode(pkg)?.takeIf { it == saved.getString(pkg) } })
                }
                assertFalse("Mika display left behind", "mika-agent" in sh("dumpsys display"))
            } finally {
                // も最後に復元されます。ここでは、最初に復元され、アサーションが失敗したときにスイッチが開いたままになることはありません。
                FeatureFlags.get(context).setSpeed(priorFlags)
            }
            return
        }
        val clock = clockPackage()
        val prior = JSONObject().put(SETTINGS, shell.getAudioMode(SETTINGS)).put(clock, shell.getAudioMode(clock))
            .put(FLAGS_KEY, priorFlags.describe())
        val (a, b) = models
        startTask("设置版本号", a)
        startTask("在时钟里新建一个带标记的闹钟", b)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"$clock"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(clock)?.takeIf { it == "ignore" } })
        record.writeText(prior.toString())
        // プロセスのバックグラウンドに配置された自殺コマンドは実行されず (テスト シェル セッションで終了します)、外部からのみ終了できます。
        // レコード ファイルが表示されたことを確認した後、開発マシンで adb shell amforce-stop <パッケージ名> を実行します。待ちきれない場合は、記録をクリアして失敗として終了してください。
        Thread.sleep(KILL_WAIT_MS)
        record.delete()
        error("Mika was not killed: run `adb shell am force-stop ${context.packageName}` after ${record.name} appears, then rerun this test")
    }

    private companion object {
        const val FLAGS_KEY = "speed_flags"
        const val KILL_WAIT_MS = 120_000L
    }
}
