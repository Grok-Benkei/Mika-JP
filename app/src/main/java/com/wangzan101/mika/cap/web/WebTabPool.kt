package com.wangzan101.mika.cap.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.cap.web.TabInfo
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import com.wangzan101.mika.core.cap.web.ExternalLink as Link
import com.wangzan101.mika.cap.web.cdp.WebDevtools

/** タグ プールで何が起こったか: 新しく開かれた、閉じられた (理由)、クラッシュした、ジャンプ アプリ リンクがブロックされた、メモリ リサイクル後に元に戻すときにリロードされました。 */
sealed interface PoolEvent {
    data class NewTab(val tabId: String, val taskId: String, val fromPopup: Boolean) : PoolEvent
    /**
     * [reason]:closed は自動的に閉じます。limit は上限を超え、最も長く未使用のものを閉じます。task_end タスクは終了します。popup_other_site ポップアップ ウィンドウは別の Web サイトに移動します。
     * 返却時には借用したタグ([WebTabPool.attachExternal])が返却され、タスク終了時にバックグラウンドに残されたタグ([WebTabPool.release])は保持されます(これら2つのタグ自体は無関係です)。
     */
    data class Closed(val tabId: String, val reason: String) : PoolEvent
    data class Crashed(val tabId: String) : PoolEvent
    data class ExternalLink(val tabId: String, val link: Link) : PoolEvent
    data class Reloaded(val tabId: String) : PoolEvent
}

/**
 * Web ページ タグ プール: 各タスクには最大で [perTask] タグが含まれます。すべてのタスクを合わせた上限は最大 [budget] (タスク間で共有)。
 * これを超えた場合、最も長期間使用されていないタスクをオフにします。他にオフにできるタスクがない場合は、最も長い間使用されていないタスクをオフにします。
 * 同時に、最近開いたタブまたは切り替えたタブのみが描画のために [host] にハングされ、残りは通常どおりバックグラウンドで実行されます (ページにはバックグラウンド タブが表示されます)。
 * ページにポップアップする新しいウィンドウは、ユーザーが (ジェスチャーで) クリックした場合にのみプール内の新しいタブを開きます。また、同じ Web サイトのみを使用できます。アプリへのリンクはジャンプせず、[PoolEvent.ExternalLink] だけがジャンプします。
 * レンダリング プロセスがクラッシュしました。タグを破棄し、[PoolEvent.Crashed] を送信し、次回 URL を開いたときに新しいタグを開きます。
 * システム メモリが不足している場合は、バックグラウンド タブをオフにしてアドレスを書き留め、元に戻すときにアドレスをリロードします。ページが読み込まれた後、タスクが終了し、アプリがバックグラウンドに入り、Cookie がディスクに書き込まれ、ログインは失われません。
 * 借用ラベル ([attachExternal]、ユーザーがブラウザ ページで見ているもの): タスクの現在のラベルとして使用されますが、プールによって所有されていません - [host] にリンクされていません (ユーザー インターフェイス内にあります)。
 * は上限としてカウントされず、空き領域やメモリのリサイクルによってオフになりません。プールはそのタスクを受け入れません (ダウンロード、ポップアップ ウィンドウ、ログイン検出は元の所有者によって管理されます)。オフにすると、タスクの終了時に返されるだけで ([detachExternal])、破棄されません。
 *
 * が WebView に移動するメソッド ([open]、[switchTo]、[close]、[closeAll]、[list]、[onTrimMemory]) はすべてメインスレッドで呼び出されます。
 * [current]、[isLive]、[crashedTab] どのネジでも調整可能です。
 */
class WebTabPool(
    private val context: Context,
    private val host: WebHost,
    private val perTask: Int = 4,
    /** 各タスクが共有するタグの総数の上限。デフォルトはプロセス中のものです。 */
    private val budget: TabBudget = WebOwner.of(context).tabBudget,
    private val assetLoaders: List<WebViewAssetLoader> = emptyList(),
    private val registry: SiteRegistry? = null,
    /** プール内のタグの DevTools セッション エントリ。デフォルトはプロセス中のものです。 */
    private val devtools: WebDevtools = WebDevtools.of(context),
) : WebTab.Listener, Closeable, TabBudget.Member {
    /**
     * [background]: ページの読み取りに使用される一時的なバックグラウンド タグ。これは現在のタグではなく、タグ リストには入らず、上限としてカウントされず、読み取り後に閉じられます ([openBackground] を参照)。
     * [external]: 借用したタグ ([attachExternal] を参照) は上限としてカウントされず、閉じるときにのみ返されます。
     */
    private class Entry(
        val id: String, val taskId: String, var tab: WebTab?, var url: String?, var title: String, var lastUsed: Long,
        val background: Boolean = false, val external: Boolean = false,
    ) {
        /** は、タスクごとおよびグローバルの上限にカウントされる通常のタグであり、スペースを解放することでオフにできます。 */
        val counted: Boolean get() = !background && !external
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val currentByTask = HashMap<String, String>()
    private val crashedByTask = HashMap<String, String>()
    /** は、処理 (引き継ぎ) のためにユーザーに渡されているタグです。タスクはタグを操作できず、プールも閉じず、リサイクルも行いません。 */
    private val locked = HashSet<String>()
    private var counter = 0
    private val main = Handler(Looper.getMainLooper())
    /** 実行待ちの各ラベルのログイン状態検出 (メインスレッドがアクティブ)。 */
    private val loginProbes = HashMap<String, Runnable>()

    private val _events = MutableSharedFlow<PoolEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PoolEvent> = _events.asSharedFlow()

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = this@WebTabPool.onTrimMemory(level)
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = this@WebTabPool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    init {
        context.applicationContext.registerComponentCallbacks(memory)
        budget.join(this)
    }

    /** はタスク内に新しいタブを開き、そこに切り替えます。 [url] は、空でない場合にロードを開始します (ロードが完了するまで待機しません)。 */
    fun open(taskId: String, url: String?): WebTab {
        checkMain()
        makeRoom(taskId, keep = null)
        val tab = create(taskId)
        url?.let { u ->
            val refused = WebUrlPolicy.checkNavigable(u)
            if (refused == null) tab.view.loadUrl(u) else Log.w(TAG, "refused to open a non-web address: $refused")
        }
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return tab
    }

    /**
     * は、ページ読み取り用の一時的なバックグラウンド タグを開きます。切断なし、ホストへのハングなし (誰もタイプセットしません。一度 [width] × [height] を押して測定すると、ページにはビューポートが表示されます)。
     * は [list] には表示されず、タスクごとおよびグローバルにラベルの上限をカウントせず (そのうちのいくつかは呼び出し元によって同時に制限されます)、スペース割り当てやメモリ再利用によってオフになることはありません。使用後は [close] に電話してください。
     */
    fun openBackground(taskId: String, width: Int, height: Int): WebTab {
        checkMain()
        val id = synchronized(this) { "t${++counter}" }
        val tab = newTab(id, taskId)
        synchronized(this) { entries[id] = Entry(id, taskId, tab, null, "", budget.tick(), background = true) }
        val w = View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        tab.view.measure(w, h)
        tab.view.layout(0, 0, width, height)
        return tab
    }

    @Synchronized
    fun current(taskId: String): WebTab? = currentByTask[taskId]?.let { entries[it]?.tab }

    /**
     * は、プールによって管理されていないタグ (ユーザーがブラウザ ページで見ているタグ) をタスク [taskId] に現在のタグとして貸し出します。その後、Web ツールがそれを読み取って操作します。
     * はプール内と同じラベルです。 [host] にはリンクされておらず、上限としてカウントされず、監視も引き継がれません。同じタグが借用されている場合は、カットオーバーされるだけです。
     * 閉じられている、クラッシュしている、または番号がプール内のタグと衝突しているものは借用できず、false を返します。メインスレッド。
     */
    fun attachExternal(taskId: String, tab: WebTab): Boolean {
        checkMain()
        if (tab.destroyed || tab.crashed) return false
        val entry = synchronized(this) {
            val existing = entries[tab.id]
            if (existing != null && (!existing.external || existing.tab !== tab || existing.taskId != taskId)) return false
            existing ?: Entry(tab.id, taskId, tab, tab.view.url, tab.view.title.orEmpty(), 0, external = true).also {
                entries[tab.id] = it
                crashedByTask.remove(taskId)
            }
        }
        makeCurrent(entry)
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return true
    }

    /**
     * 借用したラベル [tabId] を返します。プールから削除します。ビューを破壊したり移動したりしないでください。現在のラベルを、このタスクで最近使用した別のラベルに置き換えます (そうでない場合、現在のラベルはありません)。
     * は借用されていないため、false を返します。メインスレッド。
     */
    fun detachExternal(tabId: String): Boolean {
        checkMain()
        if (synchronized(this) { entries[tabId]?.external != true }) return false
        remove(tabId, PoolEvent.Closed(tabId, "returned"), force = true)
        return true
    }

    /**
     * は、元々プールによって管理されていなかったタグ [tab] を受け入れ、タスク [taskId] の通常のタグになります (ページを貸していたブラウザ ページは閉じられ、ページは継続使用のためにタスクに渡されます)。
     * は上記のものを受け入れ、上限を計算し、空にすることができ、ミッションが終了すると他のものと一緒に閉じられます。 [current] またはタスクに現在のラベルがない場合は、それに切り替えてホスト上でハングします。それ以外の場合は、バックグラウンドで実行されます。
     * は閉じられ、クラッシュしました。番号はすでにプール内にあり、収集できないため、false を返します。メインスレッド。
     */
    fun adopt(taskId: String, tab: WebTab, current: Boolean): Boolean {
        checkMain()
        if (tab.destroyed || tab.crashed || synchronized(this) { entries.containsKey(tab.id) }) return false
        makeRoom(taskId, keep = null)
        (tab.view.parent as? android.view.ViewGroup)?.removeView(tab.view)
        tab.ownerTaskId = taskId
        tab.listener = this
        val entry = Entry(tab.id, taskId, tab, tab.view.url, tab.view.title.orEmpty(), budget.tick())
        val makeIt = synchronized(this) {
            entries[tab.id] = entry
            current || currentByTask[taskId] == null
        }
        if (makeIt) makeCurrent(entry)
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return true
    }

    /**
     * タスクの最後に残すタグ [tabId] を渡します ([PageKeeper] を参照): プールから削除し、破棄せず、ホストから削除せず、他のタグをぶら下げないでください。
     * (現在のラベルの場合、このタスクには現在のラベルがありません);その後、そのタスクは引き継がれなくなり、上限としてカウントされなくなり、タスクとともに閉じられなくなります。送信終了（保持）。
     * 借用、一時背景、メモリリサイクル、クラッシュ、存在しないため引き継げず、nullが返されます。メインスレッド。
     */
    fun release(tabId: String): WebTab? {
        checkMain()
        val tab = synchronized(this) {
            val e = entries[tabId]?.takeIf { it.counted } ?: return null
            val t = e.tab?.takeIf { !it.destroyed && !it.crashed } ?: return null
            entries.remove(tabId)
            locked -= tabId
            if (currentByTask[e.taskId] == tabId) currentByTask.remove(e.taskId)
            t
        }
        loginProbes.remove(tabId)?.let(main::removeCallbacks)
        tab.listener = null
        emit(PoolEvent.Closed(tabId, "kept"))
        return tab
    }

    /** [tabId] は借用したタグです ([attachExternal] を参照)。 */
    @Synchronized
    fun isExternal(tabId: String): Boolean = entries[tabId]?.external == true

    @Synchronized
    fun isLive(tabId: String): Boolean = entries[tabId]?.tab != null

    /** テイクオーバー開始: [unlock] までタグをロックします。 */
    @Synchronized
    fun lock(tabId: String) {
        locked += tabId
    }

    @Synchronized
    fun unlock(tabId: String) {
        locked -= tabId
    }

    @Synchronized
    fun isLocked(tabId: String): Boolean = tabId in locked

    /** このタスクが最近クラッシュし、それ以来新しいタブが開かれていないタブ。 nullではありません。 */
    @Synchronized
    fun crashedTab(taskId: String): String? = crashedByTask[taskId]

    /** このタスクの特定のラベルに切り替えます。このタスクのものではない場合、または存在しない場合は、null になります。メモリによって再利用されたタグは、[PoolEvent.Reloaded] と同時に再ロードされます。 */
    fun switchTo(taskId: String, tabId: String): WebTab? {
        checkMain()
        val entry = synchronized(this) { entries[tabId]?.takeIf { it.taskId == taskId } } ?: return null
        var reloaded = false
        val tab = entry.tab ?: newTab(entry.id, taskId).also { t ->
            synchronized(this) { entry.tab = t }
            entry.url?.let { t.view.loadUrl(it) }
            reloaded = true
        }
        makeCurrent(entry)
        if (reloaded) emit(PoolEvent.Reloaded(tabId))
        return tab
    }

    /** ラベルを閉じます (タスクに関係なく)。タスク自体を閉じるには、close(taskId, tabId) を使用します。 */
    fun close(tabId: String) = remove(tabId, PoolEvent.Closed(tabId, "closed"))

    /** タスクは自身のラベルを閉じます。このタスク用ではない場合、存在しない場合、または処理のためにユーザーに渡されている場合は、 false を返し、閉じません。 */
    fun close(taskId: String, tabId: String): Boolean {
        checkMain()
        if (synchronized(this) { entries[tabId]?.taskId != taskId || tabId in locked }) return false
        close(tabId)
        return true
    }

    /** サイトを終了します。Cookie を期限切れにし、ページ ストレージをクリアし、サイト レジストリから削除します (ベスト エフォート、[WebLogin] を参照)。期限切れの Cookie の数を返します。 */
    fun clearSiteData(host: String): Int {
        checkMain()
        return WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry).logout(host)
    }

    /** すべて終了し、Web ページのデータをクリアします。開いているタブは閉じられていませんが、ログインしていません。 */
    fun clearAll() {
        checkMain()
        val views = synchronized(this) { entries.values.mapNotNull { it.tab?.view } }
        WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry).logoutAll(views, WebViewDatabase.getInstance(context))
    }

    /** タスクは完了しました。開いたタブをすべて閉じて (借用したタブは返します)、Cookie をディスクに書き込みます (タブを閉じてもログイン ステータスは失われません)。 */
    fun closeAll(taskId: String) {
        checkMain()
        val ids = synchronized(this) { entries.values.filter { it.taskId == taskId }.map { it.id } }
        ids.forEach { remove(it, PoolEvent.Closed(it, if (isExternal(it)) "returned" else "task_end"), force = isExternal(it)) }
        synchronized(this) { crashedByTask.remove(taskId) }
        flushCookies()
    }

    /**
     * タスク [taskId] が終了しました。 [keep] ページを保持するには、まずページを渡して ([release])、次にこのタスクの他のタブを閉じます ([closeAll])。
     * によって渡されたラベルは、呼び出し元によって引き継がれます。 [keep] が null または引き継がれない場合は、null (通常どおりクローズ) になります。メインスレッド。
     */
    fun closeKeeping(taskId: String, keep: String?): WebTab? {
        checkMain()
        val tab = keep?.takeIf { synchronized(this) { entries[it]?.taskId == taskId } }?.let(::release)
        closeAll(taskId)
        return tab
    }

    /** このタスクのタグ (開いた順)。 */
    fun list(taskId: String): List<TabInfo> {
        checkMain()
        val (rows, cur) = synchronized(this) { entries.values.filter { it.taskId == taskId && !it.background } to currentByTask[taskId] }
        return rows.map { e ->
            val live = e.tab
            TabInfo(e.id, live?.view?.url ?: e.url.orEmpty(), live?.view?.title ?: e.title, e.id == cur)
        }
    }

    /**
     * メモリが不足している場合 (RUNNING_LOW 以上、またはバックグラウンドでの解放が要求されている場合)、各タスクの非現行ラベルをオフにして、アドレスのみを記憶します。
     * アプリはバックグラウンド (UI_HIDDEN) に入るときに Cookie をディスクに書き込みます。
     */
    fun onTrimMemory(level: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onTrimMemory(level) }
            return
        }
        @Suppress("DEPRECATION")
        val pressure = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushCookies()
        if (!pressure) return
        val victims = synchronized(this) {
            entries.values.filter { it.tab != null && it.counted && currentByTask[it.taskId] != it.id && it.id !in locked }
        }
        for (e in victims) {
            val tab = e.tab ?: continue
            e.url = tab.view.url ?: e.url
            e.title = tab.view.title ?: e.title
            synchronized(this) { e.tab = null }
            tab.destroy()
            Log.i(TAG, "tab ${e.id} released under memory pressure (level $level)")
        }
    }

    /** はすべてのタスク タグをオフにし、システム メモリ コールバックを受信しなくなります。 */
    override fun close() {
        checkMain()
        val tasks = synchronized(this) { entries.values.map { it.taskId }.toSet() }
        tasks.forEach(::closeAll)
        budget.leave(this)
        context.applicationContext.unregisterComponentCallbacks(memory)
    }

    // ---------- グローバル上限値 ----------

    @Synchronized
    override fun tabCount(): Int = entries.values.count { it.counted }

    @Synchronized
    override fun oldestSpare(except: String?): Pair<String, Long>? =
        entries.values.filter { it.id != except && it.counted && it.id !in locked }.minByOrNull { it.lastUsed }?.let { it.id to it.lastUsed }

    override fun evictForBudget(tabId: String) = remove(tabId, PoolEvent.Closed(tabId, "limit"))

    // ---------- ラベル記載事項 ----------

    override fun onPageFinished(tab: WebTab) {
        val url = tab.view.url
        synchronized(this) {
            entries[tab.id]?.let {
                it.url = url
                it.title = tab.view.title.orEmpty()
            }
        }
        url?.let { u -> WebUrlPolicy.host(u)?.takeIf { u.startsWith("http") }?.let { registry?.visited(it) } }
        flushCookies()
        scheduleLoginProbe(tab, 0)
    }

    /**
     * ページ スクリプトのほとんどは、読み込みが完了した後にアカウント領域を描画します。ページが停止するまで待ってからログイン ステータスを確認すると、登録フォームはページの実際のステータスに従います。
     * が再度ロード (ジャンプ) された場合は、タイマーを再起動します。タスクによって使用され、ホスト上でハングされているタグのみを確認します。アドレスがまだ変更中または読み込み中の場合は、最大でも数回、もう一度待ちます。メインスレッド。
     */
    private fun scheduleLoginProbe(tab: WebTab, attempt: Int) {
        val r = registry ?: return
        loginProbes.remove(tab.id)?.let(main::removeCallbacks)
        val urlThen = tab.view.url
        val run = Runnable {
            loginProbes.remove(tab.id)
            if (tab.destroyed) return@Runnable
            val current = synchronized(this) { currentByTask[tab.ownerTaskId] == tab.id }
            if (!current || !tab.view.isAttachedToWindow) return@Runnable
            if (tab.loading || tab.view.url != urlThen) {
                if (attempt < LOGIN_PROBE_RETRIES) scheduleLoginProbe(tab, attempt + 1)
                return@Runnable
            }
            LoginProbe.probe(tab) { host, state -> r.loginObserved(host, state) }
        }
        loginProbes[tab.id] = run
        main.postDelayed(run, LOGIN_PROBE_DELAY_MS)
    }

    override fun onCrashed(tab: WebTab) {
        // はコールバックの外で破棄されます。 WebView 自身のコールバック スタックで destroy を呼び出さないでください。
        main.post {
            // 一時的な背景ラベルが折りたたまれました。タスクではないラベルが折りたたまれています。ページを読んだときに自分で報告してください。
            synchronized(this) { if (entries[tab.id]?.background != true) crashedByTask[tab.ownerTaskId] = tab.id }
            remove(tab.id, PoolEvent.Crashed(tab.id), force = true)
        }
    }

    /** ページによってトリガーされたダウンロードはそれに渡されます (タスクによってワークスペースに接続されます)。空の場合は無視されます。メインスレッドのコールバック: (ラベル、URL、UA、Content-Disposition、タイプ)。 */
    var downloadHandler: ((WebTab, String, String, String?, String?) -> Unit)? = null

    override fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
        val h = downloadHandler
        if (h == null) Log.i(TAG, "download ignored on tab ${tab.id}") else h(tab, url, userAgent, contentDisposition, mime)
    }

    override fun onExternalLink(tab: WebTab, link: Link) {
        emit(PoolEvent.ExternalLink(tab.id, link))
    }

    override fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
        // ユーザー操作のないポップアップウィンドウは開きません。
        if (!isUserGesture) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        makeRoom(tab.ownerTaskId, keep = tab.id)
        val popup = create(tab.ownerTaskId)
        popup.popupOpener = tab.view.url ?: ""
        transport.webView = popup.view
        resultMsg.sendToTarget()
        emit(PoolEvent.NewTab(popup.id, tab.ownerTaskId, fromPopup = true))
        return true
    }

    override fun onPopupRefused(tab: WebTab, url: String) {
        Log.i(TAG, "popup ${tab.id} refused: other site ${url.take(80)}")
        main.post { remove(tab.id, PoolEvent.Closed(tab.id, "popup_other_site")) }
    }

    // ---------- 内部 ----------

    private fun create(taskId: String): WebTab {
        val id = synchronized(this) { "t${++counter}" }
        val tab = newTab(id, taskId)
        val entry = Entry(id, taskId, tab, null, "", 0)
        synchronized(this) {
            entries[id] = entry
            crashedByTask.remove(taskId)
        }
        makeCurrent(entry)
        return tab
    }

    private fun newTab(id: String, taskId: String) = WebTab(context, id, taskId, assetLoaders, devtools).also { it.listener = this }

    private fun makeCurrent(entry: Entry) {
        synchronized(this) {
            currentByTask[entry.taskId] = entry.id
            entry.lastUsed = budget.tick()
        }
        // 借用したタグはユーザー インターフェイスに残ります。元々ホスト上にハングしていたものは残ります (バックグラウンドで通常どおり実行されます)
        if (!entry.external) entry.tab?.let { host.attach(it) }
    }

    /**
     * このタスク用の場所を作成します。このタスクがいっぱいになり、長期間使用されていない場合は、タスクを閉じます。すべてのタスクを結合する場合は、まずこのタスクを閉じて、他の場所を長時間使用しないで閉じないでください。
     * [keep] はロック (引き継ぎ) とは関係ありませんが、それでもカウントされます。本当に閉じるものが何もない場合は、そのまま閉じてください (ユーザーの手でページに触れるよりも、わずかにそれを超えたほうがよいでしょう)。
     */
    private fun makeRoom(taskId: String, keep: String?) {
        while (true) {
            val (own, perTaskFull) = synchronized(this) {
                val all = entries.values.filter { it.taskId == taskId && it.counted }
                all.filter { it.id != keep && it.id !in locked }.minByOrNull { it.lastUsed } to (all.size >= perTask)
            }
            when {
                perTaskFull && own != null -> remove(own.id, PoolEvent.Closed(own.id, "limit"))
                budget.full() && own != null -> remove(own.id, PoolEvent.Closed(own.id, "limit"))
                budget.full() -> if (!budget.evictOldest(except = keep)) return
                else -> return
            }
        }
    }

    /** はタグを閉じます。 [force] でない限り、引き継がれている (ロックされている) タグは閉じません (レンダリング プロセスが終了し、借用したタグが返されます)。借用物は撤去されるだけであり、破棄はされません。 */
    private fun remove(tabId: String, event: PoolEvent, force: Boolean = false) {
        checkMain()
        val (entry, next) = synchronized(this) {
            if (tabId in locked && !force) {
                Log.i(TAG, "tab $tabId is with the user, not closed")
                return
            }
            locked -= tabId
            val e = entries.remove(tabId) ?: return
            var nextEntry: Entry? = null
            if (currentByTask[e.taskId] == tabId) {
                nextEntry = entries.values.filter { it.taskId == e.taskId && !it.background }.maxByOrNull { it.lastUsed }
                if (nextEntry == null) currentByTask.remove(e.taskId) else currentByTask[e.taskId] = nextEntry.id
            }
            e to nextEntry
        }
        if (!entry.external) entry.tab?.let {
            host.detach(it.view)
            it.destroy()
        }
        next?.let { n ->
            // 次の現在のラベルがメモリによって再利用されている場合、タスクがそのラベルに切り替わったときに再ロードされます。借りたものは利用者が保管します。
            if (!n.external) n.tab?.let { host.attach(it) }
        }
        emit(event)
    }

    private fun flushCookies() {
        runCatching { WebTab.cookieManager().flush() }
    }

    private fun emit(e: PoolEvent) {
        _events.tryEmit(e)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebTabPool must be used on the main thread" }

    private companion object {
        const val TAG = "MikaWebTabPool"
        const val LOGIN_PROBE_DELAY_MS = 1_500L
        const val LOGIN_PROBE_RETRIES = 3
    }
}
