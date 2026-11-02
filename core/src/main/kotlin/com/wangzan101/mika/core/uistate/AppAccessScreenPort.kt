package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.access.AppAccessState
import kotlinx.coroutines.delay

/**
 * モバイル ポートのアプリケーション アクセス境界: 各 [ScreenPort] メンバーはここに明示的に書き込まれ (`by` 委任なし)、後でポートに追加されたメンバーが検査なしで見逃されることはありません。
 * このポイントをメンバーごとにテストで確認します。
 *
 * 画面の読み取りと、アクションの前後に各1回、前面ウィンドウの帰属を確認する; 対象を開く場合（App、共有、App を指定したリンク）は対象だけを見る。
 * ウィンドウプローブ [packages] は帰属を読むだけで、内容は収集せず、確認のために画面も作らない。禁止リストが空のときは何も追加で行わない。
 *
 * ウィンドウへの問い合わせを減らす: [snapshot]、[peek] は読み取ったその画面の中のウィンドウ帰属で直接確認し、別途尋ねない; [windowToken]（ウィンドウが変わるたびに変わるカウンタ）が渡されたときは、
 * 確認済みの結果は、ウィンドウが変わらず、リストも変わらず、かつ [CHECK_TTL_MS] を超えていなければそのまま使い回す（同じ画面読み取りに続くスクリーンショットや、プレビューの連続フレーム）。
 */
class AppAccessScreenPort(
    private val inner: ScreenPort,
    override val appAccess: AppAccessPolicy,
    private val packages: suspend () -> List<String?>,
    private val protectedUi: () -> Boolean = { false },
    private val ownPackage: String? = null,
    private val windowToken: (() -> Long)? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ScreenPort {
    /** 最後のチェックに合格したときのウィンドウ数、リストのバージョン、Mika 自身のインターフェイスが保護されているかどうか、および時間。 */
    private class Verified(val token: Long, val revision: Long, val guarded: Boolean, val at: Long)

    @Volatile private var verified: Verified? = null

    override suspend fun checkAppAccess() {
        val state = appAccess.current()
        if (!state.available) throw AppAccessDenied("app_access_unavailable", null)
        val guarded = protectedUi()
        if (state.blocked.isEmpty() && !guarded) return
        if (stillVerified(state, guarded)) return
        val token = windowToken?.invoke()
        verify(state, packages(), guarded, token)
    }

    private fun stillVerified(state: AppAccessState, guarded: Boolean): Boolean {
        val v = verified ?: return false
        val token = windowToken?.invoke() ?: return false
        return v.token == token && v.revision == state.revision && v.guarded == guarded && clock() - v.at <= CHECK_TTL_MS
    }

    /** これらのウィンドウ [found] の所有権を確認します。所有者が分からない場合は、しばらくしてから再度窓口に問い合わせてください。承認された場合は、[stillVerified] が将来使用できるように記録します。 */
    private suspend fun verify(state: AppAccessState, found: List<String?>, guarded: Boolean, token: Long?) {
        verified = null
        var s = state
        var targets = found
        var t = token
        if (s.blocked.isNotEmpty() && targets.any { it.isNullOrBlank() }) {
            delay(RETRY_MS)
            t = windowToken?.invoke()
            targets = packages()
            s = appAccess.current()
        }
        if (!s.available) throw AppAccessDenied("app_access_unavailable", null)
        for (pkg in targets) {
            if (guarded && ownPackage != null && pkg == ownPackage) throw AppAccessDenied("app_access_protected", pkg)
            s.denial(pkg)?.let { throw it }
        }
        verified = t?.let { Verified(it, s.revision, guarded, clock()) }
    }

    /** 画面の読み取り: 画面を読み取ると、ウィンドウの所有権も取得します。読んだ後は、それを確認してください。ポートがウィンドウに割り当てられていない場合は、質問するウィンドウに戻ります。 */
    private suspend fun observed(read: suspend () -> ScreenSnapshot): ScreenSnapshot {
        val state = appAccess.current()
        if (!state.available) throw AppAccessDenied("app_access_unavailable", null)
        val guarded = protectedUi()
        if (state.blocked.isEmpty() && !guarded) return read()
        // 最初にカウントします: 読み取りプロセス中にウィンドウが変更されるため、次回は使用されません
        val token = windowToken?.invoke()
        val snap = read()
        verify(appAccess.current(), snap.accessPackages() ?: packages(), guarded, token)
        return snap
    }

    private suspend fun <T> checked(body: suspend () -> T): T {
        checkAppAccess()
        val value = body()
        checkAppAccess()
        return value
    }

    private fun target(packageName: String) {
        appAccess.current().denial(packageName)?.let { throw it }
    }

    // ---------- 画面の読み取り ----------
    override suspend fun snapshot() = observed { inner.snapshot() }
    override suspend fun peek() = observed { inner.peek() }
    override suspend fun screenshotPng() = checked { inner.screenshotPng() }
    override suspend fun readVisibleText() = checked { inner.readVisibleText() }
    override suspend fun frameGrid() = checked { inner.frameGrid() }
    override suspend fun isProtected() = checked { inner.isProtected() }
    override suspend fun checkScreen() = checked { inner.checkScreen() }

    // ---------- 現在の画面でのアクション ----------
    override suspend fun tapRef(ref: String, snapshotId: String) = checked { inner.tapRef(ref, snapshotId) }
    override suspend fun tapXY(x: Int, y: Int) = checked { inner.tapXY(x, y) }
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = checked { inner.doubleTapRef(ref, snapshotId) }
    override suspend fun doubleTapXY(x: Int, y: Int) = checked { inner.doubleTapXY(x, y) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = checked { inner.typeText(ref, snapshotId, text) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = checked { inner.clearText(ref, snapshotId) }
    override suspend fun typeAt(x: Int, y: Int, text: String) = checked { inner.typeAt(x, y, text) }
    override suspend fun clearAt(x: Int, y: Int) = checked { inner.clearAt(x, y) }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = checked { inner.scroll(ref, snapshotId, down) }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = checked { inner.swipe(x1, y1, x2, y2, durationMs) }
    override suspend fun idleScroll(fraction: Double) = checked { inner.idleScroll(fraction) }
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = checked { inner.gesture(paths, durationMs) }
    override suspend fun back() = checked { inner.back() }
    override suspend fun pressKey(keyCode: Int) = checked { inner.pressKey(keyCode) }
    override suspend fun setWheel(ref: String, snapshotId: String, value: String) = checked { inner.setWheel(ref, snapshotId, value) }

    // ---------- 待機とイベント ----------
    override suspend fun waitStable(timeoutMs: Long) = checked { inner.waitStable(timeoutMs) }
    override suspend fun awaitChange(timeoutMs: Long) = checked { inner.awaitChange(timeoutMs) }
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = checked { inner.waitFor(condition, value, timeoutMs) }
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long) = checked { inner.nextEvent(kind, packageName, timeoutMs) }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int) =
        checked { inner.watchEvents(kind, packageName, durationMs, maxEvents) }

    // ---------- ターゲットごとに開く: 制限されたアプリを離れて、ターゲットのみを表示できます ----------
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
        target(packageName)
        return inner.openApp(packageName, cold)
    }

    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult {
        target(packageName)
        return inner.openAppCopy(packageName, userId, cold)
    }

    override suspend fun shareText(packageName: String, text: String): ActionResult {
        target(packageName)
        return inner.shareText(packageName, text)
    }

    /** 指定されたアプリはここで表示できます。指定されていないアプリは、リストに従ってポートによって選択されます (誰がアプリを開くのかはポートだけが知っています)。 */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult {
        packageName?.let(::target)
        return inner.openLink(action, uri, packageName)
    }

    /** は職業の登録を待つだけで、画面の読み取りは行いません。対象の写真を開くと最初に表示され、制限されたアプリは待つ必要がなく、その場で拒否されます。 */
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        target(packageName)
        return inner.awaitAppFree(packageName, userId, timeoutMs)
    }

    /** によって引き継がれたアプリは、リストに従ってポートによって選択されます (誰がアプリを受け取るかはポートのみが知っています)。終了後のフロントは通常通り執行入口にてチェックを行っております。 */
    override suspend fun systemAction(action: SystemAction) = inner.systemAction(action)

    // ---------- アプリのインターフェースには触れないでください。そのまま渡してください ----------
    override suspend fun systemCommand(command: String) = inner.systemCommand(command)
    override suspend fun listApps() = inner.listApps()
    override suspend fun otherProfiles() = inner.otherProfiles()
    override suspend fun appCopies() = inner.appCopies()
    override val afterTask: AfterTaskMarks? get() = inner.afterTask

    companion object {
        /** 認識できないウィンドウがある場合、どれくらいの頻度で再質問する必要があります(新しく開いたウィンドウを読み込むまでに時間がかかります)。 */
        const val RETRY_MS = 100L

        /** ウィンドウが変更されていない場合にチェック結果を使用できる最大時間。これは、ウィンドウ イベントが遅れて発生する短い期間をカバーします。 */
        const val CHECK_TTL_MS = 1_000L
    }
}

/**
 * この画面の各アプリ ウィンドウの所有権。ルールは同じポートのウィンドウ プローブです。アプリ ウィンドウがある場合は、そのパッケージ名です (ルート ノードを読み取れない場合は null)。
 * アプリウィンドウがない場合はフロントエンドアプリが認識され、アプリウィンドウがない場合は画面は空（空リスト）となります。ポートがウィンドウ レベル (Web ページ、テスト ポート) に指定されていない場合、ポートは null になり、呼び出し元は個別に問い合わせます。
 */
internal fun ScreenSnapshot.accessPackages(): List<String?>? {
    val apps = windows.filter { it.type == "application" }
    if (apps.isNotEmpty()) return apps.map { it.packageName }
    if (hasWindow) return null
    return listOfNotNull(unreadableApp)
}
