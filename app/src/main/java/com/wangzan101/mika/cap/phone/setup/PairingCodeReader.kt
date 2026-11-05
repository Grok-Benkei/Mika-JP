package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PairingScreen
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.shell.PairingScreenReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * をペアリングするときは、Mika のアクセシビリティ サービスを使用して、システムのペアリング ウィンドウにあるペアリング コードを読み取ります。それを読んだ後は、ユーザーが通知に記入する必要がなく、直接ペアリングされます。
 * は、[PairingCoordinator] がオンになっている期間中にのみ実行され、システム設定ウィンドウのみを表示します。読み取ったコードはログに書き込まれません。
 * アクセシビリティがオンになっていない場合、何も行われず、マッチング時に通知が送信されます。
 */
internal object PairingCodeReader {
    /** ウィンドウの内容は頻繁に数回更新されるため、安定するまで待ってから読み取ります。 */
    private const val SETTLE_MS = 300L
    private const val MAX_NODES = 400
    private const val TAG = "MikaPairing"

    suspend fun run(context: Context, control: BuiltinShellControl, coordinator: PairingCoordinator) {
        val settings = SystemPickers.settingsPackage(context.packageManager) ?: run {
            Log.w(TAG, "システム設定アプリを検出できないためコードを読み取りません")
            return
        }
        val tried = mutableSetOf<String>()
        var last: String? = null
        ScreenAccessibility.instance.collectLatest { service ->
            if (service == null) {
                Log.i(TAG, "ユーザー補助が動作していないため通知からコードを入力してください")
                return@collectLatest
            }
            Log.i(TAG, "$settings のペアリング画面を読み取っています")
            service.eventsFor(Display.DEFAULT_DISPLAY)
                .filter { it.packageName?.toString() == settings }
                .map { }
                .onStart { emit(Unit) }
                .conflate()
                .collect {
                    delay(SETTLE_MS)
                    val portFound = control.pairingDiscovery.value is PairingDiscovery.Found
                    val status = PhoneSetupFlow.effectiveStatus(control.status.value, coordinator.repairing.value)
                    val readings = readings(service, settings)
                    // 観測状態が変わったときだけ記録し、コード自体は記録しません。
                    val seen = "ウィンドウ数 ${readings.size}、コード有無 ${readings.any { it.code != null }}、ポート ${readings.firstNotNullOfOrNull { it.port }}、ポート検出 $portFound、状態 $status"
                    if (seen != last) {
                        Log.i(TAG, "検出状態: $seen")
                        last = seen
                    }
                    val attempt = readings.firstNotNullOfOrNull {
                        PairingScreen.attempt(it, portFound, status, tried)
                    } ?: return@collect
                    Log.i(TAG, "画面からコードを読み取り、ペアリングを開始します")
                    tried += attempt.code
                    coordinator.pair(attempt.code, attempt.port)
                }
        }
    }

    /** 各システム設定ウィンドウを 1 回読み取ります。ペアリング コードとポートは同じウィンドウ内にある必要があります (ペアリング ウィンドウの下のページにも「IP:ポート」があります)。 */
    private fun readings(service: ScreenAccessibility, settings: String): List<PairingScreenReading> =
        service.windowsOn(Display.DEFAULT_DISPLAY).mapNotNull { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
            if (root.packageName?.toString() != settings) return@mapNotNull null
            PairingScreen.read(texts(root))
        }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }
}
