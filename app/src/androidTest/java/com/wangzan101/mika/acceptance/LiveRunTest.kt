package com.wangzan101.mika.acceptance

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wangzan101.mika.DeviceTaskState
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.memory.MemoryRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.typeAndSend
import com.wangzan101.mika.ui.awaitOne
import com.wangzan101.mika.ui.appString
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.wangzan101.mika.R
import com.wangzan101.mika.browser.PageAskModel
import com.wangzan101.mika.browser.PageConversations
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.core.cap.web.AttachedPage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.core.cap.phone.OperatingPace

/**
 * 実マシンの受け入れ: 設定で構成された実モデルを使用してタスクを実行します。パラメータが明示的に渡された場合にのみ実行されます。通常、これはテストのセット全体でスキップされます。
 *
 * - 構成モデル (キーはパラメータを通じてのみ渡され、アプリ独自の暗号化ストレージに書き込まれ、他の場所には格納されません):
 *   `-e liveKey <key> -e liveBaseUrl <url> -e liveModel <model> [-e liveId <id>]`、[configureProvider] を実行します。
 * - メインモデル `-e liveMain <id>` をカットし、[configureProvider] も実行します。
 * - タスクを実行します:` -e liveGoal <ゴール> -e liveRun <レコード ディレクトリ名> [-e liveTimeoutSec 600] [-e liveOnTakeover cancel|handback]
 *   [-e livePace fast|standard|human] [-e liveMoveAwayAtStep N] [-e liveCancelAtStep N]`、[runGoal] を実行します。実際のインターフェイス経由: 入力ボックスにターゲットを入力し、[送信] をクリックします。
 *   liveCancelAtStep: N 番目のステップに到達したら、停止ボタンをクリックするなどしてタスクを停止し、停止後の説明が会話に記録される (result.txt の概要に書き込まれる) まで待ちます。
 *   `-e liveKeepCheckSec N`: タスクの完了後、バックグラウンドで実行されたままのページ (web_keep_open) が存在するかどうかを確認します。その場合は、ページ内のメディア再生の進行状況を N 秒ごとに 2 回読み取ります。
 *   (ビデオ/オーディオの currentTime、同じソースからのみ読み取ることができます) およびカウント専用タイマー。keep*(keptPage、keepMediaAdvanced、
 *   keepTimerAdvanced、keepNotification)、別の画像を保存します (keep-frame.png)。そのページは閉じずに、他の人が見られるように保存してください。
 *   livePace: アプリの操作リズムは速い/標準/人間的です (デフォルトは速い、速く実行します。設定を変更しないでください)。速いまたは遅い場合は標準が使用されます。
 *   liveMoveAwayAtStep: ステップ N に到達すると、仮想画面上のアプリをホーム画面に移動します (ユーザーがホーム画面で同じアプリを開いていることをシミュレートします)。
 *   一時停止ステータスと通知に注目し、通知の「続行」をクリックするのと同じように数秒後に再開します。
 *
 * - ブラウザ ページでの質問: ` -e liveUrl <URL> -e liveGoal <question> -e liveRun <record directory name> [-e liveTimeoutSec 600] [-e liveDesktop true]
 *   [-e liveNew true] [-e liveTouchAtStep N -e liveResumeAfterSec S] [-e liveOnTakeover cancel|handback]` で、[runPageAsk] を実行します。
 *   実際のインターフェイス経由: サイドバーでブラウザに入り、Web サイトを開き (liveDesktop を使用している場合はコンピューター版に変更します)、ページがロードされるまで待ち、ページの下部にある「Ask Mega」に質問を書き込み、「送信」をクリックします。
 *   (ライブ新規作成時に「新しい会話」をクリック)、このページはそのタスクに貸与されています。 liveTouchAtStep: ステップ N に到達したら、ウィンドウを通して指で Web ページを上にスワイプします
 *   (リンクをクリックせずに、実際の人間のように PageFrame を通過します)、一時停止があるかどうか、バナーと小さなカードが表示されるかどうかに注意し、S 秒 (デフォルトは 5) 待ってから、バナーの [続行] をクリックして回復を確認します。
 *   liveTouchAtStep が提供されていない場合、「操作中」によりタスクが一時停止された場合、失敗とみなされ (Mika 自身のクリックやスクロールによってタスクが一時停止されることはないはずです)、記録は書き込まれたとおりになります。
 *   result.txt runGoal のものに加えて、借用 (ページがタスクに貸与されている)、firstToolCalls、urlsOpened (web_navigate によって開かれた URL)、
 *   restartedSameUrl (このページは再度開かれました。存在しないはずです)、userTabUrlAtEnd、pausedByTouch、再開、一時停止 (さまざまな数の一時停止)、userPauses、
 *   communicationId、pageOrigin、listAsWeb (サイドバーのこの会話には、「Web ページ」という小さなラベルが付いています)。各ステップ、一時停止、再開、終了、サイドバーのメイン画面 (step-NN.png など) のスクリーンショットを撮ります。
 *
 * レコードは、アプリの外部ファイル ディレクトリ accept/<liveRun>/:log.txt (各ステップでモデルが示す内容、ツール呼び出し、結果の概要) に書き込まれます。
 * result.txt (モデル、目標、結果、ステップ数、所要時間、最終回答)、仮想画面の各ステップ、およびメイン画面のスクリーンショット。
 * が`-e liveSettleSec N`を通過した場合、結果判定と学習が完了するまで最大N秒待って、学習内容をLearned.txtに書き込みます。
 */
@RunWith(AndroidJUnit4::class)
class LiveRunTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    @After
    fun tearDown() {
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.evalHooks.operatingPace = null
    }

    @Test
    fun configureProvider() {
        val key = args.getString("liveKey")
        val main = args.getString("liveMain")
        assumeTrue(key != null || main != null)
        val store = ProviderStore.get(context)
        var saved: String? = null
        if (key != null) {
            val model = args.getString("liveModel") ?: error("liveModel missing")
            val id = args.getString("liveId") ?: model
            val baseUrl = args.getString("liveBaseUrl") ?: ProviderPresets.all.first().baseUrl
            // の機能は、アドレスが一致するテンプレートに従って取得されます (プロンプトワードのキャッシュ方法を含む、設定でのテンプレートの選択と同じ)
            val preset = ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.all.first()
            store.save(
                ProviderConfig(
                    id, ProviderConfig.Kind.OpenAiCompat, baseUrl,
                    // メインモデルを表示し、検証された位置を表示: パラメーターに従って開きます (位置の検証は、実際の測定証拠がある場合にのみ提供されます)
                    model, preset.capabilities.copy(vision = args.getString("liveVision") == "true",
                        groundingVerified = args.getString("liveGroundingVerified") == "true"), name = id,
                ),
                key,
            )
            check(store.apiKey(id) == key) { "key not stored" }
            saved = id
        }
        // 構成は目的のために使用する必要があります。そうでない場合は、誰も使用していないかのようにクリアされます。メイン モデルが指定されていない場合は、新しく保存されたモデルを使用します。
        (main ?: saved)?.let { check(store.choose(ModelRole.Main, RoleChoice.Model(it))) { "cannot assign $it" } }
        // 写真閲覧用メインモデル：写真閲覧用メインモデル
        if (args.getString("liveVision") == "true") store.choose(ModelRole.Vision, RoleChoice.SameAsMain)
    }

    @Test
    fun runGoal() {
        val goal = args.getString("liveGoal")
        assumeTrue(goal != null)
        // テストによって起動されたプロセスは、アプリの言語を設定しませんでした。通常の起動に従って、デフォルト言語をそれに変更します。組み込みブラウザはそれを押して、Web サイトにページ言語を尋ねます。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.getSystemService(android.app.LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty }?.let { android.os.LocaleList.setDefault(it) }
        }
        val run = args.getString("liveRun") ?: "run"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val moveAwayAt = args.getString("liveMoveAwayAtStep")?.toInt() ?: -1
        val cancelAt = args.getString("liveCancelAtStep")?.toInt() ?: -1
        val pace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        testTasks.evalHooks.operatingPace = pace
        var cancelled = false
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")

        DeviceSupport.shell()
        DeviceSupport.service()
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} pace=${pace.name.lowercase()} goal=$goal")
        // は、タスク終了後にプロセス レベルのスコープで実行することを学習します。最初は、そこにいくつかの永続的なものが存在することに注意し、最後にこの数値に戻るまで待ちます。
        val memory = MemoryRuntime.of(context)
        val learningJob = memory.scope.coroutineContext[Job]!!
        val resident = learningJob.children.count { it.isActive }

        compose.typeAndSend(goal!!)
        // 送信後にキーボードをしまう: 実際の人が送信後にキーボードをしまうのと同じように、ダイアログ ページの下にある小さな背景画面が表示されます。
        compose.runOnUiThread {
            val w = compose.activity.window
            compose.activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        val chat = (context.applicationContext as MikaApp).chat
        chat.ui.value.blocked?.let { error("blocked by prerequisites: $it") }
        // が送信されました。会話の中に次の質問があります。そうでない場合は、タイムアウトになるまで無駄に待たずに、すぐに報告してください。
        check(chat.ui.value.exchanges.any { it.goal == goal.trim() }) {
            "send did not go out: composer=${chat.ui.value.composer} exchanges=${chat.ui.value.exchanges.size} task=${DeviceTaskState.state.value}"
        }
        val t0 = System.currentTimeMillis()

        var lastStep = -1
        var takeovers = 0
        var movedAway = false
        var pausesHandled = 0
        var end: TaskState? = null
        var last: TaskState? = null
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val s = DeviceTaskState.state.value
            if (s != last) {
                note("state $s")
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                frame(dir, "step-%02d".format(s.step))
            }
            if (moveAwayAt > 0 && !movedAway && s is TaskState.Running && s.step >= moveAwayAt) {
                val shell = DeviceSupport.shell()
                val task = shell.topTaskId(testTasks.agentDisplayId.value)
                movedAway = true
                note("moving task $task (${shell.packageOfTask(task)}) to the main screen: ${task >= 0 && shell.moveTask(task, 0)}")
            }
            if (cancelAt > 0 && !cancelled && s is TaskState.Running && s.step >= cancelAt) {
                cancelled = true
                note("stopping at step ${s.step}")
                compose.runOnUiThread { (context.applicationContext as MikaApp).chat.cancel() }
            }
            if (s is TaskState.Held && s.reason.resumable && pausesHandled == 0) {
                pausesHandled++
                Thread.sleep(3000)
                val shell = DeviceSupport.shell()
                note("paused; still paused after 3s: ${DeviceTaskState.state.value}; main top task=${shell.topTaskId(0)} pkg=${shell.packageOfTask(shell.topTaskId(0))}")
                note("notifications: " + mikaNotifications())
                mainShot(dir, "paused-main")
                TaskService.resume(context)
                note("resume requested")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                frame(dir, "takeover-agent")
                mainShot(dir, "takeover-main")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context) else TaskService.cancel(context)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                note("chat status at end: ${chat.ui.value.exchanges.lastOrNull()?.status}")
                break
            }
            // インターフェイス テストのクロックは、テストが押された場合にのみ実行されます。待機中に一緒に押し進めると、ダイアログ ページのステップ、タイミング、小さな画面が通常どおり更新されます (デモンストレーションの記録に使用されます)。
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null) {
            note("timeout, cancelling")
            TaskService.cancel(context)
        }
        Thread.sleep(1500)
        compose.waitForIdle()
        mainShot(dir, "end-main")
        // 停止後の指示: このラウンドの回答として会話ページに表示されるまで待ちます
        val summary = if (end == TaskState.Cancelled) DeviceSupport.pollUntil(30_000) {
            chat.ui.value.exchanges.lastOrNull()?.status?.let { it as? com.wangzan101.mika.ui.ExchangeStatus.Cancelled }?.summary
        }.also {
            note("stop summary: $it")
            compose.waitForIdle()
            mainShot(dir, "stopped-summary")
        } else null
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        // liveSettleSec: 終了判定と引き継ぎ後の学習が完了したら、学習内容をLearned.txtに書き込みます
        val settleMs = (args.getString("liveSettleSec")?.toLong() ?: 0) * 1000
        if (settleMs > 0) {
            val settled = DeviceSupport.pollUntil(settleMs) { true.takeIf { learningJob.children.count { it.isActive } <= resident } } != null
            note("learning settled=$settled")
            File(dir, "learned.txt").writeText(learned(memory))
        }
        val keepSec = args.getString("liveKeepCheckSec")?.toLongOrNull() ?: 0
        val kept = if (keepSec > 0) keptPageCheck(keepSec, dir, ::note) else ""
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\npauses=$pausesHandled\nwallSec=$wall\ninputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "answer=$answer\n" + (summary?.let { "summary=$it\n" } ?: "") + kept,
        )
    }

    /**
     * タスクがまだ実行されている後、ページがバックグラウンドで残されます。ページ内のメディア再生の進行状況を [seconds] 秒ごとに 2 回読み取り、カウント専用タイマー (この承認のためにのみページに配置されます)。
     * はページ自体には触れず、その表示の画像を保存します。 result.txt に書き込まれた行を返します。
     */
    private fun keptPageCheck(seconds: Long, dir: File, note: (String) -> Unit): String {
        val keeper = (context.applicationContext as MikaApp).pageKeeper
        val page = keeper.page.value
        val tab = keeper.keptTab()
        if (page == null || tab == null) {
            note("no kept page after the run")
            return "keptPage=none\n"
        }
        val probe = "(function(){if(!window.__keepCheck){window.__keepCheck=0;setInterval(function(){window.__keepCheck++},200);}" +
            "return JSON.stringify({timer:window.__keepCheck,media:Array.from(document.querySelectorAll('video,audio')).map(function(m){return {t:m.currentTime,paused:m.paused}})});})()"
        fun read(): org.json.JSONObject? {
            val latch = java.util.concurrent.CountDownLatch(1)
            var raw: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (tab.destroyed) latch.countDown() else tab.view.evaluateJavascript(probe) { raw = it; latch.countDown() }
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return raw?.let { r -> runCatching { org.json.JSONObject(org.json.JSONTokener(r).nextValue() as String) }.getOrNull() }
        }
        val first = read()
        Thread.sleep(seconds * 1000)
        val second = read()
        note("kept page ${page.host}: before=$first after=$second")
        fun times(o: org.json.JSONObject?) = o?.optJSONArray("media")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optDouble("t") } }.orEmpty()
        val before = times(first)
        val after = times(second)
        val mediaAdvanced = after.indices.any { i -> before.getOrNull(i)?.let { after[i] > it } == true }
        val timerAdvanced = (second?.optInt("timer") ?: 0) > (first?.optInt("timer") ?: 0)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { keeper.frames().filterNotNull().first() } }?.let { bmp ->
            File(dir, "kept-frame.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val notified = mikaNotifications().contains(appString(R.string.kept_notif_title))
        // WebView はメインスレッドでのみ問い合わせることができます
        var keptUrl: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { keptUrl = if (tab.destroyed) null else tab.view.url }
        return "keptPage=${page.host}\nkeptUrl=$keptUrl\nkeptMedia=${before.size}\nkeptMediaAdvanced=$mediaAdvanced\n" +
            "keptTimerAdvanced=$timerAdvanced\nkeptPageAlive=${!tab.destroyed && keeper.page.value?.id == page.id}\nkeptNotification=$notified\n"
    }

    @Test
    fun runPageAsk() {
        val url = args.getString("liveUrl")
        val goal = args.getString("liveGoal")
        assumeTrue(url != null && goal != null)
        val run = args.getString("liveRun") ?: "page"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val touchAt = args.getString("liveTouchAtStep")?.toInt() ?: -1
        val resumeAfterMs = (args.getString("liveResumeAfterSec")?.toLong() ?: 5) * 1000
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")
        val instr = InstrumentationRegistry.getInstrumentation()
        val app = context.applicationContext as MikaApp
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} url=$url goal=$goal")

        // サイドバーからブラウザに入り、URL を開いて、ページが読み込まれるまで待ちます。
        compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performClick()
        compose.awaitOne(appString(R.string.drawer_browser)).performClick()
        compose.waitForIdle()
        val browser = UserBrowser.of(context)
        compose.runOnUiThread { browser.open(url!!)?.let { error("cannot open $url: $it") } }
        fun loaded() = DeviceSupport.pollUntil(30_000) { browser.page.value?.takeIf { !it.loading && it.progress >= 100 } } != null
        note("page loaded=${loaded()}")
        if (args.getString("liveDesktop") == "true") {
            compose.runOnUiThread { browser.setDesktop(true) }
            Thread.sleep(1000)
            note("desktop page loaded=${loaded()}")
        }
        Thread.sleep(1500)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page-ask-bar").fetchSemanticsNodes().isNotEmpty() }
        if (args.getString("liveNew") == "true" && compose.onAllNodesWithTag("page-ask-fresh").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("page-ask-fresh").performClick()
        }
        mainShot(dir, "step-00")
        val before = DeviceTaskState.runs.value.toSet()
        compose.onNodeWithTag("page-ask-field").performTextInput(goal!!)
        compose.onNodeWithTag("page-ask-send").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val task = DeviceSupport.pollUntil(15_000) {
            DeviceTaskState.runs.value.firstOrNull { it !in before && AttachedPage.parse(it.goal).words == goal.trim() }
        } ?: error("the page question did not start a task: runs=${DeviceTaskState.runs.value.map { it.goal.take(60) }}")
        val conversationId = task.conversationId
        note("task ${task.key} conversation=$conversationId")
        val t0 = System.currentTimeMillis()

        var lastStep = -1
        var last: TaskState? = null
        var borrowed = false
        var pauses = 0
        var userPauses = 0
        var takeovers = 0
        var asked = false
        var touched = false
        var pausedByTouch = false
        var resumed = false
        var end: TaskState? = null
        // は、ステータス ストリームに表示されるすべての計算を一時停止します (ポーリングでは短い一時停止が見逃され、再開する前にタッチしてテストするまでの期間も見逃されます)。
        val seen = java.util.Collections.synchronizedList(mutableListOf<TaskState>())
        val watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            var prev: TaskState? = null
            task.state.collect { st ->
                if (st is TaskState.Held && prev !is TaskState.Held) seen += st
                prev = st
            }
        }
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val s = task.state.value
            if (browser.loans.lentTo.value == conversationId) borrowed = true
            if (s != last) {
                note("state $s")
                if (s is TaskState.WaitingInput) asked = true
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                compose.mainClock.advanceTimeBy(250)
                mainShot(dir, "step-%02d".format(s.step))
            }
            if (touchAt > 0 && !touched && s is TaskState.Running && s.step >= touchAt) {
                touched = true
                note("finger swipe on the page at step ${s.step}: ${swipeOnPage()}")
                pausedByTouch = DeviceSupport.pollUntil(3_000) { true.takeIf { PageAskModel.pausedForUser(task.state.value) } } != null
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                val banner = compose.onAllNodesWithTag("page-paused-banner").fetchSemanticsNodes().isNotEmpty()
                val card = compose.onAllNodesWithTag("page-card").fetchSemanticsNodes().isNotEmpty()
                note("paused by touch=$pausedByTouch banner=$banner card=$card state=${task.state.value}")
                mainShot(dir, "paused")
                Thread.sleep(resumeAfterMs)
                note("still paused after ${resumeAfterMs / 1000}s: ${PageAskModel.pausedForUser(task.state.value)}")
                if (banner) compose.onNodeWithTag("page-paused-resume").performClick() else TaskService.resume(context, task.key)
                resumed = DeviceSupport.pollUntil(5_000) { true.takeIf { !PageAskModel.pausedForUser(task.state.value) } } != null
                note("resumed=$resumed state=${task.state.value}")
                compose.waitForIdle()
                mainShot(dir, "resumed")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                mainShot(dir, "takeover")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context, runKey = task.key) else TaskService.cancel(context, task.key)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                break
            }
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null) {
            note("timeout, cancelling")
            TaskService.cancel(context, task.key)
        }
        Thread.sleep(1500)
        watch.cancel()
        synchronized(seen) {
            pauses = seen.size
            userPauses = seen.count(PageAskModel::pausedForUser)
            note("pauses: ${seen.joinToString { (it as TaskState.Held).reason.why }}")
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        mainShot(dir, "end")
        val userTabUrl = browser.page.value?.url
        val origin = app.conversations.pageOrigin(conversationId)
        val urls = recorder.toolCalls.filter { it.first == "web_navigate" }.mapNotNull { Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(it.second)?.groupValues?.get(1) }
        val reopened = urls.any { PageConversations.pageKey(it) == PageConversations.pageKey(url!!) }
        // 会話ページに戻ってサイドバーを開きます。この会話には小さなラベル「Web ページ」が付いています。まず、通知バーとフローティング通知を非表示にします (通知バーを覆ってもサイドバーは切れません)。
        // インターフェイス テストのクロックは、サイドバーからスライドアウトするアニメーションが完了するまで押し続ける必要があります。
        fun menuShown() = compose.onAllNodesWithContentDescription(appString(R.string.chat_menu)).fetchSemanticsNodes().isNotEmpty()
        for (i in 0 until 8) {
            if (menuShown()) break
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
        }
        var listedAsWeb = false
        for (attempt in 1..3) {
            sh("cmd statusbar collapse")
            Thread.sleep(1500)
            runCatching { compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performSemanticsAction(SemanticsActions.OnClick) }
                .onFailure { note("menu not clickable: $it") }
            repeat(8) {
                compose.mainClock.advanceTimeBy(250)
                Thread.sleep(150)
            }
            compose.waitForIdle()
            listedAsWeb = DeviceSupport.pollUntil(5_000) {
                compose.mainClock.advanceTimeBy(250)
                true.takeIf { compose.onAllNodesWithTag("conversation-web", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            } != null
            val drawerOpen = compose.onAllNodesWithTag("drawer").fetchSemanticsNodes().isNotEmpty()
            note("drawer attempt $attempt: open=$drawerOpen webTag=$listedAsWeb")
            if (drawerOpen) break
        }
        mainShot(dir, "drawer")
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\nurl=$url\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\nasked=$asked\npauses=$pauses\nuserPauses=$userPauses\nwallSec=$wall\n" +
                "inputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "borrowed=$borrowed\nfirstToolCalls=${recorder.toolCalls.take(5).joinToString(" ; ") { "${it.first} ${it.second.take(120)}" }}\n" +
                "urlsOpened=${urls.joinToString(" ")}\nreopenedSameUrl=$reopened\nuserTabUrlAtEnd=$userTabUrl\n" +
                "touchAtStep=$touchAt\npausedByTouch=$pausedByTouch\nresumed=$resumed\n" +
                "conversationId=$conversationId\npageOrigin=${origin?.let { "${it.host} ${it.url} ${it.title}" }}\nlistedAsWeb=$listedAsWeb\n" +
                "answer=$answer\n",
        )
        // ミカ自身のクリックとスクロールによって、タスクがユーザーが操作していると認識させてはなりません
        if (touchAt <= 0) check(userPauses == 0) { "the run paused as if the user touched the page ($userPauses times) though nobody did" }
        else check(pausedByTouch && resumed) { "touch at step $touchAt: paused=$pausedByTouch resumed=$resumed" }
    }

    /** ブラウザ ページで、指を使ってウィンドウの短いセクションを上にスワイプします (Web ページの上半分をパネルで覆うことはできません。スワイプしてもリンクはクリックされません)。スワイプした場所に戻ります。 */
    private fun swipeOnPage(): String {
        val instr = InstrumentationRegistry.getInstrumentation()
        var at: IntArray? = null
        var size = 0 to 0
        instr.runOnMainSync {
            fun find(v: android.view.View): android.webkit.WebView? = when (v) {
                is android.webkit.WebView -> v.takeIf { it.isShown }
                is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            find(compose.activity.window.decorView)?.let { w ->
                at = IntArray(2).also(w::getLocationOnScreen)
                size = w.width to w.height
            }
        }
        val o = at ?: return "no page view"
        val x = o[0] + size.first / 2f
        val y0 = o[1] + size.second * 0.3f
        val down = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, t: Long) {
            val e = android.view.MotionEvent.obtain(down, t, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            instr.sendPointerSync(e)
            e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, y0, down)
        for (i in 1..5) send(android.view.MotionEvent.ACTION_MOVE, y0 - i * 12f, down + i * 16L)
        send(android.view.MotionEvent.ACTION_UP, y0 - 60f, down + 100)
        return "x=$x y=$y0"
    }

    /** 現時点で学んだすべて: 個人スキル (使用中およびドラフト中)、アプリの知識のさまざまな補足、および記憶されたルートの数。 */
    private fun learned(memory: MemoryRuntime): String = buildString {
        val skills = memory.skills()
        skills.list().forEach { appendLine("skill ${it.name} status=${it.status} outcomes=${it.outcomes}\n${it.markdown}\n") }
        skills.drafts().forEach { (id, s) -> appendLine("skill-draft $id ${s.name}\n${s.markdown}\n") }
        memory.appKnowledge().all().forEach { appendLine("app ${it.appName} stage=${it.stage} lesson=${it.lesson} revises=${it.revises} trials=${it.trialOutcomes}\n${it.markdown}\n") }
        appendLine("routes=${(context.applicationContext as MikaApp).routes.count()}")
    }

    /** Mito 現在の通知のタイトル、テキスト、ボタン (dumpsys 通知から抜粋)。 */
    private fun mikaNotifications(): String = sh("dumpsys notification --noredact")
        .split("NotificationRecord(").drop(1).filter { "pkg=com.wangzan101.mika " in it }
        .joinToString(" || ") { rec ->
            rec.lines().map { it.trim() }
                .filter { it.startsWith("android.title=") || it.startsWith("android.text=") || it.startsWith("[") && "\"" in it }
                .joinToString(" | ")
        }.take(1500)

    private fun frame(dir: File, name: String) {
        val bmp = DeviceTaskState.frames.value ?: return
        runCatching { File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun mainShot(dir: File, name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 80, it) }
    }

    /** は記録のみで変更はありません。実際のモデルの各応答と前のステップのツール結果がログに書き込まれます。 */
    private class Recorder(private val note: (String) -> Unit) {
        @Volatile var calls = 0
        @Volatile var inTokens = 0
        @Volatile var outTokens = 0
        /** モデル調整後のツール（名前、パラメータ）の順。 */
        val toolCalls: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

        fun wrap(inner: ChatModel) = object : ChatModel {
            override val capabilities = inner.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                messages.lastOrNull()?.parts?.filterIsInstance<Part.ToolResult>()?.forEach {
                    note("  result${if (it.isError) " ERROR" else ""}: ${it.content.take(400).replace('\n', ' ')}")
                }
                messages.lastOrNull()?.parts?.filterIsInstance<Part.Text>()?.forEach {
                    note("  user/tool text: ${it.text.take(300).replace('\n', ' ')}")
                }
                val t = System.currentTimeMillis()
                val reply = try {
                    inner.complete(system, messages, tools)
                } catch (e: Exception) {
                    note("model error: $e")
                    throw e
                }
                calls++
                reply.usage?.let { inTokens += it.inputTokens; outTokens += it.outputTokens }
                note("call#$calls ${System.currentTimeMillis() - t}ms stop=${reply.stopReason}")
                reply.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> note("  say: ${p.text.take(500).replace('\n', ' ')}")
                        is Part.ToolCall -> {
                            toolCalls += p.name to p.argsJson
                            note("  tool: ${p.name} ${p.argsJson.take(300)}")
                        }
                        is Part.Thinking -> note("  think: ${p.text.take(200).replace('\n', ' ')}")
                        else -> Unit
                    }
                }
                return reply
            }
        }
    }
}
