package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import com.wangzan101.mika.cap.web.LoginProbe
import com.wangzan101.mika.cap.web.LoginState
import com.wangzan101.mika.cap.web.SiteRegistry
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.core.cap.web.ExternalLink
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.uistate.PageDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ブラウザ ページが現在開いている Web ページ。 [desktop]: ラベルは現在、Web ページのコンピューター バージョンを使用しています。 [loginFor]: 今回は「ログイン for ミカ」、ログインする Web サイトです
 * (作業中は Mega として開きます。[BrowserIdentity] を参照)、通常のブラウジングは null です。
 */
data class BrowserPage(
    val url: String,
    val title: String,
    val progress: Int,
    val loading: Boolean,
    val canBack: Boolean,
    val canForward: Boolean,
    val desktop: Boolean = false,
    val loginFor: String? = null,
)

/**
 * ユーザー独自のブラウザ (サイドバー「ブラウザ」): 表示される [WebTab]、エージェントのラベルと同じ Web ページ構成 (Cookie、Web ページ ストレージ)。 ここでは
 * がログインしており、作業中はMikaもログインしています。使用する Web ページのバージョンは [BrowserIdentity] によって決まります。通常はモバイル バージョンがデフォルトですが、Web サイトを押すとコンピューター バージョンに変更できます。
 * ([modes] に記録)、ミカが動作しているときのバージョンに応じて「ミカにログイン」。ルールはこのラベル (WebDevtools.setModePolicy) にのみインストールされます。
 * エージェントのタグは影響を受けません。
 *
 * はどのタスクのタグ プールにも入りません。また、[com.wangzan101.mika.cap.web.WebHandover] のテイクオーバーも引き継ぎません。エージェント タグとテイクオーバーは、相互に影響を与えることなく通常どおりに進行します。
 * タグはプロセス内に配置され (画面の切り替えとページの再構築時に同じ WebView が使用されます)、ブラウザ ページを離れるときに閉じられます。インターフェイスのインスタンスが複数ある場合、ブラウザ ページには同時に複数の場所が存在する可能性があります。
 * ビューをハングする場所と Web ページを閉じる場所については、[PageHosts] を参照してください。
 * WebView の環境は [MutableContextWrapper] です。インターフェースにぶら下がっているときは、そのアクティビティ (ドロップダウン ボックス、メニューを長押しして要求) に変更し、それを外すときは、アプリに戻ります。
 * ページを開く前に、[WebUrlPolicy.checkNavigable] を参照してください。訪問したページは [recent] に記録されます。ページを停止した後、ログイン ステータスを確認し、サイト登録フォームに記録します。
 * ユーザーがページ上でミカに質問すると、彼が見ているページはその会話のタスクを借用します。現在の Web ページのラベル ([loans] を参照): ページが切り替わってポップアップすると、それに応じてページも変化します。
 * ページが閉じられると、継続使用のためにタスクに渡され、破棄されません。ユーザーがページ ([userTouched]) にタッチすると、そのページに依存していたタスクが通知されます。
 * はメインスレッドでのみ呼び出されます。
 */
class UserBrowser private constructor(private val app: Context) {
    val sites: SiteRegistry = WebSites.registry(app)
    private val dir = File(app.filesDir, "web")
    val recent = RecentPages(dir)
    val favorites = BrowserFavorites(dir)
    val looks = SiteLooks(dir)
    private val modes = UserSiteModes(dir)
    /** が見ているページはタスクに貸与されています ([PageLoans] を参照)。 */
    val loans = PageLoans<WebTab>()
    /** タグ番号: それぞれが異なります (ページとそのポップアップ ウィンドウが同じタスクに次々に貸される場合があります)。 */
    private var tabCount = 0

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wrapper = MutableContextWrapper(app)
    private var tab: WebTab? = null
    /** ページのポップアップ ウィンドウが開いている場合 ([tab] はポップアップ ウィンドウです)、開いているページを開きます。ポップアップ ウィンドウがない場合は null になります。 */
    private var opener: WebTab? = null
    private val hosts = PageHosts<ViewGroup, Context>()
    private var probe: Runnable? = null
    /** 「ミカのログイン」 ログインするためのウェブサイト;通常のブラウジングは null です。タグのバージョン ルールもバックグラウンド スレッドで読み取られます。 */
    @Volatile private var loginHost: String? = null

    private val _page = MutableStateFlow<BrowserPage?>(null)
    /** が null の場合、Web ページは開かれていません (スタート ページが表示されます)。 */
    val page: StateFlow<BrowserPage?> = _page.asStateFlow()

    private val _dialog = MutableStateFlow<PageDialog?>(null)
    /** ページでは、ユーザーの応答を待つボックスがポップアップ表示されます。 */
    val dialog: StateFlow<PageDialog?> = _dialog.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** はユーザーへのリマインダー (何かがブロックされた、ページがクラッシュした) です。表示後、[noticeShown]を調整してください。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * は、アドレス バーまたは検索ボックスで文を開きます ([AddressInput] を参照)。開くことができず、現在のページが移動されない場合にユーザーに返される理由。
     */
    fun submit(input: String): String? {
        val url = AddressInput.resolve(input) ?: return null
        return open(url)
    }

    /** 通常の閲覧用に URL を開きます。開けない場合は理由を返します。 */
    fun open(url: String): String? = load(url, null)

    /** よく使うWebサイトを開く: まだログインしていない場合は、「Mikaにログイン」に進み、Mikaが動作しているときと同じバージョンで開きます。 */
    fun openFavorite(site: FavoriteSite): String? =
        load("https://${site.host}/", site.host.takeIf { BrowserIdentity.loginFlow(site) })

    /**
     * 現在の Web サイトをコンピューター バージョン ([desktop]) に変更するか、モバイル バージョンに戻します。次回も同じようにしてください。 「ミカのログイン」としてカウントされなくなります。
     * 新しいバージョンに従って現在のページを再度開きます。
     */
    fun setDesktop(desktop: Boolean) {
        val t = tab ?: return
        val url = currentUrl() ?: return
        val host = WebUrlPolicy.host(url) ?: return
        modes.set(host, desktop)
        loginHost = null
        navigate(t, url)
    }

    /**
     * 「ログインミカ」の「ログインOK」をクリック： ページを見てください。ログインしていない場合は、プロンプトが表示されます。ログインしていることがわかる場合、またはログインしていることがわからない場合 (ユーザーを信頼できる場合)、プロンプトが表示されます。
     * この Web サイトをログイン済みとしてマークし、ホームページに戻ります。ページの読み込み中のため表示できない場合は、[CONFIRM_WAIT_MS] まで待ってから、[表示できない] をクリックします。
     */
    fun confirmLogin() {
        val t = tab ?: return
        val site = loginHost ?: return
        var done = false
        fun finish(state: LoginState) {
            if (done || t !== tab || t.destroyed) return
            done = true
            if (state == LoginState.SignedOut) {
                _notice.value = tr(R.string.browser_not_logged_in)
                return
            }
            sites.markLoggedIn(site)
            currentUrl()?.let(WebUrlPolicy::host)?.takeIf { it != site && BrowserFavorites.siteKey(it) == BrowserFavorites.siteKey(site) }?.let(sites::markLoggedIn)
            WebTab.cookieManager().flush()
            Log.i(TAG, "browser login confirmed for $site ($state)")
            _notice.value = LOGGED_IN_NOTICE
            closePage()
        }
        val timeout = Runnable { finish(LoginState.Unknown) }
        main.postDelayed(timeout, CONFIRM_WAIT_MS)
        LoginProbe.probe(t) { _, state ->
            main.post {
                main.removeCallbacks(timeout)
                finish(state)
            }
        }
    }

    private fun load(url: String, login: String?): String? {
        refusal(url)?.let { return it }
        loginHost = login
        val t = tab ?: newTab().also {
            tab = it
            place()
        }
        _page.value = BrowserPage(url, "", 0, true, false, false, desktop = !t.devtools.isMobile(t), loginFor = login)
        navigate(t, url)
        return null
    }

    private fun navigate(t: WebTab, url: String) {
        scope.launch {
            // にはエージェントと同じラベルが付いています。ナビゲートする前に、このラベルのルールに従ってデスクトップまたはモバイル バージョンの設定とシミュレーションを変更します (長くても少しの間待ちます)。
            t.devtools.beforeLoad(t, url)
            if (!t.destroyed) t.view.loadUrl(url)
        }
    }

    private fun currentUrl(): String? = tab?.view?.url?.takeIf { it.startsWith("http") } ?: _page.value?.url

    fun back() { tab?.view?.takeIf { it.canGoBack() }?.goBack() }
    fun forward() { tab?.view?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { tab?.view?.reload() }
    fun stop() {
        tab?.view?.stopLoading()
        refresh()
    }

    /** システムリターンキー: Web ページに戻れる場合は戻ります。ポップアップ ウィンドウが開けない場合は閉じます。それ以外の場合は、Web ページを閉じて、開始ページに戻ります。開いている Web ページがない場合は false を返します。 */
    fun systemBack(): Boolean {
        val v = tab?.view ?: return false
        if (v.canGoBack()) v.goBack() else if (!closePopup()) closePage()
        return true
    }

    /** Web ページを閉じて、ホームページに戻ります。タスクに貸与されたタグは、その後の使用のためにタスクに与えられます (タスクがタグを受け入れた後は破棄されません)。 */
    fun closePage() {
        probe?.let(main::removeCallbacks)
        probe = null
        loginHost = null
        opener?.let { o ->
            opener = null
            if (!loans.closing(o)) o.destroy() else handedOff(o)
        }
        tab?.let { t ->
            tab = null
            if (!loans.closing(t)) t.destroy() else handedOff(t)
        }
        loans.withdraw()
        _page.value = null
        _dialog.value = null
    }

    // タスクに割り当てられたラベルはここになくなり (ビューはタスクによってその Web ページ表示にリンクされました)、環境はアプリに戻ります (インターフェイスは残りません)。
    private fun handedOff(t: WebTab) {
        wrapper.baseContext = app
        Log.i(TAG, "browser page ${t.id} handed to the task that borrowed it")
    }

    // ---------- このページでミカさんに質問してください ----------

    /** あなたが今見ているページ（ミカの説明用）;開かれていない Web ページは null です。 */
    fun pageInfo(): com.wangzan101.mika.core.cap.web.AttachedPage.Page? {
        val url = currentUrl()?.takeIf { it.startsWith("http") } ?: return null
        val title = tab?.view?.title?.takeIf { it != url }.orEmpty().ifEmpty { _page.value?.title.orEmpty() }
        return com.wangzan101.mika.core.cap.web.AttachedPage.Page(url, title)
    }

    /** は、ページ上でユーザーが選択した単語を読み取り、それを [onText] に渡します (選択されていないか読み取ることができない場合は null)。 */
    fun readSelection(onText: (String?) -> Unit) {
        val t = tab ?: return onText(null)
        t.view.evaluateJavascript(SELECTION_SCRIPT) { raw ->
            val text = runCatching { org.json.JSONArray("[$raw]").optString(0) }.getOrNull()
            onText(text?.takeIf { raw != "null" && it.isNotBlank() })
        }
    }

    /** ユーザーがこのページで質問しました。このページは会話に貸与されました。 [conversationId] 次に実行するタスク。 */
    fun lend(conversationId: String) = loans.offer(conversationId)

    /** ダイアログ [conversationId] タスク [runKey] 実行時に貸し出されたページを取得します。同意されていない場合、ページがなくなっている場合、または貸し出されている場合は null になります。 */
    fun claim(conversationId: String, runKey: String): PageLoans.Loan<WebTab>? =
        loans.claim(conversationId, runKey, tab?.takeIf { !it.destroyed && !it.crashed })

    /** ユーザーは、見ていたページにタッチしました (ミカ自身が送信した入力ではなく、実際のタッチです)。 */
    fun userTouched() {
        tab?.let(loans::touched)
    }

    /** [回答] ページのポップアップ ボックス。 */
    fun answerDialog(accept: Boolean, text: String?) {
        tab?.dialogs?.answer(accept, text)
        _dialog.value = tab?.dialogs?.current
    }

    fun noticeShown() {
        _notice.value = null
    }

    /** ブラウザー ページに使用される場所 (インターフェイスのインスタンス上)。 [openHost]、[setContainer]、[hostStarted]、[closeHost]に与えられます。 */
    fun newHost(activity: Context): PageHosts.Host<ViewGroup, Context> = PageHosts.Host(activity)

    /** このポイントはブラウザ ページに入ります。 */
    fun openHost(host: PageHosts.Host<ViewGroup, Context>) {
        hosts.open(host)
        place()
    }

    /** この点はフロントデスクに戻ります。Web ページのビューがここでハングしています。 */
    fun hostStarted(host: PageHosts.Host<ViewGroup, Context>) {
        hosts.started(host)
        place()
    }

    /** ここの Web ページに残されているコンテナーは利用可能 ([container]) または欠落しています (null)。 */
    fun setContainer(host: PageHosts.Host<ViewGroup, Context>, container: ViewGroup?) {
        val old = host.container
        hosts.setContainer(host, container)
        if (old !== container) old?.removeAllViews()
        place()
    }

    /**
     * この時点でブラウザ ページが終了します。 Web ページが最後の位置で、画面の回転ではない場合 ([keepPage] が false)、Web ページを閉じます。
     * が最後のページではない場合は、Web ページを別の場所に残します (ビューを別の場所に変更します)。
     */
    fun closeHost(host: PageHosts.Host<ViewGroup, Context>, keepPage: Boolean) {
        host.container?.removeAllViews()
        if (hosts.close(host) && !keepPage) closePage() else place()
    }

    /**
     * Web ページ ビューをハングする場所 ([PageHosts.target]) にハングすると、環境がその場所のインターフェイスに変更されます。掛ける場所がない場合は外すと、環境がアプリに戻ります
     * (古いインターフェースは保持されません)。
     */
    private fun place() {
        val v = tab?.view
        val target = hosts.target()
        val container = target?.container
        if (v == null || container == null) {
            v?.let { (it.parent as? ViewGroup)?.removeView(it) }
            wrapper.baseContext = app
            return
        }
        if (v.parent === container) return
        (v.parent as? ViewGroup)?.removeView(v)
        wrapper.baseContext = target.activity
        container.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun refusal(url: String): String? {
        WebUrlPolicy.checkNavigable(url) ?: return null
        return if (WebUrlPolicy.external(url) != null) tr(R.string.browser_external_link) else tr(R.string.browser_bad_scheme)
    }

    private fun newTab(): WebTab = WebTab(wrapper, "browser${++tabCount}", OWNER).also { t ->
        t.view.settings.apply {
            builtInZoomControls = true
            displayZoomControls = false
        }
        // ユーザーは自分の手で見てクリックできます。コンピュータ版は必ずしもズームインする必要はなく、2 本の指でズームインできます。
        t.devtools.setHandOperated(t)
        t.devtools.setModePolicy(t) { host ->
            BrowserIdentity.mobile(loginForMika = loginHost != null, agentMobile = t.devtools.agentWantsMobile(host), userDesktop = modes.isDesktop(host))
        }
        t.listener = listener
    }

    private fun refresh() {
        val t = tab ?: return
        val v = t.view
        val url = v.url?.takeIf { it.isNotEmpty() && it != "about:blank" } ?: _page.value?.url ?: return
        _page.value = BrowserPage(
            url, v.title.orEmpty().takeIf { it != url }.orEmpty(), t.progress, t.loading, v.canGoBack(), v.canGoForward(),
            desktop = !t.devtools.isMobile(t), loginFor = loginHost,
        )
    }

    private val listener = object : WebTab.Listener {
        override fun onPageChanged(tab: WebTab) {
            if (tab !== this@UserBrowser.tab) return
            refresh()
            val url = tab.view.url
            if (url != null && url.startsWith("http")) tab.view.title?.let { recent.retitle(url, it) }
        }

        override fun onPageFinished(tab: WebTab) {
            if (tab !== this@UserBrowser.tab) return
            refresh()
            val url = tab.view.url ?: return
            if (!url.startsWith("http")) return
            recent.add(url, tab.view.title?.takeIf { it != url })
            WebTab.cookieManager().flush()
            readName(tab, url)
            scheduleProbe(tab, 0)
        }

        override fun onIcon(tab: WebTab, icon: Bitmap) {
            if (tab !== this@UserBrowser.tab) return
            val host = tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host) ?: return
            if (icon.width < MIN_ICON_PX || icon.height < MIN_ICON_PX) return
            val side = minOf(maxOf(icon.width, icon.height), MAX_ICON_PX)
            val scaled = runCatching { Bitmap.createScaledBitmap(icon, side, side, true) }.getOrNull() ?: return
            scope.launch {
                val targets = looks.iconTargets(host)
                val saved = withContext(Dispatchers.IO) {
                    targets.filter { h ->
                        runCatching {
                            val f = looks.iconFile(h)
                            f.parentFile?.mkdirs()
                            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }.getOrDefault(false)
                    }
                }
                saved.forEach(looks::iconSaved)
            }
        }

        override fun onDialog(tab: WebTab) {
            if (tab === this@UserBrowser.tab) _dialog.value = tab.dialogs.current
        }

        override fun onExternalLink(tab: WebTab, link: ExternalLink) {
            _notice.value = tr(R.string.browser_blocked_app_link)
        }

        override fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
            _notice.value = tr(R.string.browser_no_download)
        }

        override fun onCrashed(tab: WebTab) {
            if (tab !== this@UserBrowser.tab) return
            // はコールバックの外で破棄されます
            main.post {
                if (tab === this@UserBrowser.tab) closePage()
                _notice.value = tr(R.string.browser_crashed)
            }
        }

        /**
         * ページは新しいウィンドウ (target=_blank、window.open) を開く必要があります。ユーザーがクリックしたウィンドウのみが認識され、レイヤーは 1 つだけ開きます。現在のページの上に新しいウィンドウが表示されます。
         * 現在のページはそのままです ([opener]): 「サードパーティ アカウントでログイン」ポップアップ ウィンドウ。ログイン後、結果はそれを開いたページに返される必要があります。ページがなくなると白い画面で止まります。
         * ポップアップ ウィンドウが自動的に閉じたり、ポップアップ ウィンドウから退避できない場合は、開いたページに戻ります。
         */
        override fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture || tab !== this@UserBrowser.tab || opener != null) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = newTab()
            transport.webView = popup.view
            resultMsg.sendToTarget()
            (tab.view.parent as? ViewGroup)?.removeView(tab.view)
            opener = tab
            this@UserBrowser.tab = popup
            place()
            // タスクに貸与されたものは表示されているものです。それをポップアップ ウィンドウに置き換えます。
            loans.swapped(tab, popup)
            return true
        }

        override fun onCloseWindow(tab: WebTab) {
            if (tab === this@UserBrowser.tab) closePopup()
        }
    }

    /** ポップアップ ウィンドウを閉じて、開いたページに戻ります。ポップアップウィンドウがない場合は false を返します。 */
    private fun closePopup(): Boolean {
        val o = opener ?: return false
        opener = null
        tab?.let { popup ->
            loans.swapped(popup, o)
            popup.destroy()
        }
        tab = o
        place()
        refresh()
        return true
    }

    /**
     * ページ スクリプトのほとんどは、読み込み後にアカウント領域を描画します。しばらく停止してからログイン ステータスを確認します。ロード中またはアドレスが変更されている間、最大でも数回待ちます。
     * 登録フォーム内の一部の Web サイトは、表示内容に応じてマークまたは削除されます。見つからないものは、ログインした場合にのみマークされます (ユーザーはここで新しい Web サイトにログインします)。
     */
    private fun scheduleProbe(t: WebTab, attempt: Int) {
        probe?.let(main::removeCallbacks)
        val urlThen = t.view.url
        val run = Runnable {
            probe = null
            if (t !== tab || t.destroyed) return@Runnable
            if (t.loading || t.view.url != urlThen) {
                if (attempt < PROBE_RETRIES) scheduleProbe(t, attempt + 1)
                return@Runnable
            }
            LoginProbe.probe(t) { host, state ->
                val known = sites.list().any { it.host == host }
                val login = loginHost
                val wasIn = login != null && BrowserFavorites.loggedIn(sites.list(), login)
                when {
                    known -> sites.loginObserved(host, state)
                    state == LoginState.SignedIn -> sites.markLoggedIn(host)
                    else -> Unit
                }
                // 「Mika にログイン」すると、ログインしたことがわかります: ユーザーに伝えます (ログイン状態でバナーが閉じられます)
                if (login != null && !wasIn && state == LoginState.SignedIn && BrowserFavorites.siteKey(host) == BrowserFavorites.siteKey(login)) {
                    _notice.value = LOGGED_IN_NOTICE
                }
                Log.i(TAG, "browser login probe $host: $state (known=$known)")
            }
        }
        probe = run
        main.postDelayed(run, PROBE_DELAY_MS)
    }

    /** ページが読み込まれた後、Web サイト名 (ページ上で宣言されている Web サイト名またはタイトル) を読み取り、[looks] に記録します。 */
    private fun readName(t: WebTab, url: String) {
        val host = WebUrlPolicy.host(url) ?: return
        if (AddressInput.searchQuery(url) != null) return
        t.view.evaluateJavascript(SiteNames.SCRIPT) { raw ->
            if (t.destroyed) return@evaluateJavascript
            val (declared, title) = SiteNames.parse(raw) ?: return@evaluateJavascript
            val (name, rank) = SiteNames.pick(declared, title, SiteNames.isHomePage(url)) ?: return@evaluateJavascript
            looks.named(host, name, rank)
        }
    }

    companion object {
        private const val TAG = "MikaUserBrowser"
        /** このタグはどのタスクにも属しません。 */
        const val OWNER = "user-browser"
        private const val PROBE_DELAY_MS = 1_500L
        private const val PROBE_RETRIES = 3
        private const val CONFIRM_WAIT_MS = 2_500L
        /** 受け取った Web サイトのアイコンは少なくともこの大きさ、保存時は最大でもこの大きさ（ピクセル）。 */
        private const val MIN_ICON_PX = 16
        private const val MAX_ICON_PX = 128
        val LOGGED_IN_NOTICE: String get() = tr(R.string.browser_logged_in)
        /** ページ上で選択された文字（入力欄の中で選択された部分を含む）。 */
        private const val SELECTION_SCRIPT = "(function(){var s=window.getSelection();var t=s?String(s):'';" +
            "var a=document.activeElement;if(!t&&a&&typeof a.selectionStart==='number'&&a.type!=='password')" +
            "t=String(a.value||'').substring(a.selectionStart,a.selectionEnd);return t;})()"

        /** プロセス内に既にあるもの; ブラウザページをまだ開いていなければ null（そのために新規作成はしない）。 */
        fun existing(): UserBrowser? = instance

        @Volatile private var instance: UserBrowser? = null

        fun of(context: Context): UserBrowser =
            instance ?: synchronized(this) { instance ?: UserBrowser(context.applicationContext).also { instance = it } }
    }
}
