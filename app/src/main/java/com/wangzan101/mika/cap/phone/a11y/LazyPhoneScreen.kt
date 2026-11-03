package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.AfterTaskMarks
import com.wangzan101.mika.core.uistate.AppCopy
import com.wangzan101.mika.core.uistate.AppWait
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.ScreenEvent
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.SystemCommandResult
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import com.wangzan101.mika.core.uistate.WheelOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** にはモバイル アプリの操作に必要なもの (組み込みアシスタント、アクセシビリティ) がありません。 [hint] は、モデルとユーザーにその方法を指示する文です。 */
class PhoneUnavailable(val hint: String) : Exception(hint)

/**
 * 使用時のみ接続されるモバイル ポート: [acquire] は、launch_app / open_link が初めて使用されるとき、またはシステム コマンドまたはイベント ツールが使用されるときにのみ呼び出されます (組み込みアシスタントを接続し、アクセシビリティを待機し、仮想画面を準備します。これらのアクションが使用されるまで画面は作成されません)。
 * Web またはサンドボックスのみを使用して最初から最後までタスクを実行するには、組み込みのヘルパーやアクセシビリティは必要ありません。接続する前、読み取り画面は空白の画面であり、他のアクションは no_app_open を報告します。
 * は接続後アプリを開く前と同じです。接続できない場合、このアクションは、phone_unavailable を報告します (修正方法は添付されています)。次回もぜひお試しください。
 * がどのアプリがインストールされているかを尋ねるとき、パッケージ マネージャー ([listApps]) には接続せずに尋ねるだけです。別のユーザーのアプリが接続されていない場合、[appCopies] が要求されます (組み込みアシスタントは接続されている場合にのみ使用可能であり、接続されません)。
 */
class LazyPhoneScreen(
    private val width: Int,
    private val height: Int,
    private val listApps: suspend () -> List<Pair<String, String>>,
    private val appCopies: suspend () -> List<AppCopy> = { emptyList() },
    private val acquire: suspend () -> ScreenPort,
) : ScreenPort {
    private val lock = Mutex()
    @Volatile private var port: ScreenPort? = null

    /** モデルのタスクが完了すると、各アプリは残るか消えます。最後に、タスクは実行のために組み込みアシスタントに渡されます (携帯電話に接続されていない場合、処理するものは何もありません)。 */
    override val afterTask = AfterTaskMarks()

    /** このタスクでは携帯電話が使用されました (接続されています)。 */
    val acquired: Boolean get() = port != null

    private suspend fun built(): ScreenPort? = lock.withLock {
        port ?: try {
            acquire().also { port = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneUnavailable) {
            lastProblem = e.hint
            null
        }
    }

    @Volatile private var lastProblem: String? = null

    private fun unavailable() = ActionResult.Failed(PHONE_UNAVAILABLE, lastProblem)

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = built()?.openApp(packageName, cold) ?: unavailable()
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        built()?.openAppCopy(packageName, userId, cold) ?: unavailable()
    // 他のタスクが占有している場合にのみ待機します。その時点までに、電話はすでに接続されています。接続されていない場合は、何も待つ必要はありません。
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait =
        port?.awaitAppFree(packageName, userId, timeoutMs) ?: AppWait.Unsupported
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult =
        built()?.openLink(action, uri, packageName) ?: unavailable()
    override suspend fun shareText(packageName: String, text: String): ActionResult = built()?.shareText(packageName, text) ?: unavailable()
    override suspend fun systemAction(action: SystemAction): SystemActionResult = built()?.systemAction(action) ?: SystemActionResult(unavailable())
    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        port?.setWheel(ref, snapshotId, value) ?: WheelOutcome(false, null, null, 0, "none", error = "no_app_open")

    // ---------- 接続前：開いていないアプリ ----------

    override suspend fun snapshot(): ScreenSnapshot = port?.snapshot() ?: empty()
    override suspend fun peek(): ScreenSnapshot = port?.peek() ?: empty()
    override suspend fun screenshotPng(): ByteArray? = port?.screenshotPng()
    override suspend fun readVisibleText(): List<VisibleTextLine>? = port?.readVisibleText()
    override suspend fun tapRef(ref: String, snapshotId: String) = port?.tapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun tapXY(x: Int, y: Int) = port?.tapXY(x, y) ?: NO_APP_OPEN
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = port?.doubleTapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun doubleTapXY(x: Int, y: Int) = port?.doubleTapXY(x, y) ?: NO_APP_OPEN
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = port?.typeText(ref, snapshotId, text) ?: NO_APP_OPEN
    override suspend fun clearText(ref: String?, snapshotId: String?) = port?.clearText(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun typeAt(x: Int, y: Int, text: String) = port?.typeAt(x, y, text) ?: NO_APP_OPEN
    override suspend fun clearAt(x: Int, y: Int) = port?.clearAt(x, y) ?: NO_APP_OPEN
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = port?.scroll(ref, snapshotId, down) ?: NO_APP_OPEN
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = port?.swipe(x1, y1, x2, y2, durationMs) ?: NO_APP_OPEN
    override suspend fun idleScroll(fraction: Double): Boolean = port?.idleScroll(fraction) ?: false
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = port?.gesture(paths, durationMs) ?: NO_APP_OPEN
    override suspend fun back() = port?.back() ?: NO_APP_OPEN
    override suspend fun pressKey(keyCode: Int) = port?.pressKey(keyCode) ?: NO_APP_OPEN
    override suspend fun waitStable(timeoutMs: Long) = port?.waitStable(timeoutMs) ?: true
    override suspend fun awaitChange(timeoutMs: Long): Boolean = port?.awaitChange(timeoutMs) ?: super.awaitChange(timeoutMs)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = port?.waitFor(condition, value, timeoutMs) ?: false
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? {
        val screen = built() ?: return null
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.nextEvent(kind, packageName, timeoutMs)
    }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> {
        val screen = built() ?: return emptyList()
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.watchEvents(kind, packageName, durationMs, maxEvents)
    }
    override suspend fun systemCommand(command: String): SystemCommandResult? {
        val screen = built() as? AndroidScreen ?: return null
        screen.ensureDisplay()
        return screen.systemCommand(command)
    }
    override suspend fun listApps(): List<Pair<String, String>> = port?.listApps() ?: listApps.invoke()
    // 内蔵アシスタントに質問する: 接続する前に接続しないでください (接続できない場合は、ユーザーがセットアップするよう求められます)。アプリを開いた後にのみ接続してください。
    override suspend fun otherProfiles(): Map<String, List<String>> = port?.otherProfiles() ?: emptyMap()
    override suspend fun appCopies(): List<AppCopy> = port?.appCopies() ?: appCopies.invoke()
    override suspend fun checkScreen(): ScreenCheck? = port?.checkScreen()
    override suspend fun isProtected() = port?.isProtected() ?: false
    override suspend fun frameGrid() = port?.frameGrid()

    private fun empty() = ScreenSnapshot(snapshotId(), null, emptyList(), width, height, hasWindow = false)

    companion object {
        const val PHONE_UNAVAILABLE = "phone_unavailable"
        private val NO_APP_OPEN = ActionResult.Failed("no_app_open")
    }
}
