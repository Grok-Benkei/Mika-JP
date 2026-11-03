package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.ChineseUi
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.task.ParallelCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import org.junit.runner.RunWith
import com.wangzan101.mika.core.cap.phone.OperatingPace

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)

    private fun home(
        status: SystemStatus = ready,
        mainModel: String? = "DeepSeek",
        onProblem: (Problem) -> Unit = {},
        opened: MutableList<String> = mutableListOf(),
        showDeveloper: Boolean = true,
        usageTokens: Long? = 123_000,
    ) {
        compose.setContent {
            SettingsContent(
                status = status, androidRelease = "14", mainModel = mainModel, onBack = {}, onProblem = onProblem,
                onOpenModels = { opened += "models" }, usageTokens = usageTokens, onOpenUsage = { opened += "usage" }, onOpenPhoneOps = { opened += "phone" }, onOpenDuringWork = { opened += "work" },
                capsuleMode = CapsuleMode.DuringTasks, onOpenCapsule = { opened += "capsule" }, onOpenNotifications = { opened += "notifications" },
                onOpenMemory = { opened += "memory" },
                webSitesCount = 3, onOpenWebSites = { opened += "web" },
                sandbox = SandboxStatus.Ready(network = false), onOpenSandbox = { opened += "sandbox" },
                routes = 8, onOpenRoutes = { opened += "routes" }, onOpenRecovery = { opened += "recovery" },
                showDeveloper = showDeveloper, onOpenDeveloper = { opened += "dev" },
                versionName = "0.1.0", onOpenAbout = { opened += "about" },
            )
        }
    }

    /** ページはスクロール コンテンツの列全体です。最初にこの行をビューポートにスクロールしてから、アサートまたはクリックします。 */
    private fun scrollTo(text: String) {
        compose.awaitOne(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun homeShowsGroupsStatusAndValues() {
        home()
        compose.onNode(
            hasText(appString(R.string.all_ready)) and hasAnyAncestor(hasTestTag("settings-status")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_basic)).assertIsDisplayed()
        // ホームページは 1 つの画面に収まりません (「言語」行を追加すると長くなります): 表示する前に、各値をビューポートにスクロールする必要があります。
        // の後には機種、携帯電話の操作、通知、Webサイトへのログイン、操作ルート、コンピューティング環境などが続きます。
        for ((label, value) in listOf(
            R.string.settings_model to "DeepSeek",
            R.string.settings_phone_ops to appString(R.string.pill_available),
            R.string.notifications_title to appString(R.string.notif_normal),
            R.string.settings_web_logins to appPlural(R.plurals.settings_count_sites, 3),
            R.string.settings_routes to appPlural(R.plurals.settings_count_routes, 8),
            R.string.settings_sandbox to appString(R.string.sandbox_ready),
            R.string.settings_about to "0.1.0",
        )) {
            // 「準備完了」は複数の行に現れるため、値とその行の見出しを組み合わせる。
            compose.onNode(hasText(appString(label)) and hasText(value)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun homeRowsOpenTheirPages() {
        val opened = mutableListOf<String>()
        home(opened = opened)
        for (t in listOf(appString(R.string.settings_model), appString(R.string.usage_title), appString(R.string.settings_phone_ops), appString(R.string.settings_during_work), appString(R.string.capsule_settings_title), appString(R.string.notifications_title), appString(R.string.memory_and_skills), appString(R.string.settings_web_logins), appString(R.string.settings_sandbox), appString(R.string.settings_routes), appString(R.string.settings_recovery), appString(R.string.settings_developer), appString(R.string.settings_about))) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf("models", "usage", "phone", "work", "capsule", "notifications", "memory", "web", "sandbox", "routes", "recovery", "dev", "about"), opened)
    }

    /** 「トークン使用量」には過去 7 日間の合計を書き込みます。何もない場合は、「まだ」と書きます。統計がまだ計算されていない場合、この行は表示されません。 */
    @Test
    fun usageRowShowsWeekTotal() {
        var tokens by mutableStateOf<Long?>(123_000)
        compose.setContent {
            SettingsContent(
                status = ready, androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                usageTokens = tokens,
            )
        }
        compose.onNodeWithText(appString(R.string.usage_last_7d, "12.3 万")).assertIsDisplayed()
        tokens = 0
        compose.awaitOne(appString(R.string.usage_none)).assertIsDisplayed()
        tokens = null
        compose.waitForIdle()
        compose.onAllNodesWithText(appString(R.string.usage_title)).assertCountEquals(0)
    }

    /** リリース バージョンには開発者向けオプションが表示されません。 */
    @Test
    fun developerRowHiddenInRelease() {
        home(showDeveloper = false)
        compose.onAllNodesWithText(appString(R.string.settings_developer)).assertCountEquals(0)
    }

    /** 問題がある場合は、ステータス カードがプロンプトに変わり、ボタンによって問題が [問題] に渡されます。 */
    @Test
    fun statusCardTurnsIntoFixPrompt() {
        val clicked = mutableListOf<Problem>()
        home(
            SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
            onProblem = { clicked += it },
        )
        compose.onNodeWithText(appString(R.string.banner_wireless_off)).assertIsDisplayed()
        compose.onAllNodes(
            hasText(appString(R.string.all_ready)) and hasAnyAncestor(hasTestTag("settings-status")),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        compose.onNodeWithText(appString(R.string.pill_needs_attention)).assertIsDisplayed()
        compose.awaitOne(appString(R.string.heads_up_action)).performClick()
        assertEquals(listOf<Problem>(Problem.PhoneHelper(BuiltinShellStatus.WirelessDebuggingOff)), clicked)
    }

    @Test
    fun noModelAndOldSystem() {
        home(SystemStatus(30, BuiltinShellStatus.Running, AccessibilityState.EnabledNotRunning, notificationsAllowed = false), mainModel = null)
        compose.onNodeWithText(appString(R.string.banner_too_old)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_not_set)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.pill_unsupported)).assertIsDisplayed()
        // Android 12 以下の携帯電話の操作は利用できません：リカバリおよび修理は提供されません
        compose.onAllNodesWithText(appString(R.string.settings_recovery)).assertCountEquals(0)
    }

    @Test
    fun phoneOpsPageShowsPermissionRowsAndPairing() {
        var repaired = 0
        compose.setContent {
            PhoneOpsScreen(
                SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
                "14", onProblem = {}, onOpenNotifications = {}, onRepair = { repaired++ }, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText(appString(R.string.settings_a11y)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.state_on)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.phone_wireless_off)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.notif_normal)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_android, "14")).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_meets)).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.usageNote(HelperRestart.None)).assertExists()
        compose.onNodeWithText(PhoneSetupCopy.REPAIR).performClick()
        assertEquals(1, repaired)
    }

    /** 携帯電話の操作ページの「通知」行には、全体的なステータスが表示されます。クリックすると通知ページに入ります。 */
    @Test
    fun phoneOpsNotificationRowOpensNotifications() {
        var opened = 0
        compose.setContent {
            PhoneOpsScreen(
                ready.copy(notificationsAllowed = false), "14", onProblem = {}, onOpenNotifications = { opened++ },
                onRepair = {}, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText(appString(R.string.state_turned_off)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.notifications_title)).performClick()
        assertEquals(1, opened)
    }

    /** 通知ページ: マスター スイッチが有効になっている場合、チャンネルがオフになっている場合、または進行状況がオフになっている場合、各行にステータスが表示されます。各行をクリックして onFix に送信します。 */
    @Test
    fun notificationsPageShowsChannelsAndOpensThem() {
        val fixes = mutableListOf<Fix>()
        compose.setContent {
            NotificationsScreen(
                ready.copy(attentionImportance = 4, resultImportance = 2, progressImportance = 0), onFix = { fixes += it }, onBack = {},
            )
        }
        compose.onNodeWithText(appString(R.string.settings_allowed)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.notif_attention_when)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.notif_heads_up)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.notif_quiet)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.state_turned_off)).assertIsDisplayed()
        compose.onNodeWithText(StatusCopy.NOTIF_FOOTER).assertExists()
        compose.onNodeWithText(appString(R.string.channel_attention)).performClick()
        compose.onNodeWithText(appString(R.string.channel_result)).performClick()
        compose.onNodeWithText(appString(R.string.channel_progress)).performClick()
        assertEquals(listOf(Fix.OpenAttentionChannel, Fix.OpenResultChannel, Fix.OpenProgressChannel), fixes)
    }

    /** メイン スイッチがオフになっています: 「オンにします」。3 つのチャンネルすべてが閉じているように表示されます。 */
    @Test
    fun notificationsPageWhenAppOff() {
        val fixes = mutableListOf<Fix>()
        compose.setContent { NotificationsScreen(ready.copy(notificationsAllowed = false), onFix = { fixes += it }, onBack = {}) }
        compose.onAllNodesWithText(appString(R.string.state_turned_off)).assertCountEquals(3)
        compose.onNodeWithText(appString(R.string.heads_up_action)).performClick()
        assertEquals(listOf(Fix.AllowNotifications), fixes)
    }

    @Test
    fun unpairAsksFirst() {
        var unpaired = 0
        compose.setContent { PhoneOpsScreen(ready, "14", onProblem = {}, onOpenNotifications = {}, onRepair = {}, onUnpair = { unpaired++ }, onBack = {}) }
        compose.onNodeWithText(PhoneSetupCopy.UNPAIR).performClick()
        assertEquals(0, unpaired)
        compose.clickInDialog(PhoneSetupCopy.UNPAIR_CONFIRM)
        assertEquals(1, unpaired)
    }

    /** 並列スイッチがオンになっているが、デバイスがそれをサポートしていない場合: この行はグレー表示され、理由が示され、加算または減算は行われません。 */
    @Test
    fun parallelDisabledShowsReason() {
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(2, ParallelCapability.detect(33, lowRam = false, userMax = 2, secondDisplayFailed = false)),
                onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithText(appString(R.string.settings_parallel)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.parallel_needs_14)).assertIsDisplayed()
        compose.onNodeWithText(appPlural(R.plurals.settings_count_tasks, 1)).assertIsDisplayed()
        compose.onNodeWithText("+").assertDoesNotExist()
    }

    @Test
    fun parallelMaxAdjustable() {
        val picked = mutableListOf<Int>()
        var max by mutableStateOf(2)
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(max, ParallelCapability.detect(34, lowRam = false, userMax = max, secondDisplayFailed = false)),
                onParallelMax = { max = it; picked += it }, onBack = {},
            )
        }
        compose.onNodeWithText(appString(R.string.settings_keep_on)).assertIsDisplayed()
        compose.onNodeWithText(appPlural(R.plurals.settings_count_tasks, 2)).assertIsDisplayed()
        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(appPlural(R.plurals.settings_count_tasks, 3)).assertIsDisplayed()
        compose.onNodeWithText("−").performClick()
        assertEquals(listOf(3, 2), picked)
    }

    @Test
    fun duringWorkWithoutParallelHasNoParallelRow() {
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {})
        }
        compose.onNodeWithText(appString(R.string.settings_mute)).assertIsDisplayed()
        compose.onAllNodesWithText(appString(R.string.settings_parallel)).assertCountEquals(0)
    }

    /** 「作業中」の 2 つの項目はデフォルトでオンになっていますが、ユーザーはそれらをオフにすることができます。スイッチをクリックして新しい値をコールバックします。 */
    @Test
    fun duringWorkTogglesCanBeTurnedOff() {
        val changes = mutableListOf<String>()
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = { changes += "screen:$it" },
                muteTarget = true, onMuteTarget = { changes += "mute:$it" },
                parallel = null, onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("work-keep-screen-on").performClick()
        compose.onNodeWithTag("work-mute-target").performClick()
        assertEquals(listOf("screen:false", "mute:false"), changes)
    }

    /** 作業時 › 操作リズム: 速い、標準 (デフォルト)、人間らしい 3 つのうちの 1 つを選択し、クリックすると新しい値が呼び出されます。 */
    @Test
    fun operatingPaceIsOneChoiceOfThree() {
        val picked = mutableListOf<OperatingPace>()
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {}, onOperatingPace = { picked += it })
        }
        compose.onNodeWithText(appString(R.string.settings_pace)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_pace_fast)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_pace_standard)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_pace_human)).performClick()
        compose.onNodeWithText(appString(R.string.settings_pace_fast)).performClick()
        assertEquals(listOf(OperatingPace.HUMAN, OperatingPace.FAST), picked)
    }

    /** 現在の選択内容はホームページの「サスペンションカプセル」の欄に記載されております。 */
    @Test
    fun capsuleRowShowsCurrentChoice() {
        compose.setContent {
            SettingsContent(status = ready, androidRelease = "14", mainModel = "m", onBack = {}, onProblem = {}, onOpenModels = {}, capsuleMode = CapsuleMode.Always)
        }
        compose.awaitOne(appString(R.string.capsule_mode_always)).assertIsDisplayed()
    }

    /** 3 つのフローティング カプセル サブページの 1 つを選択し、コールバックの新しい値を選択します。フッターは、アクセシビリティ サービスが使用されていることを示します。 */
    @Test
    fun capsulePageOffersThreeChoices() {
        var mode by mutableStateOf(CapsuleMode.DuringTasks)
        val picked = mutableListOf<CapsuleMode>()
        compose.setContent { CapsuleScreen(mode, onMode = { picked += it; mode = it }, onBack = {}) }
        compose.awaitOne(appString(R.string.capsule_mode_tasks)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.capsule_mode_off)).performScrollTo().assertIsDisplayed()
        // 脚注は 3 つのオプションの下にあります。画面が短い場合、またはフォント サイズが大きい場合は、スクロールして表示する必要があります。
        compose.onNodeWithText(appString(R.string.settings_a11y), substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.capsule_mode_always)).performScrollTo().performClick()
        compose.onNodeWithText(appString(R.string.capsule_mode_off)).performScrollTo().performClick()
        assertEquals(listOf(CapsuleMode.Always, CapsuleMode.Off), picked)
    }

    /** 最初に「すべてクリア」してください。確認するとクリアされ、項目数が0になり、ボタンが消えます。キャンセルすると動きません。 */
    @Test
    fun clearRoutesAsksThenEmpties() {
        var routes by mutableStateOf(3)
        var cleared = 0
        compose.setContent { RoutesScreen(routes, onClear = { cleared++; routes = 0 }, onBack = {}) }
        compose.onNodeWithText(appString(R.string.routes_saved)).assertIsDisplayed()
        compose.onNodeWithText(appPlural(R.plurals.settings_count_routes, 3), substring = true).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.routes_clear_all)).performClick()
        compose.clickInDialog(appString(R.string.cancel))
        assertEquals(0, cleared)
        compose.onNodeWithText(appString(R.string.routes_clear_all)).performClick()
        compose.awaitOne(appString(R.string.routes_clear_title), inDialog = true).assertIsDisplayed()
        compose.clickInDialog(appString(R.string.clear))
        assertEquals(1, cleared)
        compose.onNodeWithText(appPlural(R.plurals.settings_count_routes, 0), substring = true).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.routes_clear_all)).assertDoesNotExist()
    }

    /** 開発者向けオプション: 8 つのスイッチは、速度と実験の 2 つのグループに分けられます。クリックすると対応する項目が開きます。 */
    @Test
    fun developerOptionsHasTwoGroupsAndToggles() {
        val changed = mutableListOf<SpeedFlags>()
        var flags by mutableStateOf(SpeedFlags.OFF)
        compose.setContent { DeveloperOptionsScreen(flags, onSpeed = { flags = it; changed += it }, onBack = {}) }
        compose.onNodeWithText(appString(R.string.dev_speed)).assertIsDisplayed()
        for (t in listOf(appString(R.string.dev_adaptive), appString(R.string.dev_inline), appString(R.string.dev_batch), appString(R.string.dev_deep_links), appString(R.string.dev_light))) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        scrollTo(appString(R.string.dev_experiments))
        compose.onNodeWithText(appString(R.string.dev_experiments)).assertIsDisplayed()
        scrollTo(appString(R.string.dev_replay))
        compose.onNodeWithText(appString(R.string.dev_replay)).performClick()
        assertEquals(listOf(SpeedFlags(replay = true)), changed)
        scrollTo(appString(R.string.dev_parallel))
        scrollTo(appString(R.string.dev_compaction))
        compose.onNodeWithText(appString(R.string.dev_compaction)).assertIsDisplayed()
    }

    /** 「自律モード」は開発者向けオプションの先頭にあり、デフォルトではオフになっています。スイッチをクリックするか、行全体を切り替えます。 */
    @Test
    fun autonomousSwitchComesFirstAndToggles() {
        val toggled = mutableListOf<Boolean>()
        var on by mutableStateOf(false)
        compose.setContent { DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, autonomous = on, onAutonomous = { toggled += it; on = it }) }
        compose.onNodeWithText(AutonomyCopy.GROUP).assertIsDisplayed()
        compose.onNodeWithText(AutonomyCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("dev-autonomous").assertIsOff().performClick()
        compose.onNodeWithTag("dev-autonomous").assertIsOn()
        compose.onNodeWithText(AutonomyCopy.TITLE).performClick()
        compose.onNodeWithTag("dev-autonomous").assertIsOff()
        assertEquals(listOf(true, false), toggled)
    }

    /** 「ビュー画像の位置が確認されました」が開発者向けオプションに移動されました。画像ビュー モデルが利用可能な場合にのみ表示されます。クリックして切り替えます。 */
    @Test
    fun groundingSwitchShowsOnlyWithVisionModel() {
        val toggled = mutableListOf<Boolean>()
        var grounding by mutableStateOf<GroundingSetting?>(null)
        compose.setContent {
            DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, grounding = grounding, onGrounding = { toggled += it })
        }
        compose.onNodeWithText(GroundingCopy.TITLE).assertDoesNotExist()
        grounding = GroundingSetting("qwen-vl-max", verified = false)
        scrollTo(GroundingCopy.TITLE)
        compose.onNodeWithText(GroundingCopy.TITLE).performClick()
        assertEquals(listOf(true), toggled)
    }

    private class Calls { var reset = 0; val network = mutableListOf<Boolean>() }

    private fun advanced(status: SandboxStatus, network: Boolean = false, isolationIssue: Boolean = false): Calls {
        val c = Calls()
        compose.setContent {
            Column {
                AdvancedSection(
                    status = status, networkAllowed = network, isolationIssue = isolationIssue,
                    onNetworkChange = { c.network += it }, onReset = { c.reset++ },
                )
            }
        }
        return c
    }

    @Test
    fun unsupportedHidesAdvanced() {
        advanced(SandboxStatus.Unsupported("此设备不支持(需要 arm64)"))
        compose.onAllNodesWithText(SandboxCopy.NETWORK_TITLE).assertCountEquals(0)
        compose.onAllNodesWithText(appString(R.string.sandbox_reset)).assertCountEquals(0)
    }

    @Test
    fun advancedHasNoInstallOrSandboxWording() {
        advanced(SandboxStatus.NotInstalled)
        compose.onNodeWithText(SandboxCopy.NETWORK_TITLE).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.sandbox_reset)).assertIsDisplayed()
        for (t in listOf("サンドボックス", "サンドボックスをインストール", "ソフトウェアの配布元", "ワークスペースを空にする")) compose.onAllNodesWithText(t, substring = true).assertCountEquals(0)
    }

    @Test
    fun resetAsksFirst() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithText(appString(R.string.sandbox_reset)).performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText(SandboxCopy.RESET_BODY).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.cancel)).performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText(appString(R.string.sandbox_reset)).performClick()
        compose.onNodeWithText(appString(R.string.reset)).performClick()
        assertEquals(1, c.reset)
    }

    @Test
    fun isolationIssueShown() {
        advanced(SandboxStatus.Ready(network = true), network = true, isolationIssue = true)
        compose.onNodeWithText(SandboxCopy.NETWORK_EXPLAIN).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.ISOLATION_UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun networkToggleReportsChange() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithTag("sandbox-network").performClick()
        assertTrue(c.network == listOf(true))
    }

    /** 使用スペース: 各会話のワークスペースに 1 行。実行中のタスクがある場合は、それらをクリアできない、またはクリックできないことを示してください。最初にクリアを確認し、確認後にコールバックする必要があります。 */
    @Test
    fun usageClearsAWorkspaceOnlyAfterConfirming() {
        val usage = com.wangzan101.mika.cap.sandbox.SandboxUsage(
            runtime = 300L shl 20, skills = 2L shl 20,
            workspaces = listOf(
                com.wangzan101.mika.cap.sandbox.WorkspaceUsage("c-a", "a", "查快递", 5L shl 20, busy = true),
                com.wangzan101.mika.cap.sandbox.WorkspaceUsage("c-b", "b", "整理表格", 1L shl 20, busy = false),
                com.wangzan101.mika.cap.sandbox.WorkspaceUsage("c-gone", null, null, 1024, busy = false),
            ),
        )
        val cleared = mutableListOf<String>()
        var all = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AdvancedSection(
                    status = SandboxStatus.Ready(network = false), networkAllowed = false, isolationIssue = false,
                    onNetworkChange = {}, onReset = {}, usage = usage,
                    onClearWorkspace = { cleared += it.dirName }, onClearAll = { all++ },
                )
            }
        }
        compose.onNodeWithText(SandboxCopy.USAGE_TITLE).assertExists()
        compose.onNodeWithText(appString(R.string.sandbox_usage_deleted_chat)).assertExists()
        compose.onNodeWithText(appString(R.string.sandbox_usage_busy)).assertExists()
        compose.onNodeWithText("查快递").performScrollTo().performClick()
        compose.onAllNodesWithText(SandboxCopy.CLEAR_TITLE).assertCountEquals(0)
        compose.onNodeWithText("整理表格").performScrollTo().performClick()
        compose.onNodeWithText(SandboxCopy.CLEAR_TITLE).assertIsDisplayed()
        assertEquals(emptyList<String>(), cleared)
        compose.onNodeWithText(SandboxCopy.CLEAR).performClick()
        assertEquals(listOf("c-b"), cleared)
        compose.onNodeWithText(SandboxCopy.CLEAR_ALL).performScrollTo().performClick()
        compose.onNodeWithText(SandboxCopy.CLEAR_ALL_BODY).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.CLEAR).performClick()
        assertEquals(1, all)
    }
}

