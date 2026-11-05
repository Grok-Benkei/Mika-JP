package com.wangzan101.mika.cap.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.DialogReply
import com.wangzan101.mika.core.cap.web.ExternalLink
import com.wangzan101.mika.core.cap.web.PageDialogs
import com.wangzan101.mika.core.uistate.PageDialog
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Web ページ タグ: WebView とその読み込みステータス。 Web ページのデスクトップ バージョンがデフォルトで開きますが、サイトを押すとモバイル バージョンに戻ることができます ([WebDevtools] を参照)。
 * はメインスレッドで作成する必要があります。 [assetLoaders] は、ローカル フィクスチャ (ドメイン名ごとに 1 つ) などのアプリが付属する Web ページにのみ使用されます。
 * システムは、mika という名前の独立した Web ページ構成を使用して複数の構成をサポートします (Cookie とストレージはシステム内の他の WebView から分離されています)。
 * ページ (位置決め、カメラ、マイク、MIDI、保護されたメディア) の許可申請はサイレントに拒否されます。アプリへのリンクはジャンプされません。当面の間、ダウンロードは受け付けておりません。
 * ページ ポップアップ ボックス (アラート/確認/プロンプト、ページ終了確認) がシステム ダイアログ ボックスにポップアップ表示されない場合は、[dialogs] と入力し、他のエージェント (またはユーザー自身のブラウザ ページ) が応答します。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebTab(
    context: Context,
    val id: String,
    ownerTaskId: String,
    private val assetLoaders: List<WebViewAssetLoader> = emptyList(),
    /** このタグの DevTools セッション エントリ。デフォルトはプロセス中のものです。 */
    val devtools: WebDevtools = WebDevtools.of(context),
) {
    /** タグ プールは、タグで起こったことを受け取ります。すべてがメインスレッドでコールバックされます。 */
    interface Listener {
        fun onPageFinished(tab: WebTab) {}
        fun onCrashed(tab: WebTab) {}
        fun onExternalLink(tab: WebTab, link: ExternalLink) {}
        /** ページは新しいウィンドウを開く必要があります。 true を返して、それが受け入れられたことを示します (新しいラベルの WebView を [resultMsg] に渡します)。 */
        fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        /** ポップアップされた新しいタグは、初めて別の Web サイトに移動します。タグ プール内で閉じてください。 */
        fun onPopupRefused(tab: WebTab, url: String) {}
        /** ページは、独自のウィンドウ (window.close()) を閉じる必要があります。 */
        fun onCloseWindow(tab: WebTab) {}
        /** ページによってダウンロードがトリガーされました (保存のみで、開かれず、インストールもされません)。 */
        fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {}
        /** ページのアドレス、タイトル、または読み込みの進行状況が変更されました (アドレス バーと表示されているタグの進行状況バーを更新します)。 */
        fun onPageChanged(tab: WebTab) {}
        /** ページにはポップアップ ボックスがあります。[dialogs] にメモし、返信をお待ちください。 */
        fun onDialog(tab: WebTab) {}
        /** は現在のページのファビコンを受け取りました。 */
        fun onIcon(tab: WebTab, icon: Bitmap) {}
    }

    /**
     * web_upload 最初にここに配置され、ページ ファイル選択ボックスに渡されるファイル (ワークスペース ファイルの content:// アドレス)。
     * 選択ボックスが出てきたら一旦外します。提出書類がない場合は、そのまま選択ボックスが解除されます。メインスレッドでの読み取りと書き込みのみを行います。
     */
    var pendingUpload: List<Uri>? = null

    /** ページ上に選択ボックスが表示されたが、提出する書類がない（キャンセルされた）回数。 */
    var choosersRefused: Int = 0
        private set

    /** このラベルはどのタスクに属しますか (ユーザー自身のブラウザ ページには独自の番号があります)。ユーザーがタスクに貸し出されたページを閉じ、タスクがそれを受け入れると、そのタスクに変更されます。 */
    var ownerTaskId: String = ownerTaskId
        internal set

    val view: WebView = WebView(context)

    /** このタグは応答を待つページポップアップです。 ページから */
    val dialogs = PageDialogs()

    var listener: Listener? = null

    /** がポップアップ表示される場合、それを開いたページのアドレスです。ポップアップ タブは、この Web サイト内でのみ移動できます。 */
    var popupOpener: String? = null

    // ポップアップ タグは同じサイト ページにすでに読み込まれています。今後他の Web サイトにアクセスしたい場合は、そのナビゲーションをブロックするだけでタグを閉じないようにしてください。
    private var popupCommitted = false
    /** 現在の主文書に対するプロキシ認証の再試行回数です。メインスレッドで扱います。 */
    private val proxyAuth = WebProxyAuth()

    /** メインドキュメントがロードされているかどうか。メインスレッドでの読み取りと書き込みのみを行います。 */
    var loading: Boolean = false
        private set

    /** メインドキュメントの読み込み進行状況 (0 ～ 100)。メインスレッドでの読み取りと書き込みのみを行います。 */
    var progress: Int = 0
        private set

    /** 最新の [load] がロードが完了するまで待機したか (エラー ページを含む)。タイムアウトは false です。 */
    var lastLoadFinished: Boolean = false
        private set

    /** メインドキュメントの最新の HTTP エラーステータス (4xx/5xx)。取得できない場合、またはエラーがない場合は null になります。 */
    var httpErrorStatus: Int? = null
        private set

    /** メインドキュメントが最後に開かれなかったときにブラウザによって表示されたエラー コード (net::ERR_NAME_NOT_RESOLVED など)。開かれているか、エラーがない場合は null です。 */
    var mainFrameError: String? = null
        private set

    /** 最新の [load] ページがダウンロードに変更されました (メインのドキュメントはファイルです)。 */
    var loadBecameDownload: Boolean = false
        private set

    /** 最新のブロックされたアプリのリンク。 [load] 序盤でクリア。 */
    var lastExternalLink: ExternalLink? = null
        private set

    /** [destroy] 以降は True。 */
    @Volatile var destroyed: Boolean = false
        private set

    /** は、レンダリング プロセスがクラッシュするか、システムによってリサイクルされた後は true になります。タグは使用できなくなり、上位層によって再度開かれます。 */
    @Volatile var crashed: Boolean = false
        private set

    /** レンダリング プロセスはそれ自体ではクラッシュしませんでしたが、メモリを解放するためにシステムによって回復されました (同時に [crashed] が true です)。 */
    @Volatile var reclaimed: Boolean = false
        private set

    private var pending: CompletableDeferred<Boolean>? = null
    // が新しいロードを開始した後、onPageFinished を認識する前に、自身の onPageStarted まで待機する必要があります。最後のナビゲーションの遅延完了コールバックはカウントされません。
    private var armed = false
    private var mainFrameFailed = false
    private var mainUrl: String? = null

    init {
        // 構成は、WebView が他の処理を行う前に設定する必要があります。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(view, PROFILE)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            @Suppress("DEPRECATION")
            saveFormData = false
            setGeolocationEnabled(false)
            // 新しいウィンドウは、onCreateWindow を通じてタグ プールに渡されます。スクリプトはユーザーの操作なしにウィンドウを開くことはできません。
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        devtools.prepare(this, context)
        // サードパーティ Cookie がオフになっています (決定)。別途決定する必要がある後続のサイトがある場合
        cookieManager().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoaders.firstNotNullOfOrNull { it.shouldInterceptRequest(request.url) }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (request.isForMainFrame && refusePopup(url)) return true
                // はアプリリンク (意図:、カスタムプロトコル、アプリストア) にジャンプしません。メインドキュメント内のタグのみがタグプールに通知されます（サブフレームでのサイレントブロック）
                WebUrlPolicy.external(url)?.let { link ->
                    if (request.isForMainFrame) {
                        lastExternalLink = link
                        listener?.onExternalLink(this@WebTab, link)
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                // ポップアップ ウィンドウの最初のナビゲーションは必ずしも shouldOverrideUrlLoading を経由するわけではありません: ここでもう一度確認してください
                if (url != null && refusePopup(url)) {
                    v.stopLoading()
                    return
                }
                proxyAuth.onPageStarted()
                loading = true
                armed = true
                mainFrameFailed = false
                mainFrameError = null
                httpErrorStatus = null
                mainUrl = url
                devtools.onPageStarted(this@WebTab, url)
                listener?.onPageChanged(this@WebTab)
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                listener?.onPageChanged(this@WebTab)
            }

            // ページが表示され始めたら、ポップアップ ウィンドウの Web サイトを再度確認します (最初の 2 か所がバイパスされる場合は最後のステップ)
            override fun onPageCommitVisible(v: WebView, url: String?) {
                if (url != null && refusePopup(url)) v.stopLoading()
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (!armed) return
                loading = false
                finish(!mainFrameFailed)
                listener?.onPageFinished(this@WebTab)
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    mainFrameError = error.description?.toString()?.takeIf { it.isNotBlank() } ?: "net::error ${error.errorCode}"
                }
            }

            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    httpErrorStatus = response.statusCode
                    mainFrameFailed = true
                }
            }

            // ネットワーク プロキシにはアカウントとパスワードが必要です。[設定] › [ネットワーク プロキシ] で HTTP プロキシに応答するだけです。何度か答えてもなお尋ねられます（アカウントとパスワードが間違っています）ので、私は二度と答えません。
            // Web サイト独自のログイン ボックスはここに入力されません。
            override fun onReceivedHttpAuthRequest(v: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                val credentials = proxyAuth.credentialsFor(host)
                if (credentials == null) {
                    handler.cancel()
                    return
                }
                handler.proceed(credentials.first, credentials.second)
            }

            // 証明書に問題がある場合は続行されません。
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (error.url == mainUrl) {
                    mainFrameFailed = true
                    mainFrameError = "certificate_error ${error.primaryError}"
                }
            }

            // は true を返すため、アプリ全体が強制終了されません。タグは無効になり、タグ プールによって破棄されます。
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer gone for tab $id crashed=${detail.didCrash()}")
                crashed = true
                reclaimed = !detail.didCrash()
                loading = false
                (view.parent as? ViewGroup)?.removeView(view)
                finish(false)
                listener?.onCrashed(this@WebTab)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (resultMsg == null) return false
                return listener?.onCreateWindow(this@WebTab, isUserGesture, resultMsg) ?: false
            }

            override fun onCloseWindow(window: WebView) {
                listener?.onCloseWindow(this@WebTab)
            }

            override fun onProgressChanged(v: WebView, newProgress: Int) {
                progress = newProgress
                listener?.onPageChanged(this@WebTab)
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                listener?.onPageChanged(this@WebTab)
            }

            override fun onReceivedIcon(v: WebView, icon: Bitmap?) {
                if (icon != null) listener?.onIcon(this@WebTab, icon)
            }

            // ページ ポップアップ ボックス: 書き留めて戻ります (true を返すと自分で処理することを意味し、システムはボックスをポップアップしません)。インターフェイス スレッドを占有しません。ページスクリプトは応答する前に停止します
            override fun onJsAlert(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.ALERT, message, null, result)

            override fun onJsConfirm(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.CONFIRM, message, null, result)

            override fun onJsPrompt(v: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
                held(PageDialog.PROMPT, message, defaultValue, result)

            override fun onJsBeforeUnload(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.BEFORE_UNLOAD, message, null, result)

            // ユーザーにはこのラベルは表示されません。承認ポップアップは、ユーザーの [同意する: すべて拒否] をクリックするのと同じです。
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }

            // ファイル選択ボックス: web_upload によって事前に配置されたワークスペース ファイルのみを送信し、取得後すぐにクリアします。そうでない場合は、システム セレクターをポップアップ表示せずにシステム セレクターをキャンセルします。
            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val files = pendingUpload
                pendingUpload = null
                if (files.isNullOrEmpty()) {
                    choosersRefused++
                    callback.onReceiveValue(null)
                    return true
                }
                val picked = if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) files else files.take(1)
                callback.onReceiveValue(picked.toTypedArray())
                return true
            }
        }
        // ダウンロードは一時的に受け付けられず、ダウンロード モジュールによって引き継がれます。
        // はタグ プールにダウンロードされます (ワークスペースにドロップされます)。ファイルは開かれていない、またはインストールされていません。
        view.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val l = listener
            if (l == null) Log.i(TAG, "download ignored on tab $id: ${url.take(80)}")
            else l.onDownload(this, url, userAgent.orEmpty(), contentDisposition, mimetype)
            // ロードを待機していたナビゲーションがダウンロードになりました。ページは再度ロードされず、タイムアウトを待つ必要はありません。
            if (pending != null) {
                loadBecameDownload = true
                loading = false
                finish(false)
            }
        }
    }

    /** ページのポップアップ ボックスに注目してください。応答はメイン スレッドでブラウザに渡されます (JsResult は 1 回だけ応答できます)。 */
    private fun held(kind: String, message: String?, defaultText: String?, result: JsResult): Boolean {
        val text = message.orEmpty().take(PageDialog.MAX_MESSAGE)
        dialogs.open(PageDialog(kind, text, defaultText?.take(PageDialog.MAX_MESSAGE)), object : DialogReply {
            override fun accept(text: String?) {
                view.post { if (result is JsPromptResult && text != null) result.confirm(text) else result.confirm() }
            }
            override fun dismiss() {
                view.post { result.cancel() }
            }
        })
        Log.i(TAG, "page dialog $kind held on tab $id")
        listener?.onDialog(this)
        // ロード中の箇条書きボックス: ページはここで停止し、ロード待機はタイムアウトまで待機しなくなります。
        if (pending != null) finish(true)
        return true
    }

    /**
     * ポップアップのタブが元のページと異なるサイトへ移動しようとしたら遮断する（about:blankを除く）。同じサイトをまだ読み込んでいなければ、タブプールに閉じる処理を任せる。
     * 同じサイトを読み込み済みなら、今回の移動だけを遮断する。
     */
    private fun refusePopup(url: String): Boolean {
        val opener = popupOpener ?: return false
        if (url == "about:blank" || WebUrlPolicy.sameSite(opener, url)) {
            if (url != "about:blank") popupCommitted = true
            return false
        }
        if (!popupCommitted) listener?.onPopupRefused(this, url)
        else Log.i(TAG, "popup tab $id kept on its site, refused ${url.take(80)}")
        return true
    }

    private fun finish(ok: Boolean) {
        pending?.complete(ok)
        pending = null
        armed = false
    }

    /** URL を開いて、メイン ドキュメントが読み込まれるまで待ちます。タイムアウト、メインドキュメントエラー、または HTTP 4xx/5xx が false を返します。 http/https URL のみが受け入れられます。 */
    suspend fun load(url: String, timeoutMs: Long = 20_000): Boolean = withContext(Dispatchers.Main) {
        if (crashed) return@withContext false
        WebUrlPolicy.checkNavigable(url)?.let {
            Log.w(TAG, "refused to load a non-web address on tab $id: $it")
            lastLoadFinished = false
            return@withContext false
        }
        // 初めてナビゲートする前にデスクトップ シミュレーションをセットアップします (接続が失敗した場合は、最大でも短時間待って、通常どおり開きます)
        devtools.beforeLoad(this@WebTab, url)
        if (crashed || destroyed) return@withContext false
        finish(false)
        lastExternalLink = null
        lastLoadFinished = false
        mainFrameError = null
        loadBecameDownload = false
        val done = CompletableDeferred<Boolean>()
        pending = done
        loading = true
        view.loadUrl(url)
        val ok = withTimeoutOrNull(timeoutMs) { done.await() }
        lastLoadFinished = ok != null
        if (ok == null) {
            if (pending === done) finish(false)
            view.stopLoading()
            loading = false
        }
        ok ?: false
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        dialogs.close()
        devtools.release(this)
        listener = null
        finish(false)
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    companion object {
        private const val TAG = "MikaWebTab"
        private const val PROFILE = "mika"

        /**
         * ウェブのログイン情報を保存するCookieストア。複数プロファイルに対応していればMika専用、非対応なら既定のストアを使う。メインスレッドから呼び出す。
         * 複数プロファイルに非対応の端末では、同じアプリの全WebViewとCookieを共有する。ログイン状態が混ざらないよう、ほかのWebViewでウェブサイトの認証を処理しない。
         */
        fun cookieManager(): CookieManager =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager
            } else {
                CookieManager.getInstance()
            }

        /** と [cookieManager] は、Web ストレージ (localStorage、IndexedDB など) の構成が同じです。メインスレッドによって呼び出されます。 */
        fun webStorage(): WebStorage =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).webStorage
            } else {
                WebStorage.getInstance()
            }
    }
}
