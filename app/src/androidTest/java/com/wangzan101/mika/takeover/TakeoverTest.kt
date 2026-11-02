package com.wangzan101.mika.takeover

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 実機テスト: スクリプト モデルは、仮想画面の設定サブページで引き継ぎを要求します。 「対応させていただきます」は設定をホーム画面に移動しサウンドを元に戻します。
 * 「終わりました。」仮想画面に戻り、再び音をミュートにすると、エージェントは次のラウンドで観察します。
 * が一時的にメイン画面（専用テスト機）を占有しますので、終了したらメイン画面から設定をオフにしてください。内蔵アシスタントを実行し、Mika アクセシビリティ サービスをオンにする必要があります。
 */
@RunWith(AndroidJUnit4::class)
class TakeoverTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    private val model = ScriptedModel()
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        // キャンセルすると、アプリはホーム画面に残ります (ユーザーの目から離れません)。テストマシンでは、テスターが自分で閉じることができます
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    /**
     * このアプリの通知が多すぎる場合、システムはグループの概要 (タイトルとボタンなし) を自動的に追加します。同じチャネルをたどる可能性があるため、スキップする必要があります。
     * は、「他のテストで順位を上げた後、空のタイトルを獲得しました」の前に、この概要を取得しました。
     */
    private fun attentionRecord() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun attention(): Notification? = attentionRecord()?.notification

    private fun attentionActions(): List<String>? = attention()?.actions?.map { it.title.toString() }

    /**
     * 通知ボタンをクリックします。ボタンはインターフェイスを表示せずに [TakeoverActivity] を開き (通知バーは閉じます)、タスク サービスに転送されます。
     * バックグラウンドでのアクティビティ起動の制限により、テストプロセス自体の送信ボタンのPendingIntentがブロックされます(通知バーからのクリックは制限されません)。
     * したがって、ここでボタンのターゲットを確認した後、同じインテントを使用してボタンを直接起動します。
     */
    private fun tapAttentionAction(title: String, action: String) {
        val a = attention()?.actions?.firstOrNull { it.title.toString() == title } ?: error("no action $title: ${attentionActions()}")
        assertTrue(a.actionIntent.isActivity)
        context.startActivity(Intent(context, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun attentionTitle(): String? = attention()?.extras?.getCharSequence("android.title")?.toString()

    @Test
    fun handOverMovesToMainAndBack() {
        TaskService.start(context, "测试接管")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        val appTask = shell.topTaskId(displayId)
        assertTrue(appTask >= 0)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        // 設定サブページを開いて、そのサブページの引き継ぎをリクエストします。一部のシステムでは、別のタスクでサブページが開きます (アプリのログイン ページや支払いページもこれを行うことがよくあります)。
        // は、仮想画面の上部にあり、ユーザーが処理する必要があるタスクを引き継ぐ必要があります。
        sh("am start --display $displayId -a android.settings.DISPLAY_SETTINGS")
        settingsTask = pollUntil(3_000) { shell.topTaskId(displayId).takeIf { it != appTask } } ?: appTask
        android.util.Log.i("TakeoverTest", "app task $appTask, sub-page task $settingsTask")

        model.feed("hand_to_user", """{"reason":"captcha","message":"请完成页面上的验证"}""")
        assertEquals(TaskState.WaitingTakeover("captcha", "请完成页面上的验证"), awaitState { it is TaskState.WaitingTakeover })
        assertEquals("需要你处理:验证码", pollUntil(5_000) { attentionTitle() })
        assertEquals(listOf("我来处理", "跳过", "取消任务"), pollUntil(5_000) { attentionActions() })

        // 「私が処理します」: ホーム画面の上部に設定されており、音を出すことができます。この動きは意図的なものであり、ユーザーが持ち去ったものとはみなされません。
        tapAttentionAction("我来处理", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(priorAudio, pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == priorAudio } })
        assertEquals(listOf("处理好了", "我自己做完了", "取消任务"),
            pollUntil(5_000) { attentionActions()?.takeIf { it.size == 3 } })
        Thread.sleep(1_500) // 「削除された」ポーリングを少なくとも 2 ラウンド実行させます
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingTakeover)
        assertEquals(Takeover.Stage.HandedOver, DeviceTaskState.takeoverStage.value)

        // 「処理済み」(インターフェイス上のボタンは同じ入り口に移動します): 仮想画面に戻り、サウンドを再度ミュートにすると、エージェントは最初に確認するためのプロンプトを受け取ります。
        TaskService.handBack(context)
        val history = model.awaitCall().messages
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
        assertEquals("ignore", shell.getAudioMode(SETTINGS))
        // 返される結果は hand_to_user: complete のツール結果であり、最初に検証が必要です
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        val text = last.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(text, "\"outcome\":\"complete\"" in text && AgentLoop.HANDBACK_NOTE in text)
        assertEquals(true, pollUntil(3_000) { if (attentionActions() == null) true else null })

        model.feed("phone_observe")
        val afterObserve = model.awaitCall().messages
        val result = afterObserve.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(result.content, "\"currentApp\":\"$SETTINGS\"" in result.content)

        // は、私が読んだばかりの要素名を引用して返信しました。本当に読み取られ、文字通りの検証が正しく、結末が検証されて完了しました。
        val quote = Regex("\"name\":\"([^\"\\\\]{2,40})\"").findAll(result.content).map { it.groupValues[1] }
            .firstOrNull { n -> n.count { it.isLetter() } >= 2 && n.none { it.isDigit() } } ?: error("no element name on screen: ${result.content}")
        val answer = "done,设置里有「$quote」"
        model.answer(answer)
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == answer && end.verified)
        // ステータスが先に終了に変わります。音の復元はエンディングで行われます。 Busy が false に戻るまで待ってから、もう一度確認してください。
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /** 設定を開いて引き継ぎをリクエストし、通知が表示されるまで待ちます。エージェントの仮想スクリーン番号を返します。 */
    private fun requestTakeover(): Int {
        TaskService.start(context, "测试接管")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        model.feed("hand_to_user", """{"reason":"login","message":"请在设置里看一下"}""")
        awaitState { it is TaskState.WaitingTakeover }
        pollUntil(5_000) { attentionActions() } ?: error("no takeover notification")
        return displayId
    }

    private fun lastToolResult(history: List<Message>): String {
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        return last.parts.filterIsInstance<Part.ToolResult>().single().content
    }

    /** メイン画面の設定ウィンドウで、スイッチではない最初のクリック可能な項目の中央。クリックするとサブページのみに入ります。 */
    private fun clickableOnMainScreen(): Rect? = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
        .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == SETTINGS }
        ?.let { root ->
            fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.isClickable && !n.isCheckable && n.isVisibleToUser && !n.text.isNullOrBlank()) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c)?.let { return it } }
                return null
            }
            find(root)?.let { Rect().also(it::getBoundsInScreen) }
        }

    @Test
    fun skipButtonPresent() {
        requestTakeover()
        assertEquals(listOf("我来处理", "跳过", "取消任务"), attentionActions())
        tapAttentionAction("跳过", TaskService.ACTION_SKIP)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"skipped\"" in text && "\"reason\":\"takeover_skipped\"" in text)
        // は引き継がれておらず、アプリは常に仮想画面上にあります。
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
    }

    @Test
    fun handBackReportsInputCount() {
        requestTakeover()
        tapAttentionAction("我来处理", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(Takeover.Stage.HandedOver, pollUntil(5_000) { DeviceTaskState.takeoverStage.value.takeIf { it == Takeover.Stage.HandedOver } })

        // ユーザーがホーム画面でアプリを 2 回クリックします: エントリを入力し、戻り、再度入力します。
        repeat(2) { i ->
            val target = pollUntil(5_000) { clickableOnMainScreen() } ?: error("nothing clickable in settings on the main screen")
            sh("input -d ${Display.DEFAULT_DISPLAY} tap ${target.centerX()} ${target.centerY()}")
            Thread.sleep(1_000)
            if (i == 0) {
                sh("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_BACK")
                Thread.sleep(1_000)
            }
        }

        TaskService.handBack(context)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"complete\"" in text)
        val count = Regex("\"inputCount\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: error("no inputCount: $text")
        assertTrue(text, count >= 2)
        assertTrue(text, "\"stoppedAt\":" in text)
    }
}
