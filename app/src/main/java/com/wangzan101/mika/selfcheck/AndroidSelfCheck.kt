package com.wangzan101.mika.selfcheck

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.FrameMath
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.RootShell
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebScreen
import com.wangzan101.mika.cap.web.WebTabPool
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckOutcome
import com.wangzan101.mika.core.selfcheck.DeviceFacts
import com.wangzan101.mika.core.selfcheck.SelfCheck
import com.wangzan101.mika.core.selfcheck.SelfCheckPort
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.ui.PhoneSetupCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 互換性セルフテストの Android 実装。携帯電話上のこれらのタスクは、プロセス内で共有されるアシスタント接続 ([PhoneServices.sharedShell]) という同じセットを使用します。
 * [AgentDisplay] ビルド画面、[AndroidScreen] ページを開き、画面を読み、クリックし、入力し、スクロールし、戻ります。 Web ページは組み込みブラウザ [WebScreen] を使用します。
 * サンドボックスウォーク [AndroidSandbox]。ミカ自身のセルフテスト ページとシステム設定のみをタッチし (開くだけ、クリックしないでください)、設定を変更せず、インターネットにも接続しないでください。
 * レポートの事実にはステータスと数値のみが含まれており、他のアプリのパッケージ名、アカウント、ユーザーのコンテンツは含まれていません。
 * [status] は、インターフェイス上の現在のシステム ステータス (アクセシビリティ、通知、組み込みアシスタント) です。
 */
class AndroidSelfCheck(context: Context, private val status: () -> SystemStatus) : SelfCheckPort {
    private val app = context.applicationContext
    private val phone = PhoneServices.of(app)

    @Volatile private var connection: ShellBinding? = null
    @Volatile private var shell: IMikaShell? = null
    @Volatile private var display: AgentDisplay? = null
    @Volatile private var screen: AndroidScreen? = null
    @Volatile private var web: Web? = null

    /** は、実行開始時のメイン画面の一番上のタスクです。ノードに到達するたびにそれをもう一度見て、変更されたノードを書き留めます。 */
    private var mainBefore: Int? = null
    private val mainChanged = mutableListOf<String>()

    private class Web(val host: WebHost, val pool: WebTabPool, val screen: WebScreen) {
        fun close() {
            runCatching { pool.closeAll(TASK) }
            runCatching { pool.close() }
            runCatching { host.close() }
        }
    }

    override suspend fun facts(): DeviceFacts = withContext(Dispatchers.IO) {
        val mem = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val m = metrics()
        val st = status()
        val devPageMissing = DeveloperSettings.developerOptionsPageMissing(app.packageManager)
        val wirelessMissing = DeveloperSettings.wirelessDebuggingMissing(app.contentResolver)
        DeviceFacts(
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
            mem.totalMem, m.widthPixels, m.heightPixels,
            wirelessDebugging = !wirelessMissing,
            appVersion = BuildConfig.VERSION_NAME, appBuild = BuildConfig.GIT_HASH,
            developerOptionsPage = !devPageMissing,
            phoneControlUnavailable = PhoneSetupFlow.unavailable(
                devPageMissing, wirelessMissing, st.builtinShell, st.helperRestart, rootAvailable = RootShell.su() != null,
            ),
        )
    }

    override suspend fun check(check: SelfCheck): CheckOutcome = when (check) {
        SelfCheck.DEVICE -> error("facts() の情報から判定します")
        SelfCheck.PERMISSIONS -> permissions()
        SelfCheck.HELPER -> helper()
        SelfCheck.VIRTUAL_DISPLAY -> virtualDisplay()
        SelfCheck.APP_LAUNCH -> appLaunch()
        SelfCheck.SCREEN_READING -> screenReading()
        SelfCheck.ACTIONS -> actions()
        SelfCheck.STAYS_ALIVE -> staysAlive()
        SelfCheck.MAIN_SCREEN -> mainScreen()
        SelfCheck.BROWSER -> browser()
        SelfCheck.SANDBOX -> sandbox()
        SelfCheck.APP_COPIES -> appCopies()
    }

    // ---------- 2 権限 ----------

    private fun permissions(): CheckOutcome {
        val s = status()
        val battery = runCatching { app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName) }.getOrNull()
        val detail = "ユーザー補助: " + when (s.accessibility) {
            AccessibilityState.On -> "有効（接続済み）"
            AccessibilityState.EnabledNotRunning -> "有効（未接続）"
            AccessibilityState.Off -> "無効"
        } + "、通知: " + (if (s.notificationsAllowed) "許可済み" else "未許可") +
            "、バッテリー制限: " + when (battery) { true -> "なし"; false -> "あり"; null -> "確認できません" }
        return when {
            s.accessibility == AccessibilityState.Off -> CheckOutcome.fail(detail, Advice.TurnOnAccessibility)
            s.accessibility == AccessibilityState.EnabledNotRunning -> CheckOutcome.fail(detail, Advice.RestartAccessibility)
            !s.notificationsAllowed -> CheckOutcome.partial(detail, Advice.AllowNotifications)
            battery == false -> CheckOutcome.partial(detail, Advice.AllowBattery)
            else -> CheckOutcome.pass(detail)
        }
    }

    // ---------- 3 内蔵アシスタント ----------

    private suspend fun helper(): CheckOutcome {
        val st = status().builtinShell
        // ペアリングされていない場合は接続されません (接続時にセットアップを求めるメッセージが表示されます)。ペアリングされている場合は、ウォッチドッグが起動している可能性がありますので、しばらくお待ちください。
        if (st == BuiltinShellStatus.NotPaired && !phone.shellControl.paired) return CheckOutcome.fail("スマートフォン操作が未設定です（${PhoneSetupCopy.statusLine(st)}）", Advice.SetUpPhoneControl)
        val conn = connection ?: phone.sharedShell.acquire().also { connection = it }
        val sh = withTimeoutOrNull(HELPER_WAIT_MS) { conn.shell.filterNotNull().first() }
            ?: return CheckOutcome.fail("操作アシスタントが動作していません（${PhoneSetupCopy.statusLine(phone.shellControl.status.value)}）", Advice.SetUpPhoneControl)
        shell = sh
        return withContext(Dispatchers.IO) {
            val uid = sh.identity().getOrNull(0) ?: -1
            val path = when {
                uid == ROOT_UID -> "root 経由で起動"
                phone.shellControl.paired -> "ペア設定済みのワイヤレスデバッグで起動"
                else -> "パソコンから起動（USB）"
            }
            val who = when (uid) { SHELL_UID -> "shell"; ROOT_UID -> "root"; else -> "uid $uid" }
            val times = List(LATENCY_CALLS) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                sh.identity()
                (SystemClock.elapsedRealtimeNanos() - t0) / 1_000
            }.sorted()
            mainBefore = mainTop(sh)
            CheckOutcome.pass("実行権限: $who、$path、往復 ${format(times[times.size / 2] / 1000.0)} ミリ秒")
        }
    }

    // ---------- 4 つの仮想画面 ----------

    private suspend fun virtualDisplay(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("操作アシスタントに接続できません", Advice.SetUpPhoneControl)
        val m = metrics()
        // 画面構築プロセスを中断しないでください。完成した画面を書き留めて、最後に解放してください。
        val made = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { AgentDisplay(sh, m.widthPixels, m.heightPixels, m.densityDpi, app.getSystemService(DisplayManager::class.java)) }.onSuccess { display = it }
        }
        val d = made.getOrElse { return CheckOutcome.fail("仮想画面を作成できませんでした（${it.javaClass.simpleName}）", Advice.DisplayFailed) }
        val flags = runCatching { app.getSystemService(DisplayManager::class.java).getDisplay(d.displayId)?.flags }.getOrNull()
        val own = flags?.let { it and ownFocusFlag() != 0 }
        val detail = "${d.width}x${d.height} の画面を作成、独立したフォーカス: " + when (own) { true -> "あり"; false -> "なし"; null -> "確認できません" }
        return when {
            Build.VERSION.SDK_INT < DeviceFacts.OWN_FOCUS_SDK -> CheckOutcome.partial("$detail（独立したフォーカスには Android 14 以降が必要です）", Advice.FocusShared)
            own == false -> CheckOutcome.partial(detail, Advice.FocusShared)
            else -> CheckOutcome.pass(detail)
        }
    }

    /** Android 14 以降に表示される「独自のフォーカス」サイン。定数はパブリックではないため、最初に名前で取得できますが、元の値を取得することはできません。 */
    private fun ownFocusFlag(): Int = runCatching { Display::class.java.getField("FLAG_OWN_FOCUS").getInt(null) }.getOrDefault(1 shl 11)

    // ---------- 5 ページを開く ----------

    private suspend fun appLaunch(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("操作アシスタントに接続できません", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("仮想画面がありません", Advice.DisplayFailed)
        val a11y = ScreenAccessibility.instance.value ?: return CheckOutcome.fail("ユーザー補助サービスに接続できません", Advice.RestartAccessibility)
        val s = screen ?: AndroidScreen(a11y, sh, LazyAgentDisplay(d.width, d.height) { d }.also { it.get() }).also { screen = it }
        val own = s.openOwnPage(SelfCheckTargetActivity.intent(app))
        if (own !is ActionResult.Ok || topPackage(sh, d.displayId) != app.packageName) {
            return CheckOutcome.fail("Mika のセルフテストページを仮想画面で開けませんでした（${reason(own)}）", Advice.LaunchFailed)
        }
        sampleMain(sh, "test page")
        // まずセルフテスト ページを閉じてから、システム設定を開きます。次に、古いページを先頭に戻すのではなく、最初からページを開きます。
        withContext(Dispatchers.Main) { SelfCheckTargetActivity.finishOpen() }
        val settings = SystemPickers.settingsPackage(app.packageManager)
        val settingsNote = if (settings == null) "システムの設定アプリが見つかりません" else when (val r = s.openApp(settings, cold = false)) {
            is ActionResult.Failed -> if (r.reason == "app_in_use_by_user") SETTINGS_IN_USE else "設定アプリを開けませんでした（${r.reason}）"
            is ActionResult.Ok -> {
                val onMika = withContext(Dispatchers.IO) { topPackage(sh, d.displayId) } == settings
                val onMain = withContext(Dispatchers.IO) { mainTop(sh)?.let { runCatching { sh.packageOfTask(it) }.getOrNull() } } == settings
                sampleMain(sh, "Settings")
                // ただ見てください: 一度戻ってそのままにしてください
                s.back()
                when {
                    onMain -> SETTINGS_ON_MAIN
                    !onMika -> "設定アプリが Mika の仮想画面の最前面に表示されませんでした"
                    else -> null
                }
            }
        }
        // セルフテスト ページで次の項目を実行する必要があります。一度ページを再度開き、最初から開始します。
        val again = s.openOwnPage(SelfCheckTargetActivity.intent(app))
        if (again !is ActionResult.Ok || !poll { withContext(Dispatchers.IO) { isTestPageOnTop(sh, d.displayId) } }) {
            return CheckOutcome.fail("設定アプリを閉じた後、Mika のセルフテストページを開き直せませんでした（${reason(again)}）", Advice.LaunchFailed)
        }
        return when (settingsNote) {
            null -> CheckOutcome.pass("Mika のセルフテストページと設定アプリを仮想画面で開けました")
            SETTINGS_ON_MAIN -> CheckOutcome.fail("セルフテストページの起動は正常。$settingsNote", Advice.OpenedOnMainScreen)
            SETTINGS_IN_USE -> CheckOutcome.partial("セルフテストページの起動は正常。$settingsNote", Advice.SettingsInUse)
            else -> CheckOutcome.partial("セルフテストページの起動は正常。$settingsNote", Advice.LaunchFailed)
        }
    }

    // ---------- 6 画面の見方 ----------

    private suspend fun screenReading(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("画面を読み取る準備ができていません", Advice.LaunchFailed)
        val d = display ?: return CheckOutcome.fail("仮想画面がありません", Advice.DisplayFailed)
        s.waitStable(SETTLE_MS)
        // ページが最初に開かれたとき、ツリーはまだ完成していない可能性があります。Mika が最上部に表示され、必要なコントロールがすべて揃うまで待ちます。せいぜいしばらくお待ちください。
        var snap = s.snapshot()
        poll(PAGE_WAIT_MS) {
            snap = s.snapshot()
            snap.packageName == app.packageName && EXPECTED.all { snap.find(it) != null }
        }
        val missing = EXPECTED.filter { snap.find(it) == null }
        if (snap.packageName != app.packageName || missing.isNotEmpty()) {
            return CheckOutcome.fail("見つからない要素: ${missing.joinToString().ifEmpty { "なし" }}。${seen(snap)}", Advice.ReadFailed)
        }
        val frame = awaitFrame(d) ?: return CheckOutcome.fail("要素ツリーは読み取れましたが、仮想画面のスクリーンショットを取得できません", Advice.BlankFrames)
        val blank = try {
            val grid = FrameMath.lumaGrid(frame.width, frame.height, frame::getPixel)
            FrameMath.isBlack(frame.width, frame.height, frame::getPixel) || grid.max() - grid.min() < BLANK_SPREAD
        } finally {
            frame.recycle()
        }
        if (blank) return CheckOutcome.fail("要素ツリーは読み取れましたが、スクリーンショットが空白か真っ黒です", Advice.BlankFrames)
        return CheckOutcome.pass("${snap.elements.size} 個の要素を読み取り、必要な要素をすべて確認しました。スクリーンショットは空白ではありません")
    }

    // ---------- 7 アクション ----------

    private suspend fun actions(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("画面を読み取る準備ができていません", Advice.LaunchFailed)
        val sh = shell ?: return CheckOutcome.fail("操作アシスタントに接続できません", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("仮想画面がありません", Advice.DisplayFailed)
        val ok = mutableListOf<String>()
        val bad = mutableListOf<String>()
        fun mark(name: String, passed: Boolean) { if (passed) ok += name else bad += name }

        // クリック: プラス 1 を数えます
        var snap = s.snapshot()
        val before = snap.find(COUNT)?.number()
        val button = snap.find(BUTTON)
        if (button != null && before != null) {
            s.tapRef(button.ref, snap.snapshotId)
            mark("tap", poll { s.peek().find(COUNT)?.number() == before + 1 })
        } else mark("tap", false)

        // 入力: 英語と中国語で 1 回ずつ入力し、入力ボックスを読み返します。
        snap = s.snapshot()
        mark("type ASCII", snap.find(INPUT)?.let { typed(s, it, snap, ASCII) } == true)
        snap = s.snapshot()
        mark("type Japanese", snap.find(INPUT)?.let { typed(s, it, snap, JAPANESE) } == true)

        // スクロール: 表示される行が変更されました
        snap = s.snapshot()
        val rowsBefore = snap.rows()
        val list = snap.find(LIST)
        if (list != null && rowsBefore.isNotEmpty()) {
            s.scroll(list.ref, snap.snapshotId, down = true)
            mark("scroll", poll { s.peek().rows().let { it.isNotEmpty() && it.first() != rowsBefore.first() } })
        } else mark("scroll", false)

        // 戻り: セルフテスト ページがミカ画面の上部から残ります。ほとんどの場合、入力後は入力メソッドがまだ再生されています。これらの数回のクリックは入力メソッドを閉じるためのものであり (これは Android の本来のケースです)、回数にはカウントされません。
        val gone = BackPresses.until(
            tries = BACK_TRIES,
            maxPresses = BACK_MAX_PRESSES,
            keyboardUp = { withContext(Dispatchers.IO) { s.keyboardUp() } },
            press = { s.back() },
            left = { poll { withContext(Dispatchers.IO) { !isTestPageOnTop(sh, d.displayId) } } },
        )
        mark("back", gone)
        sampleMain(sh, "actions")

        fun label(name: String): String = when (name) {
            "tap" -> "タップ"
            "type ASCII" -> "英数字の入力"
            "type Japanese" -> "日本語の入力"
            "scroll" -> "スクロール"
            "back" -> "戻る操作"
            else -> name
        }
        val detail = (ok.map { "${label(it)}: 成功" } + bad.map { "${label(it)}: 失敗" }).joinToString("、")
        return when {
            bad.isEmpty() -> CheckOutcome.pass(detail)
            bad == listOf("type Japanese") -> CheckOutcome.partial(detail, Advice.ActionFailed)
            else -> CheckOutcome.fail(detail, Advice.ActionFailed)
        }
    }

    private suspend fun typed(s: AndroidScreen, field: UiElement, snap: ScreenSnapshot, text: String): Boolean {
        s.clearText(field.ref, snap.snapshotId)
        val fresh = s.snapshot()
        val f = fresh.find(INPUT) ?: return false
        s.typeText(f.ref, fresh.snapshotId, text)
        return poll { s.peek().find(INPUT)?.name?.let { SelfCheckText.contains(it, text) } == true }
    }

    // ---------- 8 動き続けてください ----------

    private suspend fun staysAlive(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("画面を読み取る準備ができていません", Advice.LaunchFailed)
        val d = display ?: return CheckOutcome.fail("仮想画面がありません", Advice.DisplayFailed)
        if (s.openOwnPage(SelfCheckTargetActivity.intent(app)) !is ActionResult.Ok) {
            return CheckOutcome.fail("Mika のセルフテストページを開き直せませんでした", Advice.LaunchFailed)
        }
        val start = SystemClock.uptimeMillis()
        var lastChange = start
        var prev = frameHash(d)
        var lastTick = s.peek().find(TICK)?.number()
        var treeLostAt: Long? = null
        while (SystemClock.uptimeMillis() - start < ALIVE_MS) {
            delay(ALIVE_STEP_MS)
            val now = SystemClock.uptimeMillis()
            val h = frameHash(d)
            if (h != null && h != prev) lastChange = now
            prev = h
            if (now - lastChange > STALL_MS) {
                return CheckOutcome.fail("映像の更新が ${(lastChange - start + 500) / 1000} 秒後に停止しました", Advice.FramesStopped)
            }
            val tick = s.peek().find(TICK)?.number()
            if (tick == null || tick == lastTick) {
                if (treeLostAt == null) treeLostAt = now
            } else treeLostAt = null
            lastTick = tick
            treeLostAt?.let { lost ->
                if (now - lost > STALL_MS) return CheckOutcome.fail("要素ツリーの更新が ${(lost - start + 500) / 1000} 秒後に停止しました", Advice.FramesStopped)
            }
        }
        return CheckOutcome.pass("映像と要素ツリーが ${ALIVE_MS / 1000} 秒間、更新され続けました")
    }

    // ---------- 9 ホーム画面 ----------

    private suspend fun mainScreen(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("操作アシスタントに接続できません", Advice.SetUpPhoneControl)
        val before = mainBefore ?: return CheckOutcome.fail("開始時にメイン画面を読み取れませんでした", Advice.Error)
        sampleMain(sh, "end")
        val who = if (withContext(Dispatchers.IO) { runCatching { sh.packageOfTask(before) }.getOrNull() } == app.packageName) "Mika" else "別のアプリ"
        return if (mainChanged.isEmpty()) CheckOutcome.pass("最前面のタスクは変わりませんでした（$who が最前面のまま）")
        else CheckOutcome.fail("最前面のタスクが変わった時点: ${mainChanged.joinToString { stageLabel(it) }}", Advice.MainScreenChanged)
    }

    // ---------- 10 内蔵ブラウザ ----------

    private suspend fun browser(): CheckOutcome {
        if (runCatching { WebViewCompat.getCurrentWebViewPackage(app) == null }.getOrDefault(true)) return CheckOutcome.fail("この端末には WebView がありません", Advice.NoWebView)
        val m = metrics()
        // はタスクと同様にメインスレッドに組み込まれます。構築された内容を必ず書き留めて、最後に閉じてください。訪問は Web サイトのリストに記録されません (レジストリには記録されません)。
        val built = withContext(NonCancellable + Dispatchers.Main) {
            runCatching {
                val host = WebHost(app, m.widthPixels, m.heightPixels, m.densityDpi)
                try {
                    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()
                    val pool = WebTabPool(app, host, assetLoaders = listOf(loader))
                    Web(host, pool, WebScreen(pool, TASK, host, SystemClock::uptimeMillis)).also { web = it }
                } catch (e: Exception) {
                    host.close()
                    throw e
                }
            }
        }
        val w = built.getOrElse { return CheckOutcome.fail("ブラウザーを起動できませんでした（${it.javaClass.simpleName}）", Advice.WebFailed) }
        val nav = w.screen.navigate(WEB_PAGE, newTab = false)
        if (!nav.loaded) return CheckOutcome.fail("ローカルのテストページを読み込めませんでした（${nav.netError ?: "未読み込み"}）", Advice.WebFailed)
        val read = w.screen.readPage(MAX_CHARS, 0, scrollLazy = false).text
        if (!SelfCheckText.contains(read, WEB_MARKER)) return CheckOutcome.fail("ページは読み込めましたが、本文を読み取れませんでした", Advice.WebFailed)
        val snap = w.screen.snapshot()
        val button = snap.elements.firstOrNull { it.resourceId == WEB_BUTTON }
            ?: return CheckOutcome.fail("本文は読み取れましたが、ボタンが見つかりませんでした", Advice.WebFailed)
        w.screen.tapRef(button.ref, snap.snapshotId)
        val clicked = poll { SelfCheckText.contains(w.screen.readPage(MAX_CHARS, 0, scrollLazy = false).text, WEB_CLICKED) }
        return if (clicked) CheckOutcome.pass("ローカルのテストページを読み込み、本文の読み取りとボタンのタップを確認しました")
        else CheckOutcome.fail("本文は読み取れましたが、ボタンをタップしてもページが変わりませんでした", Advice.WebFailed)
    }

    // ---------- 11 サンドボックス ----------

    private suspend fun sandbox(): CheckOutcome {
        val state = SandboxState.get(app)
        when (val st = state.status.value) {
            is SandboxStatus.Unsupported -> return CheckOutcome.skipped("${Build.SUPPORTED_ABIS.firstOrNull() ?: "この端末"} はサンドボックスに対応していません", Advice.SandboxUnsupported)
            is SandboxStatus.Ready -> Unit
            // はセルフテスト用のオペレーティング環境をインストールしません (数分かかります)
            else -> return CheckOutcome.partial("実行環境の準備ができていません（${st.javaClass.simpleName}）", Advice.SandboxNotPrepared)
        }
        val box = AndroidSandbox(app, state)
        // コマンドにエラーがある場合 (たとえば、ネットワーク フィルターをインストールできずに拒否された場合)、真実に従ってそれを書き留めます。いつものようにキャンセルして捨ててください。
        suspend fun run(argv: List<String>): Result<CommandResult> = try {
            Result.success(box.run(SANDBOX_WORKSPACE, argv, null, COMMAND_MS) {})
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val echo = run(listOf("sh", "-c", "echo ok"))
        val echoOk = echo.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "ok" } == true
        val py = run(listOf("python3", "-c", "print(1+1)"))
        val pyOk = py.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "2" } == true
        fun what(r: Result<CommandResult>) = r.fold(
            { if (it.timedOut) "タイムアウト" else "終了コード ${it.exitCode}、${it.durationMs} ミリ秒" }, { "エラー: ${it.javaClass.simpleName}" },
        )
        val detail = "echo: ${if (echoOk) "成功" else "失敗"}（${what(echo)}）、python3: ${if (pyOk) "成功" else "失敗"}（${what(py)}）"
        return when {
            echoOk && pyOk -> CheckOutcome.pass(detail)
            echoOk -> CheckOutcome.partial(detail, Advice.SandboxFailed)
            else -> CheckOutcome.fail(detail, Advice.SandboxFailed)
        }
    }

    // ---------- 12 別のアプリ ----------

    private suspend fun appCopies(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("操作アシスタントに接続できません", Advice.SetUpPhoneControl)
        val users = withContext(Dispatchers.IO) { runCatching { sh.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS) }.getOrNull() }
            ?: return CheckOutcome.partial("他のユーザープロファイルを読み取れませんでした", Advice.Error)
        return CheckOutcome.pass("利用できる他のプロファイルは ${users.size} 件です")
    }

    // ---------- 最後 ----------

    override suspend fun release() {
        withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                runCatching { SelfCheckTargetActivity.finishOpen() }
                web?.close()
                web = null
            }
            withContext(Dispatchers.IO) {
                display?.release()
                display = null
            }
            screen = null
            shell = null
            connection?.let {
                connection = null
                phone.sharedShell.release()
            }
        }
    }

    // ---------- ガジェット ----------

    private fun metrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    /** ホーム画面の一番上のタスク。読み取れない場合は null になります。 */
    private fun mainTop(sh: IMikaShell): Int? = runCatching { sh.topTaskId(Display.DEFAULT_DISPLAY) }.getOrNull()?.takeIf { it >= 0 }

    private suspend fun sampleMain(sh: IMikaShell, stage: String) {
        val before = mainBefore ?: return
        val now = withContext(Dispatchers.IO) { mainTop(sh) }
        if (now != before && stage !in mainChanged) mainChanged += stage
    }

    private fun topPackage(sh: IMikaShell, displayId: Int): String? = runCatching {
        sh.topTaskId(displayId).takeIf { it >= 0 }?.let(sh::packageOfTask)
    }.getOrNull()

    private fun isTestPageOnTop(sh: IMikaShell, displayId: Int): Boolean =
        runCatching { sh.topActivity(displayId) }.getOrNull()?.contains(SelfCheckTargetActivity::class.java.simpleName) == true

    private suspend fun awaitFrame(d: AgentDisplay): Bitmap? {
        repeat(FRAME_TRIES) {
            d.latestFrameBitmap()?.let { return it }
            delay(POLL_MS)
        }
        return null
    }

    /** リアルタイム画像 (縮小された画像) のフィンガープリント。画像がまだない場合は null です。 */
    private fun frameHash(d: AgentDisplay): Int? {
        val bmp = d.frames.value ?: return null
        return runCatching {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            px.contentHashCode()
        }.getOrNull()
    }

    private suspend fun poll(timeoutMs: Long = VERIFY_MS, block: suspend () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (block()) return true
            delay(POLL_MS)
        }
        return block()
    }

    /**
     * セルフテスト ページのコントロール: 最初にリソース ID を押して (スクリーン リーダーは短い名前を表示し、完全な書き込みの終わりも認識します)、次にページ上のテキストを押します (単語を含むコントロールには、画面を読み取るときにリソース ID がありません)。
     * テキストは、セルフテスト ページと同じアプリ言語で取得されます。比較では大文字と小文字は区別されず、空白は考慮されません ([SelfCheckText] を参照)。
     */
    private fun ScreenSnapshot.find(key: String): UiElement? =
        elements.firstOrNull { e -> e.resourceId?.let { it == key || it.endsWith("/$key") } == true }
            ?: elements.firstOrNull { e ->
                when (key) {
                    TITLE -> SelfCheckText.same(e.name, tr(R.string.selfcheck_target_title))
                    COUNT -> tapsText.matches(e.name)
                    BUTTON -> e.clickable && SelfCheckText.same(e.name, tr(R.string.selfcheck_target_button))
                    INPUT -> e.editable
                    TICK -> tickText.matches(e.name)
                    LIST -> e.scrollable
                    else -> false
                }
            }

    /** セルフテスト ページに線が表示されます (上から下に押します)。 */
    private fun ScreenSnapshot.rows(): List<String> =
        elements.filter { rowText.matches(it.name) }.sortedBy { it.centerY }.map { it.name }

    /**
     * 画面を読んでもセルフテスト ページが見つからない場合は、レポートに「見た内容」を書き込みます。セルフテスト ページの場合は、パッケージ名と最初のいくつかのコントロールを書き込みます。システム インターフェイスの場合は、パッケージ名、番号、コントロールのタイプまたは ID のみを書き込みます
     * (システム設定の上部にユーザー名が書かれている場合がありますが、テキストは書き込まれません);他のアプリはパッケージ名さえ書きません。
     */
    private fun seen(snap: ScreenSnapshot): String {
        val pkg = snap.packageName
        val n = snap.elements.size
        val first = snap.elements.take(SEEN_ELEMENTS)
        return when {
            pkg == app.packageName -> "表示内容: Mika、要素 $n 個: " + first.joinToString { (it.resourceId ?: it.name).take(SEEN_CHARS) }
            pkg == null -> "読み取れるウィンドウがありません（要素 $n 個）"
            systemPackage(pkg) -> "表示内容: $pkg、要素 $n 個: " + first.joinToString { (it.resourceId ?: it.role).take(SEEN_CHARS) }
            else -> "表示内容: 別のアプリ、要素 $n 個"
        }
    }

    private fun systemPackage(pkg: String): Boolean = pkg == SystemPickers.settingsPackage(app.packageManager) || runCatching {
        app.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    /** 数字を含むテキスト (「クリック %d 回」、「タイミング %d」、「行 %d」): アプリ言語に従って取得され、コントロールを識別するために使用されます。 */
    private fun numbered(res: Int) = SelfCheckText.Numbered(tr(res, SENTINEL), SENTINEL.toString())

    private val tapsText by lazy { numbered(R.string.selfcheck_target_taps) }
    private val tickText by lazy { numbered(R.string.selfcheck_target_tick) }
    private val rowText by lazy { numbered(R.string.selfcheck_target_row) }

    private fun UiElement.number(): Int? = Regex("\\d+").findAll(name).lastOrNull()?.value?.toIntOrNull()

    private fun reason(r: ActionResult): String = (r as? ActionResult.Failed)?.reason ?: "最前面にありません"

    private fun stageLabel(stage: String): String = when (stage) {
        "test page" -> "セルフテストページの起動時"
        "Settings" -> "設定アプリの起動時"
        "actions" -> "操作テスト時"
        "end" -> "終了時"
        else -> stage
    }

    private fun format(ms: Double) = String.format(java.util.Locale.ROOT, "%.1f", ms)

    private companion object {
        const val TASK = "self-check"
        const val SANDBOX_WORKSPACE = "self-check"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val HELPER_WAIT_MS = 10_000L
        const val LATENCY_CALLS = 5
        const val SETTLE_MS = 2_000L
        const val VERIFY_MS = 2_000L
        const val POLL_MS = 100L
        const val FRAME_TRIES = 20
        const val BLANK_SPREAD = 8
        const val BACK_TRIES = 2
        /** 入力メソッドを閉じるまでの最大クリック回数はこの回数です。 長い間画像がまったく変化しなかったとしても、 */
        const val BACK_MAX_PRESSES = 5
        const val ALIVE_MS = 15_000L
        const val ALIVE_STEP_MS = 500L
        /** は停止しました (ページ上の数字は 250 ミリ秒ごとに変化しました)。 */
        const val STALL_MS = 3_000L
        const val COMMAND_MS = 8_000L
        const val MAX_CHARS = 4_000
        const val SETTINGS_IN_USE = "設定アプリはメイン画面で使用中のため、新たに開きませんでした"
        const val SETTINGS_ON_MAIN = "設定アプリがメイン画面で開きました"

        const val COUNT = "selfcheck_count"
        const val BUTTON = "selfcheck_button"
        const val INPUT = "selfcheck_input"
        const val TICK = "selfcheck_tick"
        const val LIST = "selfcheck_list"
        const val TITLE = "selfcheck_title"
        val EXPECTED = listOf(TITLE, COUNT, BUTTON, INPUT, TICK, LIST)
        const val ASCII = "Mika 123"
        const val JAPANESE = "こんにちはミカ"
        const val SENTINEL = 987_654_321
        /** セルフテストページが表示されるまでに、画面読み取り項目が表示されるまでに最大でこのくらいの時間がかかります。 */
        const val PAGE_WAIT_MS = 5_000L
        const val SEEN_ELEMENTS = 5
        const val SEEN_CHARS = 24

        const val WEB_PAGE = "https://appassets.androidplatform.net/assets/selfcheck/page.html"
        const val WEB_MARKER = "SELFCHECK-READ-OK"
        const val WEB_BUTTON = "#selfcheck-button"
        const val WEB_CLICKED = "クリック 1"
    }
}
