package com.wangzan101.mika.cap.web.cdp

import android.content.Context
import android.icu.util.ULocale
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebViewCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.cdp.CdpDiscovery
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import com.wangzan101.mika.core.shell.DevtoolsSocket
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import android.os.ParcelFileDescriptor
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.wangzan101.mika.cap.web.WebOwner

/**
 * 各 Web ページ タグの DevTools セッション エントリ。接続を確立できない場合 (システムが接続をサポートしていないか、ブロックされているか、ラベルが閉じられている場合)、これは null になります。
 * 呼び出し元はインジェクション スクリプトを返します。すべてのタブのデフォルトはデスクトップ バージョンです。これが新しいベースラインであり、セッションに依存しません。セッションがない場合、WebView 設定は
 * デスクトップ UA、UA-CH (サポートされている場合) およびワイド ビューポートは引き続き有効ですが、シミュレーション レイヤー (ビューポート幅、navigator.platform) がありません。
 *
 * 接続方法を順番に試して結果を覚えておいてください。このプロセスの `webview_devtools_remote_<pid>` に直接接続します。拒否された場合は、[relay] を使用してクライアントに代わって接続します (組み込みアシスタント、シェル ID、アセンブリ層によって与えられます)。それが機能しない場合は接続されません。 通常、プロセス内には
 * のコピーが 1 つあります (アプリによって提供されます。[of] を参照)。テスト用に、独自のコピーを作成できます。セッション ソースと接続方法の変更は、自分自身にのみ影響します。
 * 各タグを初めて使用するときにタグを書き留め、1 つずつ見つけて targetId を覚えてください。その後、このセッションを使用し続けます。一度切断して再接続し、失敗した場合、タグはダウングレードされます (しばらくしてから再試行してください)。
 *
 * デスクトップ Web ページもここにあります。ラベルが作成されたらすぐに、WebView 設定をデスクトップ UA (DevTools に接続できない場合のフォールバック) に変更し、ドキュメントの先頭に ID パッチを挿入します。
 * セッションが接続されたら、シミュレーションを使用して、UA-CH、ビューポート、タッチ、およびポインターをデスクトップに置き換えます ([DesktopProfile] を参照)。サイトごとに記憶されているすべてのモバイル サイトがデフォルトに戻ります。
 * ユーザーが Mega でインターフェイス言語を選択すると、Web ページの両方のバージョンがその言語で要求されます ([PageLanguage] を参照)。システムに従っているときは動きません。
 */
class WebDevtools(private val relay: ((String) -> ParcelFileDescriptor?)?) {
    enum class Route { Unknown, Direct, Relay, None }

    /** テスト: 直接接続のみ / プロキシ接続のみ / 接続が利用できないかのように; null は自動を意味します。修正後は[resetRoute]を調整してください。 */
    @Volatile var forceRoute: Route? = null

    private val _route = MutableStateFlow(Route.Unknown)
    /** 最後に検出された接続方法。不明はまだテストされていません。 */
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** は、DevTools (診断およびインターフェイス用) を使用できるようになりました。 */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    @Volatile var provider: (WebTab) -> CdpSession? = this::cached

    /** セッションは接続されました。接続する前にバックグラウンドで接続を開始し、最初に null を返します。ブロッキングがなく、どのスレッドでも調整できます。 */
    fun session(tab: WebTab): CdpSession? =
        // セッションのソースが変更された場合 (独自のテストなど)、このラベルのビューポート シミュレーションに従って入力座標も変換する必要があります。
        provider(tab)?.also { s -> links[tab]?.let { s.inputScale = it.inputScale } }

    /** と他のセッションが接続されています (最大 [timeoutMs])。接続されていない場合は null です。メインスレッドが待機している場所では呼び出さないでください。 タグ */
    suspend fun awaitSession(tab: WebTab, timeoutMs: Long = ATTACH_TIMEOUT_MS): CdpSession? {
        val link = links[tab] ?: return null
        return bounded(timeoutMs) { attach(link) }
    }

    /** は、Web ページのモバイル バージョンで使用されるようになりました。 */
    fun isMobile(tab: WebTab): Boolean = links[tab]?.mobile ?: false

    /** このタグの DevTools ターゲット ID。接続されていない場合は null です。 */
    fun targetId(tab: WebTab): String? = links[tab]?.targetId

    // ---------- ラベルのライフサイクル (WebTab 調整) ----------

    /** ラベルが作成されました (メイン スレッド): デバッグ インターフェイスを開き、WebView 設定のデスクトップ バージョンに変更して、X-Requested-With ヘッダーをオフにします。 */
    fun prepare(tab: WebTab, context: Context) {
        val st = tab.view.settings
        init(context, st)
        val link = Link(tab)
        links[tab] = link
        // ビューのサイズが変更されました (ホスト ディスプレイが変更され、引き継ぎインターフェイスが変更されました): デスクトップ バージョンのビューポートのスケーリングが再計算されました。
        tab.view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onViewResized(link)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(st, emptySet()) }
        }
        applySettings(st, mobile = false)
        applyIdentityScript(link)
        link.acceptLanguage = chosenAcceptLanguage()
    }

    /** メイン ドキュメントをロードする前 (メイン スレッド、一時停止可能): ターゲット Web サイトに応じてモバイル バージョンまたはデスクトップ バージョンを選択し、最初のナビゲーションの前にセッションの接続とシミュレーションのセットアップを試みます。 */
    suspend fun beforeLoad(tab: WebTab, url: String) {
        val link = links[tab] ?: return
        val mobile = wantsMobile(link, url) ?: false
        val changed = link.mobile != mobile
        if (changed) switchMode(link, mobile)
        // ユーザーが途中でミカの言語を変更しました: 開いているタグは、次のメインドキュメントがロードされる前に変更されます (シミュレーションはここでリセットされ、このページは新しい言語で要求されます)
        chosenAcceptLanguage().let { lang ->
            if (lang != link.acceptLanguage) {
                link.acceptLanguage = lang
                link.configured = null
            }
        }
        // の接続とシミュレーションはバックグラウンドで行われます。最大でもしばらく待ちます。ポイントに到達すると、通常どおりロードされます (このページは、最初に WebView によって設定された退避パスに従い、接続後に変更されます)
        bounded(BEFORE_LOAD_WAIT_MS) {
            val s = attach(link)
            if (s != null && link.configured != link.mobile) link.lock.withLock { configure(link, s) }
            s
        }
    }

    // は独立したコルーチンで実行されます。呼び出し元は、内部の読み取りと書き込みのブロックによって妨げられることなく、上限まで待機して終了します。
    private suspend fun <T> bounded(timeoutMs: Long, block: suspend () -> T?): T? {
        val job = scope.async { block() }
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    /**
     * メインドキュメントの読み込みが開始されます (メインスレッド): リンクをクリックし、Web サイトの別のバージョンにジャンプし、変更して、再度リロードします。
     * は Web ページ (about:blank など) に対して不動ではありません。
     */
    fun onPageStarted(tab: WebTab, url: String?) {
        val link = links[tab] ?: return
        if (url == null || !url.startsWith("http")) return
        link.loadedBeforeAttach = true
        val mobile = wantsMobile(link, url) ?: return
        if (link.mobile == mobile) return
        switchMode(link, mobile)
        scope.launch {
            link.session?.takeIf { !it.closed.value }?.let { runCatching { configure(link, it) } }
            withContext(Dispatchers.Main) { if (!tab.destroyed) tab.view.reload() }
        }
    }

    /**
     * このタグは、各 Web サイトで使用するバージョンを決定します ([policy] はホスト名を収集し、モバイル バージョンの場合は true を返します)。サイト (エージェント) によって記憶されているバージョンは参照されなくなります。
     * は、その共有と交換するために null に設定されます。このタグにのみ影響し、次回メインドキュメントがロードされたとき (現在のページが呼び出し元によって再ロードされたとき) に有効になります。メインスレッドによって呼び出されます。
     */
    fun setModePolicy(tab: WebTab, policy: ((String?) -> Boolean)?) {
        links[tab]?.policy = policy
    }

    /**
     * このタグはユーザーによって表示およびクリックされます (ブラウザ ページ内のタグ)。デスクトップ バージョンにはビューポート エミュレーションが含まれておらず、ページは 2 本の指で拡大および縮小できます ([DesktopProfile.viewportCall] を参照)。
     * は、次回セッションが構成されるときに有効になります。メインスレッドによって呼び出されます。
     */
    fun setHandOperated(tab: WebTab) {
        links[tab]?.handOperated = true
    }

    /** エージェント ラベル [host] を開くとき、モバイル バージョンを使用する必要がありますか (サイトに記憶されているバージョンをクリックします。ラベルが作成されていない場合は、デフォルトのデスクトップ バージョンをクリックします)。 */
    fun agentWantsMobile(host: String?): Boolean = modes?.isMobile(host) ?: false

    // タグ独自のルールが優先されます。そうでない場合は、サイトに記憶されているものを見てください。読み込まれていない場合はnullとなります。
    private fun wantsMobile(link: Link, url: String): Boolean? {
        val host = WebUrlPolicy.host(url)
        link.policy?.let { return it(host) }
        return modes?.isMobile(host)
    }

    /** このビューは、デスクトップ ビューポート サイズでホスト ディスプレイにハングアップする必要があります (ラベルにはデスクトップ バージョンを使用します)。未知の見解は誤りです。 */
    fun wantsDesktopHost(view: WebView): Boolean = links.values.firstOrNull { it.tab.view === view }?.let { !it.mobile } ?: false

    // メインスレッド: 2 層設定を切り替え、ホストにぶら下がっているビューを対応するサイズの表示に切り替えます。
    private fun switchMode(link: Link, mobile: Boolean) {
        link.mobile = mobile
        applySettings(link.tab.view.settings, mobile)
        applyIdentityScript(link)
        WebHost.followMode(link.tab)
    }

    /**
     * メイン スレッド: デスクトップ バージョンでは、各ドキュメント (フレームを含む) の先頭に ID パッチ ([DesktopProfile.identityScript]) が挿入され、モバイル バージョンではそれが削除されます。
     * システムは最初はドキュメント インジェクションをサポートしていません。これはセッションで Page.addScriptToEvaluateOnNewDocument に置き換えられます ([configure] を参照)。
     */
    private fun applyIdentityScript(link: Link) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (link.mobile) {
            link.identityScript?.let { runCatching { it.remove() } }
            link.identityScript = null
        } else if (link.identityScript == null) {
            link.identityScript = runCatching { WebViewCompat.addDocumentStartJavaScript(link.tab.view, DesktopProfile.identityScript(), setOf("*")) }
                .onFailure { Log.w(TAG, "identity script on tab ${link.tab.id}: ${it.message}") }.getOrNull()
        }
    }

    private fun onViewResized(link: Link) {
        if (link.mobile) return
        val s = link.session?.takeIf { !it.closed.value } ?: return
        scope.launch {
            link.lock.withLock {
                if (link.session === s && !link.mobile) runCatching { configure(link, s) }.onFailure { Log.w(TAG, "re-fit tab ${link.tab.id}: ${it.message}") }
            }
        }
    }

    /** タグは閉じられています: セッションを切断して忘れてください。 */
    fun release(tab: WebTab) {
        val link = links.remove(tab) ?: return
        link.session?.let { s -> scope.launch { s.close() } }
        link.session = null
    }

    /** サイトがモバイル バージョンを使用しているかデスクトップ バージョンを使用しているかに注意してください。現在のページは呼び出し元によって再ロードされます (ロード前に [beforeLoad] が変更されます)。 テスト用の */
    fun setSiteMode(host: String, mobile: Boolean) {
        modes?.set(host, mobile)
    }

    /** : 実際のセッション ソースに戻します (他のテストで [provider] が変更されたが、元に戻されていない場合)。 */
    fun resetProvider() {
        provider = this::cached
    }

    /** テスト用: 検出された接続方法を忘れて、次回再テストします。 */
    fun resetRoute() {
        path = null
        directFailed = false
        _route.value = Route.Unknown
        _available.value = false
    }

    // ---------- 内部 ----------

    private class Link(val tab: WebTab) {
        val lock = Mutex()
        @Volatile var session: CdpSession? = null
        @Volatile var targetId: String? = null
        @Volatile var mobile = false
        /** このタグ独自のバージョン ルール ([setModePolicy] を参照)。 null の場合、サイトによって記憶されているもの。 */
        @Volatile var policy: ((String?) -> Boolean)? = null
        /** ユーザー操作ラベル ([setHandOperated] を参照): ビューポート エミュレーションはありません。 */
        @Volatile var handOperated = false
        /** Web ページが要求される言語 (Accept-Language の値)。 null は、システムに従い、それをカバーしないことを意味します。メインドキュメントをロードする前に、毎回タグを作成し、ミカの言語でタグを取得します。 */
        @Volatile var acceptLanguage: String? = null
        /** バージョンはセッションに設定されました。 null はまだ設定されていないことを意味します。 */
        @Volatile var configured: Boolean? = null
        @Volatile var degradedAt = 0L
        @Volatile var loadedBeforeAttach = false
        @Volatile var everAttached = false
        /** 現在のシミュレーションにおける入力ドメイン座標と CSS ピクセルの比率 ([CdpSession.inputScale] を参照)。 */
        @Volatile var inputScale = 1.0
        /** ビューポート シミュレーションが追加されていない場合のページの devicePixelRatio (WebView の各 CSS ピクセルが占めるビュー ピクセル数)、および測定時のビューの表示。 */
        @Volatile var baseDpr: Double? = null
        @Volatile var baseDprDisplay = -1
        /** ビューポート シミュレーションがこのセッションに設定されています。次のページで読み取った devicePixelRatio を設定します。 */
        @Volatile var metricsSet = false
        @Volatile var emulatedDpr: Double? = null
        /** デスクトップ ID パッチがドキュメントの先頭に挿入されました (メインスレッドがアクティブ)。モバイル版の場合は null。 */
        var identityScript: ScriptHandler? = null
        /** システムは、ドキュメントの挿入開始時にこのセッションに登録されている同じパッチ番号をサポートしていません。 */
        @Volatile var sessionScriptId: String? = null
    }

    /** 接続方法です。 */
    private class Path(val route: Route, val transport: CdpTransport)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val links = ConcurrentHashMap<WebTab, Link>()
    private val pathLock = Mutex()
    @Volatile private var path: Path? = null
    @Volatile private var directFailed = false
    @Volatile private var relayFailedAt = 0L
    @Volatile private var modes: SiteModes? = null
    @Volatile private var desktop: JsonObject? = null
    @Volatile private var desktopUa: String? = null
    @Volatile private var desktopMetadata: UserAgentMetadata? = null
    @Volatile private var defaultMetadata: UserAgentMetadata? = null
    /** WebView のデフォルトのモバイル ID (UA および CDP 形式の UA-CH): モバイル バージョンで言語を選択すると、それがコピーされ、言語のみが変更されます。 */
    @Volatile private var defaultUa: String = ""
    @Volatile private var defaultMetadataJson: JsonObject? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var debuggingOn = false

    private val socketName: String get() = DevtoolsSocket.name(Process.myPid())

    @Synchronized
    private fun init(context: Context, st: WebSettings) {
        if (!debuggingOn) {
            WebView.setWebContentsDebuggingEnabled(true)
            debuggingOn = true
        }
        if (modes == null) modes = SiteModes(File(context.applicationContext.filesDir, "web"))
        if (appContext == null) appContext = context.applicationContext
        if (desktopUa != null) return
        val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
        this.defaultUa = defaultUa
        val (major, full) = DesktopProfile.version(defaultUa) ?: ("120" to "120.0.0.0")
        val metadataOk = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        // 初めて設定を変更する前に、カーネル独自の UA-CH をモバイル版サイトに置き換えてください。
        val probe = if (metadataOk) runCatching { WebSettingsCompat.getUserAgentMetadata(st) }.getOrNull() else null
        val engine = probe?.brandVersionList?.map { Brand(it.brand, it.majorVersion, it.fullVersion) }
        val brands = DesktopProfile.brands(engine, major, full)
        defaultMetadata = probe
        defaultMetadataJson = probe?.let { m ->
            DesktopProfile.metadata(
                m.brandVersionList.map { Brand(it.brand, it.majorVersion, it.fullVersion) }, m.fullVersion.orEmpty(), m.platform.orEmpty(),
                m.platformVersion.orEmpty(), m.architecture.orEmpty(), m.model.orEmpty(), m.isMobile,
                if (m.bitness == UserAgentMetadata.BITNESS_DEFAULT) "" else m.bitness.toString(), m.isWow64,
            )
        }
        desktopMetadata = if (metadataOk) {
            UserAgentMetadata.Builder()
                .setBrandVersionList(brands.map { UserAgentMetadata.BrandVersion.Builder().setBrand(it.brand).setMajorVersion(it.major).setFullVersion(it.full).build() })
                .setFullVersion(full).setPlatform(DesktopProfile.PLATFORM).setPlatformVersion("").setArchitecture("x86")
                .setModel("").setMobile(false).setBitness(64).setWow64(false).build()
        } else null
        desktop = DesktopProfile.userAgentOverride(major, full, brands)
        desktopUa = DesktopProfile.userAgent(major)
    }

    /**
     * ミカでユーザーが選択した言語に対応する Accept-Language。次のシステムは null です。まず、ICUの一般的な書き方に従ってラベルを完成させます。
     * (en → en-Latn-US) がリクエスト ヘッダーとして書き込まれます。
     */
    private fun chosenAcceptLanguage(): String? {
        val tag = appContext?.let { runCatching { AppLanguage.current(it).tag }.getOrNull() } ?: return null
        return PageLanguage.acceptLanguage(ULocale.addLikelySubtags(ULocale.forLanguageTag(tag)).toLanguageTag())
    }

    // リトリート レイヤー: UA、UA-CH (サポートされている場合)、ワイド ビューポート。シミュレーションが接続された後は、シミュレーションが優先されます (言語はシミュレーションによってのみ設定できます。[attach] のリロードを参照してください)。
    private fun applySettings(st: WebSettings, mobile: Boolean) {
        st.userAgentString = if (mobile) null else desktopUa
        st.useWideViewPort = !mobile
        st.loadWithOverviewMode = !mobile
        val meta = if (mobile) defaultMetadata else desktopMetadata
        if (meta != null && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching { WebSettingsCompat.setUserAgentMetadata(st, meta) }
        }
    }

    private fun cached(tab: WebTab): CdpSession? {
        val link = links[tab] ?: return null
        link.session?.takeIf { !it.closed.value }?.let { return it }
        if (!tab.destroyed && !link.lock.isLocked && retryAllowed(link)) scope.launch { attach(link) }
        return null
    }

    private fun retryAllowed(link: Link) =
        link.degradedAt == 0L || SystemClock.elapsedRealtime() - link.degradedAt >= DEGRADED_RETRY_MS

    private suspend fun attach(link: Link): CdpSession? = link.lock.withLock {
        link.session?.takeIf { !it.closed.value }?.let { return it }
        val tab = link.tab
        if (tab.destroyed || tab.crashed || links[tab] !== link || !retryAllowed(link)) return null
        val p = path() ?: return null
        val s = try {
            link.targetId?.let { reopen(p, it) } ?: find(p, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "attach tab ${tab.id}: ${e.message}")
            null
        }
        if (s == null) {
            link.degradedAt = SystemClock.elapsedRealtime()
            // 接続パスが壊れている可能性があります (アシスタントが消えています): 次回再確認してください
            if (p.route == Route.Relay) invalidate(p)
            return null
        }
        link.session = s
        link.degradedAt = 0L
        link.configured = null
        // 新しいセッションでエミュレーションがありません (切断されると WebView が取り消されます) シミュレーションが設定される前に
        link.metricsSet = false
        link.sessionScriptId = null
        runCatching { configure(link, s) }.onFailure { Log.w(TAG, "emulation on tab ${tab.id}: ${it.message}") }
        if (!link.everAttached && link.loadedBeforeAttach && (!link.mobile || link.acceptLanguage != null)) {
            // ページがロードされました (ポップアップ タブ、セッションを待機していない最初のページなど): デスクトップのバージョンまたは選択した言語に従って 1 回リロードします。
            // WebView 設定に言語項目がありません。リクエスト ヘッダーを変更できるのはシミュレーションのみであるため、言語の選択時にモバイル バージョンもリロードする必要があります。
            withContext(Dispatchers.Main) { if (!tab.destroyed) tab.view.reload() }
        }
        link.everAttached = true
        watch(link, s)
        s
    }

    private suspend fun configure(link: Link, s: CdpSession) {
        val mobile = link.mobile
        if (mobile) {
            // はシステムの時間と空間 UA に従います。つまり、オーバーライドをキャンセルして WebView 設定に戻ります (デフォルトに戻されました)。言語を選択すると、デフォルトの ID がコピーされ、言語のみが変更されます。
            s.send("Emulation.setUserAgentOverride", PageLanguage.mobileOverride(defaultUa, defaultMetadataJson, link.acceptLanguage), timeoutMs = CALL_TIMEOUT_MS)
            s.send("Emulation.clearDeviceMetricsOverride", timeoutMs = CALL_TIMEOUT_MS)
            link.metricsSet = false
            s.inputScale = 1.0
            link.inputScale = 1.0
            DesktopProfile.clearMediaCall().let { (m, p) -> runCatching { s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) } }
            link.sessionScriptId?.let { id ->
                runCatching { s.send("Page.removeScriptToEvaluateOnNewDocument", buildJsonObject { put("identifier", id) }, timeoutMs = CALL_TIMEOUT_MS) }
                link.sessionScriptId = null
            }
        } else {
            // タイプセットする前に、ホストのデフォルトのデスクトップ ビューポート サイズを押します。
            val (size, display) = withContext(Dispatchers.Main) {
                val v = link.tab.view
                (if (v.width > 0 && v.height > 0) v.width to v.height else DesktopProfile.HOST_WIDTH to DesktopProfile.HOST_HEIGHT) to (v.display?.displayId ?: -1)
            }
            val (w, h) = size
            desktop?.let { s.send("Emulation.setUserAgentOverride", PageLanguage.withLanguage(it, link.acceptLanguage), timeoutMs = CALL_TIMEOUT_MS) }
            // タッチ、ポインター、ホバー: それぞれは個別に送信され、カーネルが認識しないものは他のものには影響しません。
            for ((m, p) in DesktopProfile.emulationCalls()) {
                runCatching { s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) }.onFailure { Log.i(TAG, "$m not applied on tab ${link.tab.id}: ${it.message}") }
            }
            if (link.sessionScriptId == null && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                link.sessionScriptId = runCatching {
                    s.send("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", DesktopProfile.identityScript()) }, timeoutMs = CALL_TIMEOUT_MS)["identifier"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
            val hand = link.handOperated
            val fit = if (hand) null else DesktopProfile.fitScale(w, baseDpr(link, s, display).toFloat())
            DesktopProfile.viewportCall(hand, w, h, fit).let { (m, p) -> s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) }
            link.metricsSet = !hand
            link.emulatedDpr = if (hand) null else readDpr(s)
            // スケーリング後の入力フィールドの座標は、スケーリング前のビュー (DIP) に従って解釈されます。呼び出し元は通常どおり CSS ピクセルを与え、次のように乗算されます。
            s.inputScale = fit ?: 1.0
            link.inputScale = s.inputScale
            Log.i(TAG, if (hand) "tab ${link.tab.id} desktop identity without viewport emulation (hand-operated)" else "tab ${link.tab.id} desktop viewport fitted to ${w}x$h view px (scale $fit)")
        }
        link.configured = mobile
    }

    /**
     * シミュレーションなしで WebView の各 CSS ピクセルが占めるビュー ピクセル数。 WebViewが認識する表示密度に依存します。ビューを変更すると表示が変わる場合があります。
     * そこで表示マークを押してください。重みが必要な場合は、まずシミュレーションをキャンセルし、シミュレーションが終了したときにページで読み取られた値を取得します。
     */
    private suspend fun baseDpr(link: Link, s: CdpSession, display: Int): Double {
        link.baseDpr?.takeIf { link.baseDprDisplay == display }?.let { return it }
        var v = if (link.metricsSet) {
            s.send("Emulation.clearDeviceMetricsOverride", timeoutMs = CALL_TIMEOUT_MS)
            link.metricsSet = false
            var cur: Double? = null
            val until = SystemClock.elapsedRealtime() + DPR_SETTLE_MS
            while (SystemClock.elapsedRealtime() < until) {
                cur = readDpr(s)
                if (cur != null && cur != link.emulatedDpr) break
                delay(DPR_POLL_MS)
            }
            cur
        } else readDpr(s)
        if (v == null || v <= 0) v = link.baseDpr ?: withContext(Dispatchers.Main) { link.tab.view.resources.displayMetrics.density.toDouble() }
        link.baseDpr = v
        link.baseDprDisplay = display
        return v
    }

    private suspend fun readDpr(s: CdpSession): Double? = runCatching {
        s.evaluate("devicePixelRatio", timeoutMs = CALL_TIMEOUT_MS)["value"]?.jsonPrimitive?.contentOrNull?.toDouble()
    }.getOrNull()

    private fun watch(link: Link, s: CdpSession) {
        scope.launch {
            s.closed.first { it }
            val tab = link.tab
            if (links[tab] !== link || tab.destroyed || tab.crashed || link.session !== s) return@launch
            link.session = null
            // 切断されるたびに再接続します。このラベルに接続できない場合、ラベルはダウングレードされます。しばらくしてからもう一度お試しください。
            delay(RECONNECT_BACKOFF_MS)
            if (attach(link) == null) Log.w(TAG, "tab ${tab.id} lost its devtools session and could not reconnect: degraded")
        }
    }

    /** 既知の targetId: 直接接続し直します (ラベルとターゲット間の対応はナビゲーション全体で変更されません)。ターゲットがなくなった場合は null になります。 */
    private suspend fun reopen(p: Path, targetId: String): CdpSession? {
        val t = CdpDiscovery.list(p.transport, socketName, timeoutMs = DISCOVERY_TIMEOUT_MS).firstOrNull { it.id == targetId } ?: return null
        val ws = t.webSocketDebuggerUrl ?: return null
        return connect(p, ws)
    }

    private suspend fun find(p: Path, link: Link): CdpSession? {
        val tab = link.tab
        val marker = UUID.randomUUID().toString()
        if (!mark(tab, marker)) return null
        val claimed = links.values.mapNotNull { l -> l.targetId.takeIf { l !== link } }.toSet()
        val tabUrl = withContext(Dispatchers.Main) { tab.view.url }
        val candidates = TargetMatch.candidates(CdpDiscovery.list(p.transport, socketName, timeoutMs = DISCOVERY_TIMEOUT_MS), claimed, tabUrl)
        for (t in candidates) {
            val s = connect(p, t.webSocketDebuggerUrl!!) ?: continue
            val v = runCatching {
                s.evaluate(TargetMatch.READ_SCRIPT, timeoutMs = MARK_TIMEOUT_MS)["value"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (TargetMatch.isMarker(v, marker)) {
                runCatching { s.evaluate(TargetMatch.CLEAR_SCRIPT, timeoutMs = MARK_TIMEOUT_MS) }
                link.targetId = t.id
                return s
            }
            s.close()
        }
        Log.w(TAG, "no devtools target carries tab ${tab.id}'s marker (${candidates.size} candidates)")
        return null
    }

    // ハンドシェイクには上限があり (フローはその時点でオフになります)、スタックしたハンドシェイクが常にタグのロックを占有するわけではありません。
    private suspend fun connect(p: Path, webSocketUrl: String): CdpSession? =
        try {
            CdpSession.connect(p.transport, socketName, CdpDiscovery.webSocketPath(webSocketUrl), handshakeTimeoutMs = CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "devtools connect via ${p.route}: ${e.message}")
            null
        }

    /** タグの現在のドキュメント (メインスレッドによって実行) にタグを書き込み、完了するまで待ちます。 */
    private suspend fun mark(tab: WebTab, marker: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            if (tab.destroyed) done.complete(false)
            else tab.view.evaluateJavascript(TargetMatch.markScript(marker)) { done.complete(true) }
        }
        return withTimeoutOrNull(MARK_TIMEOUT_MS) { done.await() } ?: false
    }

    private suspend fun path(): Path? = pathLock.withLock {
        val forced = forceRoute
        if (forced == Route.None) return publish(null, Route.None)
        path?.let { if (forced == null || it.route == forced) return it }
        if (forced == Route.Direct || (forced == null && !directFailed)) {
            val direct = DirectTransport()
            if (probe(direct)) return publish(Path(Route.Direct, direct), Route.Direct)
            if (forced == null) {
                directFailed = true
                Log.i(TAG, "direct devtools connection refused; trying the phone helper")
            }
        }
        if (forced == Route.Relay || forced == null) {
            if (forced == null && SystemClock.elapsedRealtime() - relayFailedAt < RELAY_RETRY_MS && relayFailedAt != 0L) return publish(null, Route.None)
            relay?.let { open ->
                val t = RelayTransport(open)
                if (probe(t)) return publish(Path(Route.Relay, t), Route.Relay)
                relayFailedAt = SystemClock.elapsedRealtime()
            }
        }
        publish(null, Route.None)
    }

    private fun publish(p: Path?, r: Route): Path? {
        path = p
        _route.value = r
        _available.value = p != null
        return p
    }

    private suspend fun invalidate(p: Path) = pathLock.withLock {
        if (path === p) publish(null, Route.Unknown)
    }

    private suspend fun probe(t: CdpTransport): Boolean =
        runCatching { CdpDiscovery.version(t, socketName, timeoutMs = PROBE_TIMEOUT_MS) }.isSuccess

    companion object {
        private const val TAG = "MikaDevtools"
        private const val ATTACH_TIMEOUT_MS = 3_000L
        /** 最初のナビゲーション前の待機セッションの上限: 接続できない場合、Web ページを開くのを長時間遅らせることはできません。 */
        private const val BEFORE_LOAD_WAIT_MS = 2_500L
        private const val PROBE_TIMEOUT_MS = 2_000L
        private const val DISCOVERY_TIMEOUT_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val MARK_TIMEOUT_MS = 2_000L
        private const val CALL_TIMEOUT_MS = 3_000L
        private const val RECONNECT_BACKOFF_MS = 500L
        private const val DPR_SETTLE_MS = 500L
        private const val DPR_POLL_MS = 50L
        /** ダウングレードされたタグは、時間が経ってから再試行する必要があります (アシスタントが再度開始された可能性があります)。 */
        private const val DEGRADED_RETRY_MS = 60_000L
        /** プロキシ接続テストが失敗した後、再度テストするのに非常に時間がかかりました (アシスタントがまだ起動していない可能性があります)。 */
        private const val RELAY_RETRY_MS = 15_000L

        /** プロセスのもの (アプリによって与えられる)。 */
        fun of(context: Context): WebDevtools = WebOwner.of(context).webDevtools
    }
}
