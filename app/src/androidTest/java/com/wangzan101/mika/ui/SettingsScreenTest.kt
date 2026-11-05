package com.wangzan101.mika.ui

import com.wangzan101.mika.R

import com.wangzan101.mika.core.shell.HelperRestart
import com.wangzan101.mika.JapaneseUi
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
    @get:Rule(order = Int.MIN_VALUE) val japanese = JapaneseUi()

    @get:Rule val compose = createComposeRule()

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)

    private fun home(
        status: SystemStatus = ready,
        mainModel: String? = "DeepSeek",
        onProblem: (Problem) -> Unit = {},
        opened: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            SettingsContent(
                status = status, androidRelease = "14", mainModel = mainModel, onBack = {}, onProblem = onProblem,
                onOpenModels = { opened += "models" }, onOpenPhoneOps = { opened += "phone" }, onOpenDuringWork = { opened += "work" },
                capsuleMode = CapsuleMode.DuringTasks, onOpenCapsule = { opened += "capsule" }, onOpenNotifications = { opened += "notifications" },
                onOpenLanguage = { opened += "language" }, onOpenAppAccess = { opened += "access" },
                onOpenMemory = { opened += "memory" }, onOpenAdvanced = { opened += "advanced" },
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
        compose.onNode(hasText(appString(R.string.all_ready)) and hasAnyAncestor(hasTestTag("settings-status")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_basic)).assertIsDisplayed()
        // 首页只显示模型、手机操作、通知和版本等常用状态。
        for ((label, value) in listOf(
            R.string.settings_model to "DeepSeek",
            R.string.settings_phone_ops to appString(R.string.pill_available),
            R.string.notifications_title to appString(R.string.notif_normal),
            R.string.settings_about to "0.1.0",
        )) {
            compose.onNode(hasText(appString(label)) and hasText(value)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun homeRowsOpenTheirPages() {
        val opened = mutableListOf<String>()
        home(opened = opened)
        for (t in listOf(appString(R.string.settings_model), appString(R.string.settings_phone_ops), appString(R.string.settings_during_work), appString(R.string.capsule_settings_title), appString(R.string.settings_language_appearance), appString(R.string.notifications_title), appString(R.string.settings_memory_data), appString(R.string.appaccess_title), appString(R.string.settings_advanced), appString(R.string.settings_about))) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf("models", "phone", "work", "capsule", "language", "notifications", "memory", "access", "advanced", "about"), opened)
    }

    /** 高级与其他集中放连接、维护和工程入口,各行仍打开原来的子页。 */
    @Test
    fun advancedRowsOpenTheirPages() {
        val opened = mutableListOf<String>()
        compose.setContent {
            AdvancedSettingsContent(
                onBack = {}, proxy = "", onOpenProxy = { opened += "proxy" },
                sandbox = SandboxStatus.Ready(network = false), onOpenSandbox = { opened += "sandbox" },
                onOpenRecovery = { opened += "recovery" },
                computerAccess = false, onOpenComputerAccess = { opened += "computer" },
                mcpServersCount = 0, onOpenMcpServers = { opened += "mcp" },
                showDeveloper = true, onOpenDeveloper = { opened += "dev" },
            )
        }
        for (t in listOf(appString(R.string.net_proxy), appString(R.string.settings_sandbox), appString(R.string.mcp_title), appString(R.string.mcp_servers_title), appString(R.string.settings_recovery))) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        compose.onNodeWithText(appString(R.string.settings_developer)).performScrollTo().performClick()
        assertEquals(listOf("proxy", "sandbox", "computer", "mcp", "recovery", "dev"), opened)
    }

    /** 发布版的高级与其他里没有开发者选项。 */
    @Test
    fun developerRowHiddenInRelease() {
        compose.setContent { AdvancedSettingsContent(onBack = {}, showDeveloper = false) }
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
        compose.onAllNodesWithText(appString(R.string.all_ready)).assertCountEquals(0)
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
    }

    @Test
    fun phoneOpsPageShowsPermissionRowsAndPairing() {
        var repaired = 0
        compose.setContent {
            PhoneOpsScreen(
                SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
                "14", onProblem = {}, onRepair = { repaired++ }, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText(appString(R.string.settings_a11y)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.state_on)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.phone_wireless_off)).assertIsDisplayed()
        compose.onNodeWithText("安卓 14").assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.settings_meets)).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.usageNote(HelperRestart.None)).assertExists()
        compose.onNodeWithText(PhoneSetupCopy.REPAIR).performClick()
        assertEquals(1, repaired)
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
        compose.setContent { PhoneOpsScreen(ready, "14", onProblem = {}, onRepair = {}, onUnpair = { unpaired++ }, onBack = {}) }
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
        compose.onNodeWithText("1 个").assertIsDisplayed()
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
        compose.onNodeWithText("2 个").assertIsDisplayed()
        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 个").assertIsDisplayed()
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
        compose.onNodeWithText(appString(R.string.mcp_turn_off)).performScrollTo().assertIsDisplayed()
        // 脚注は 3 つのオプションの下にあります。画面が短い場合、またはフォント サイズが大きい場合は、スクロールして表示する必要があります。
        compose.onNodeWithText(appString(R.string.settings_a11y), substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.capsule_mode_always)).performScrollTo().performClick()
        compose.onNodeWithText(appString(R.string.mcp_turn_off)).performScrollTo().performClick()
        assertEquals(listOf(CapsuleMode.Always, CapsuleMode.Off), picked)
    }

    /** 最初に「すべてクリア」してください。確認するとクリアされ、項目数が0になり、ボタンが消えます。キャンセルすると動きません。 */
    @Test
    fun clearRoutesAsksThenEmpties() {
        var routes by mutableStateOf(3)
        var cleared = 0
        compose.setContent { RoutesScreen(routes, onClear = { cleared++; routes = 0 }, onBack = {}) }
        compose.onNodeWithText(appString(R.string.routes_saved)).assertIsDisplayed()
        compose.onNodeWithText("3 条", substring = true).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.routes_clear_all)).performClick()
        compose.clickInDialog(appString(R.string.cancel))
        assertEquals(0, cleared)
        compose.onNodeWithText(appString(R.string.routes_clear_all)).performClick()
        compose.awaitOne(appString(R.string.routes_clear_title), inDialog = true).assertIsDisplayed()
        compose.clickInDialog(appString(R.string.clear))
        assertEquals(1, cleared)
        compose.onNodeWithText("0 条", substring = true).assertIsDisplayed()
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

    /** 「自主模式」在任务运行最前面,默认关;点开关或整行都切换。 */
    @Test
    fun autonomousSwitchComesFirstAndToggles() {
        val toggled = mutableListOf<Boolean>()
        var on by mutableStateOf(false)
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = null, onParallelMax = {}, onBack = {}, autonomous = on, onAutonomous = { toggled += it; on = it },
            )
        }
        compose.onNodeWithText(AutonomyCopy.GROUP).assertIsDisplayed()
        compose.onNodeWithText(AutonomyCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("work-autonomous").assertIsOff().performClick()
        compose.onNodeWithTag("work-autonomous").assertIsOn()
        compose.onNodeWithText(AutonomyCopy.TITLE).performClick()
        compose.onNodeWithTag("work-autonomous").assertIsOff()
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
        advanced(SandboxStatus.Unsupported(appString(R.string.install_unsupported)))
        compose.onAllNodesWithText(SandboxCopy.NETWORK_TITLE).assertCountEquals(0)
        compose.onAllNodesWithText(appString(R.string.sandbox_reset)).assertCountEquals(0)
    }

    @Test
    fun advancedHasNoInstallOrSandboxWording() {
        advanced(SandboxStatus.NotInstalled)
        compose.onNodeWithText(SandboxCopy.NETWORK_TITLE).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.sandbox_reset)).assertIsDisplayed()
        for (t in listOf(appString(R.string.selfcheck_check_sandbox), "安装沙箱", "软件源", "清空工作区")) compose.onAllNodesWithText(t, substring = true).assertCountEquals(0)
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
        compose.onNodeWithText(appString(R.string.sandbox_resetting)).performClick()
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

