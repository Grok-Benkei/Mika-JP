package com.wangzan101.mika.confirm

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 実機テスト: スクリプト モデルは、仮想画面設定でお金を使う操作を宣言します (これは単なるレポートであり、実際に実行されることはありません。同意する必要があるのはお金だけであり、金額は常にレポートに含まれます)。
 * 優先度の高い通知のタイトル、本文、「同意する」「同意しない」ボタンと、同意しない、同意する、コメント自動拒否の3つのパスを確認します。
 * では、組み込みアシスタントが実行されており、Mika アクセシビリティ サービスがオンになっている必要があります。
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    // インターフェイスによって開始されたタスクは、最初のツールが呼び出される前に別の意図を尋ねます (このモデルは、軽量モデルがない場合に尋ねられます): 固定回答 ACT、スクリプトは必要ありません
    private val model = ScriptedModel().apply { intent = "ACT" }
    private var appTask = -1

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        if (appTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == appTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun attention(): Notification? = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        ?.notification

    private fun lastToolResult(call: ScriptedModel.Call): String =
        call.messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content

    /** 仮想画面で設定を開き、「注文する」と報告します。戻ったとき、タスクはユーザーの承認を待っています。 */
    private fun startAndRequest() {
        TaskService.start(context, "测试确认关卡")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"order","target":"测试项","content":"买测试项\n第二行","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation }
    }

    @Test
    fun notificationHasApproveDecline() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        assertEquals("要你同意:下单", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("在 ") && "对象:测试项" in text && "内容:买测试项 第二行" in text && "金额:¥25" in text)
        assertEquals(listOf("同意", "不同意"), n.actions.map { it.title.toString() })
        // と同じチャネルを引き継ぎます。これはチャネルを作成する際の優先度が高くなります。ユーザーまたはシステムが後でこの値を下げる可能性があります (アプリはそれを元に戻すことはできません)。ここでの唯一の要件は、デフォルトより低くなく、実際のレベルが記録されることです。
        assertEquals(TaskService.CHANNEL_ATTENTION, n.channelId)
        val importance = context.getSystemService(NotificationManager::class.java).getNotificationChannel(TaskService.CHANNEL_ATTENTION).importance
        Log.i("ConfirmationDeviceTest", "attention channel importance $importance")
        assertTrue("importance $importance", importance >= NotificationManager.IMPORTANCE_DEFAULT)
        // ロック画面にはアクションのみが表示され、オブジェクトやコンテンツは表示されません。
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        // 一般的なプロンプトにはすでに「オブジェクト」という単語があります。確認したいのは、特定のオブジェクトとコンテンツが公開されていないことです。 これら 2 つのボタンは、
        val publicText = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(publicText, "测试项" !in publicText && "第二行" !in publicText)
        // システム通知リストにも表示されます。
        val dump = sh("dumpsys notification --noredact")
        assertTrue("buttons not in dumpsys notification", "同意" in dump && "不同意" in dump)

        // 古い通知をクリックした回答（申告が正しくない場合）はカウントされません
        context.startService(Intent(context, TaskService::class.java).setAction(TaskService.ACTION_APPROVE)
            .putExtra(ConfirmationNotifier.EXTRA_REQUEST, 12345))
        Thread.sleep(1_000)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)

        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        assertEquals(true, pollUntil(3_000) { if (attention() == null) true else null })
        model.feed("end_not_done", """{"message":"用户没有同意下单,没有做这一步"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed && "用户没有同意下单" in end.reason)
    }

    @Test
    fun approveButtonAnswersTheLoop() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        // 通知の「同意する」ボタン自体を押すことで送信されるインテント
        n.actions.first { it.title.toString() == "同意" }.actionIntent.send()
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "\"approved\":true" in result)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value !is TaskState.WaitingConfirmation)
        // 同意しても実際に実行しない場合は、終了してください
        model.feed("end_not_done", """{"message":"测试到此为止"}""")
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
    }

    @Test
    fun evalAutoDeclineSkipsTheUser() {
        testTasks.evalHooks.autoDecideConfirmation = { false }
        TaskService.start(context, "测试确认关卡")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"send","target":"测试","content":"转账","amount":"¥25","app":"$SETTINGS"}""")
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        model.feed("end_not_done", """{"message":"没有同意"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed)
    }

    /** 2 回目の宣言後、最初の通知の [同意する] ボタンをクリックしてもカウントされません。 */
    @Test
    fun firstNotificationCannotApproveSecondRequest() {
        startAndRequest()
        val first = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        val firstApprove = first.actions.first { it.title.toString() == "同意" }.actionIntent
        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        assertTrue(lastToolResult(model.awaitCall()).contains("confirmation_declined"))
        // アクションの種類を変更して再度適用します（同じ種類のアプリが拒否された場合、再度要求されません）
        model.feed("request_confirmation", """{"action":"submit","target":"测试项","content":"提交测试项","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation && it.request.action.name == "submit" }
        pollUntil(5_000) { attention()?.takeIf { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "要你同意:提交" } }
        runCatching { firstApprove.send() } // 古いボタンはシステムによって無効化された可能性がありますが、無効化はまだ合格とみなされます。
        Thread.sleep(1_500)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)
    }
}

