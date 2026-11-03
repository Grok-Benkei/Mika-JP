package com.wangzan101.mika.cap.phone.a11y

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.a11y.MikaAccessibilityService

/** 実機テスト共通: シェル接続、アクセシビリティ サービス、仮想画面上の AndroidScreen。内蔵アシスタントが実行されており (ペアリング、ワイヤレス デバッグがオン)、Mika アクセシビリティ サービスがオンになっている必要があります。 */
object DeviceSupport {
    const val SETTINGS = "com.android.settings"
    val TEST_PACKAGE: String get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName + ".test"
    private var connection: ShellBinding? = null
    private val phone get() = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * とアプリはプロセス内でシェル接続を共有し、常にそれを占有しています。それぞれをバインドしてからバインドを解除すると、最初にバインドを解除した側がシェル サービスを終了します。
     * 相手の手札のバインダーは無効になります。
     */
    @Synchronized
    fun shell(): IMikaShell {
        val c = connection ?: phone.sharedShell.acquire().also {
            connection = it
            rebindAccessibility()
        }
        return awaitShell(c)
    }

    /** は、ライブ アシスタントを取得するための接続を待ちます。待つことができない場合は、現在のステータスで失敗し、最初にペアリングするように求められます。アシスタントが強制終了された後、ウォッチドッグが再起動するまでに時間がかかり、切断コールバックが到着する前に無効なバインダーがまだ保持されている可能性があります。 */
    fun awaitShell(c: ShellBinding, timeoutMs: Long = 30_000): IMikaShell = runBlocking {
        withTimeoutOrNull(timeoutMs) { c.shell.filterNotNull().first { it.asBinder().pingBinder() } }
    } ?: error(
        "Mika の操作アシスタントが動作していません（状態: ${phone.shellControl.status.value}）。" +
            "ワイヤレスデバッグをオンにして、Mika の「スマホの操作を設定」で一度ペア設定してください。ワイヤレスデバッグをオンにしたまま、もう一度テストを実行してください。",
    )

    /** サービスがオフラインになった場合 (前回のテストでこのプロセスのバインドが終了した場合など)、まずシステムに再バインドするように要求し、その後接続するまで待ちます。 */
    fun service(): ScreenAccessibility {
        if (ScreenAccessibility.instance.value == null) rebindAccessibility()
        return runBlocking {
            withTimeoutOrNull(10_000) { ScreenAccessibility.instance.filterNotNull().first() }
        } ?: error("Mika のユーザー補助サービスが有効になっていません")
    }

    class Agent(val display: AgentDisplay, val screen: AndroidScreen) {
        fun release() = display.release()
    }

    fun newAgent(): Agent {
        val shell = shell()
        val d = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        return Agent(d, AndroidScreen(service(), shell, ready(d)))
    }

    /** すでに構築された画面: テストするには、最初に画面を構築します (最初にアプリを配置することもできます)。phone_launch_app が画面を読み取るのを待つ必要はありません。 */
    fun ready(d: AgentDisplay): LazyAgentDisplay = LazyAgentDisplay(d.width, d.height) { d }.also { runBlocking { it.get() } }

    /**
     * テストを開始すると、システムはアプリを強制的に停止し、ユーザー補助サービスはクラッシュしたと記録され、自動的に再接続されなくなります。
     * がオンになっている場合は、スタートアップ リストから削除して元に戻し、システムに再バインドを要求します。オンにしないと動きません。
     */
    private fun rebindAccessibility() {
        if (ScreenAccessibility.instance.value != null) return
        val key = "enabled_accessibility_services"
        val mika = "${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/${MikaAccessibilityService::class.java.name}"
        val enabled = sh("settings get secure $key").trim().split(':')
        if (mika !in enabled) return
        try {
            sh("settings put secure $key ${enabled.filter { it != mika }.joinToString(":").ifEmpty { "\"\"" }}")
            Thread.sleep(500)
        } finally {
            sh("settings put secure $key ${enabled.joinToString(":")}")
        }
    }

    fun sh(cmd: String): String {
        // 他のユーザー補助サービスを抑制しない。抑制すると、テスト中にMikaのサービスも一時停止する。
        val ua = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val pfd = ua.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** テスト用パッケージの画面を仮想画面で開く。指定するクラス名はマニフェストの登録名と一致させる。 */
    fun startTestActivity(displayId: Int, activity: Class<out android.app.Activity>) {
        sh("am start --display $displayId -n $TEST_PACKAGE/${activity.name}")
    }

    /**
     * メイン画面の状態: 最前面のディスプレイ、ディスプレイ0のフォーカスウィンドウとActivity、メイン画面の最上位タスク。
     * 仮想画面上でエージェントが操作しても、これらは変化してはならない。
     */
    fun mainScreenState(shell: IMikaShell): String {
        val topDisplay = sh("dumpsys window").lines().map { it.trim() }
            .firstOrNull { it.startsWith("mTopFocusedDisplayId=") }
        checkNotNull(topDisplay) { "dumpsys window に mTopFocusedDisplayId が見つかりません" }
        val section = sh("dumpsys window displays").substringAfter("Display: mDisplayId=0", "")
            .substringBefore("Display: mDisplayId=").lines().map { it.trim() }
        val focus = section.firstOrNull { it.startsWith("mCurrentFocus=") }
        checkNotNull(focus) { "ディスプレイ 0 の mCurrentFocus が見つかりません" }
        val focusedApp = section.firstOrNull { it.startsWith("mFocusedApp=") }
            ?.let { Regex("u0 (\\S+)").find(it)?.groupValues?.get(1) }
        return "$topDisplay $focus focusedApp=$focusedApp topTask=${shell.topTaskId(android.view.Display.DEFAULT_DISPLAY)}"
    }

    fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        return block()
    }
}
