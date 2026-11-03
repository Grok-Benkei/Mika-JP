package com.wangzan101.mika.cap.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.content.Context
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import android.os.SystemClock
import android.util.Log
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.takeover.HandoverActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.net.NetProxy
import java.io.File
import com.wangzan101.mika.core.workspace.SandboxTree

/**
 * ワンタスクの組み込みブラウザ: 独自のバックエンド Web ページ表示、タグのプール、Web ポート、および分離されたスクリプト エンジン。
 * タスクごとに 1 セット (並行して、現在の各ラベルが独自のディスプレイにハングされ、通常どおりレンダリングされます)。メインスレッドをビルドして閉じます。
 */
class WebStack private constructor(
    private val context: Context,
    val taskId: String,
    val host: WebHost,
    val pool: WebTabPool,
    workspace: Workspace?,
    /** が渡されます。 null の場合、ページを離れることはできません (web_keep_open は使用不可として報告されます)。 */
    private val keeper: PageKeeper?,
) {
    /**
     * この会話のワークスペース: [dir] はホスト ディレクトリです (work/<conversation>、存在しない場合は作成され、サンドボックスをインストールする必要はありません)。
     * のダウンロードと書き込み、アップロードと読み取りはすべて [gate] を経由します (一度開いてハンドルで確認します)。
     */
    class Workspace(val dir: File, val gate: WorkspaceFileGate, val tree: SandboxTree)

    // のダウンロードはバックグラウンドで実行されます。タスクが完了するとタスクがキャンセルされ、未完了マークが解除されてファイルの半分が削除されます。
    private val transfers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** ダウンロードとアップロード;ワークスペースがない場合は空です (両方とも使用不可として報告されます)。 */
    val files: WebFiles? = workspace?.let { w ->
        val downloads = WebDownloads(w.dir, w.gate, w.tree, NetProxy.shared.client(), cookies = { url -> WebTab.cookieManager().getCookie(url) }, scope = transfers)
        WebFiles(context, w.dir, downloads, w.gate, transfers)
    }

    val screen: WebScreen = WebScreen(pool, taskId, host, SystemClock::uptimeMillis, files, keptNow = keeper?.let { k -> { k.page.value } })

    /** 左のページには誰の名前（会話、走行記録、オリジナルの言葉）が記録されています。タスクが終了したらそれを受け取ります。指定されない場合はページを残さないでください。 */
    @Volatile var keepOrigin: (() -> KeptOrigin)? = null

    init {
        files?.let { f -> pool.downloadHandler = { tab, url, ua, cd, mime -> onDownload(f, tab, url, ua, cd, mime) } }
    }

    // ページによってトリガーされるダウンロード: http(s) は直接フェッチされ、blob: はページ スクリプトによって読み取られ、data: は直接デコードされます。その他 (intent: など) は無視されます。ファイルは保存されるだけで、開かれません。
    // 3 つのタイプはすべて、最初にページ リンクに指定された名前を要求します (<a download>)。ダウンロード モニタリングは応答ヘッダー内の名前のみを取得します。blob:/data: には応答ヘッダーさえありません。
    private fun onDownload(f: WebFiles, tab: WebTab, url: String, ua: String, cd: String?, mime: String?) {
        val d = f.downloads
        val pageName: suspend () -> String? = { screen.downloadName(tab, url) }
        when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> {
                val referer = tab.view.url
                // TLS が原因で接続できない場合は、ブラウザのネットワーク スタックを使用してページからページを取得します。現在のページと同じオリジンを持つアドレスのみが与えられます。
                val viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = if (!sameOrigin(url, referer)) null else { max ->
                    (screen.readBlob(tab, url, max) as? PageScript.Blob.Ok)?.let { it.bytes to it.mime }
                }
                d.withPageName(pageName) { name -> d.start(url, ua, cd, mime, referer, name, viaPage) }
            }
            url.startsWith("blob:", true) -> d.withPageName(pageName) { name ->
                d.startFrom(url, cd, mime, name) { max ->
                    when (val b = screen.readBlob(tab, url, max)) {
                        is PageScript.Blob.Ok -> b.bytes to b.mime
                        is PageScript.Blob.Failed -> throw WebDownloads.BlobRefused(b.error)
                    }
                }
            }
            url.startsWith("data:", true) -> d.withPageName(pageName) { name -> d.dataUrl(url, cd, name) }
            else -> Log.i(TAG, "download with an unsupported address ignored")
        }
    }

    /** 2 つの URL は同じオリジンを持っています (プロトコル、ホスト、ポートが同じです)。そのうちの 1 つは誤りです。 */
    private fun sameOrigin(a: String, b: String?): Boolean {
        fun origin(u: String?) = u?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
        val x = origin(a) ?: return false
        return x == origin(b)
    }

    /** ユーザーに渡される (引き継がれる) ラベル。そうでない場合は null です。 */
    @Volatile private var handedTab: WebTab? = null

    /** ページは現在ユーザーの手に渡っています (管理下にあります)。 */
    val handedOver: Boolean get() = handedTab != null

    /** このタスクには現在のタグがありますか (現在のタグは Web ページの表示に表示され、表示されます)。 Web ページがバックグラウンドの一時タグでのみ読み取られる場合、このタグは存在しません。 */
    val hasVisibleTab: Boolean get() = pool.current(taskId) != null

    /** 現在のラベルは、ユーザーが見ている借用ページです ([borrow] を参照)。これは Web ページ表示上にはなく、ピクチャ イン ピクチャにはピクチャが表示されません。 */
    val onBorrowedTab: Boolean get() = pool.current(taskId)?.let { pool.isExternal(it.id) } == true

    // ---------- ラベルをお借りしました ----------

    /**
     * は、ユーザーがブラウザ ページで見ているラベル [tab] を借用し、それをこのタスクの現在のラベルとして使用します ([WebTabPool.attachExternal] を参照)。生と死は依然として元の所有者に属します。
     * にアクセスできず (クローズまたはクラッシュ)、false を返します。メインスレッド。
     */
    fun borrow(tab: WebTab): Boolean = pool.attachExternal(taskId, tab).also { if (it) Log.i(TAG, "task $taskId borrowed tab ${tab.id}") }

    /** 借用したタグ [tab] を返します (タスクは終了し、ユーザーはタグを返却しません)。借りたタグは動かせないものではありません。メインスレッド。 */
    fun giveBack(tab: WebTab) {
        if (pool.detachExternal(tab.id)) Log.i(TAG, "task $taskId returned tab ${tab.id}")
    }

    /**
     * 借用したラベルは [next] に置き換えられました (ユーザーのページがポップアップ ウィンドウを開いたり閉じたりすると、表示されているラベルが別のラベルになります)。元のラベルが返され、新しいラベルが借用されます。
     * 現在のラベルが新しいラベルであることがわかりました。メインスレッド。
     */
    fun swapBorrowed(prev: WebTab, next: WebTab) {
        val wasCurrent = pool.current(taskId) === prev
        giveBack(prev)
        if (wasCurrent || pool.current(taskId) == null) borrow(next)
    }

    /**
     * ユーザーが貸与されたページがあるブラウザ ページを閉じると、ラベルには次のタスク ([adopt]) が与えられます。借用したコピーを返し、プール内の同じ WebView を通常のラベルとして受け入れるというタスクです。
     * は、Web ページ表示にフックされると通常どおり実行され、ページのステータス (スクロール、フォーム、単一ページのアプリケーションのステータス) が失われることはありません。タスクが終了すると他のタグで閉じられます。受け入れられない（クローズ、クラッシュ）場合は false が返されます。
     * は呼び出し元自身によって破棄されます。メインスレッド。
     */
    fun adopt(tab: WebTab): Boolean {
        val wasCurrent = pool.current(taskId) === tab
        giveBack(tab)
        return pool.adopt(taskId, tab, current = wasCurrent).also { if (it) Log.i(TAG, "task $taskId adopted tab ${tab.id}") }
    }

    /**
     * テイクオーバー: 現在のラベルをロックし、ホーム画面のテイクオーバー インターフェイスに表示します。現在のラベルがない場合、またはインターフェイスを開けない場合は false を返します。
     * [actions] インターフェイス上のボタンをタスクに与えます (処理済み、自分で終了、キャンセル、そのままにしておきます)。 [currentKey] は、現時点でのタスクの待機番号です。
     */
    fun showForTakeover(reason: String, message: String?, waitKey: Int, actions: (WebHandover.Action) -> Unit, currentKey: () -> Int?): Boolean {
        val tab = pool.current(taskId) ?: return false
        pool.lock(tab.id)
        // 借用したタグはすでにユーザー (ブラウザ ページ) の前にあります。移動せずにロックし、ユーザーがタグを処理して返すのを待ちます。
        val shown = if (pool.isExternal(tab.id)) !tab.destroyed
            else WebHandover.show(context, tab, host, reason, message, waitKey, taskId, actions, currentKey)
        if (shown) handedTab = tab else pool.unlock(tab.id)
        // 人間とコンピューターの検証またはアクセス傍受によりタスクが実際にブロックされます (ユーザーに引き渡される必要があります): タスクが配置されている Web サイトを傍受に遭遇したものとして記録します。
        if (shown && reason in SiteRegistry.CHALLENGE_KINDS) {
            tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host)?.let { WebSites.registry(context).challenged(it, reason) }
        }
        return shown
    }

    /** ハンドオーバー: 引き継ぎインターフェースを終了し、ラベルをバックグラウンドに戻し、ロックを解除します。 [loggedIn] ログインしていることがページに表示されると、Web サイトはログイン済みとして記録されます。引き継ぎ中でない場合は null です。 */
    fun returnFromTakeover(loggedIn: Boolean): HandoverActivity? {
        val tab = handedTab ?: return null
        handedTab = null
        if (pool.isExternal(tab.id)) {
            pool.unlock(tab.id)
            if (loggedIn && !tab.destroyed) LoginProbe.probe(tab) { host, state -> if (state == LoginState.SignedIn) WebSites.registry(context).markLoggedIn(host) }
            return HandoverActivity(borrowedTouches?.invoke(tab) ?: 0, tab.view.title?.takeIf { it.isNotBlank() })
        }
        val r = WebHandover.end(loggedIn, WebSites.registry(context), tab)
        pool.unlock(tab.id)
        return r
    }

    /** ユーザーが借用したタグを数回タッチしました (タグが引き継がれて返されたときに結果が記録されます)。貸し手側から与えられます。 */
    @Volatile var borrowedTouches: ((WebTab) -> Int)? = null

    /**
     * タスク終了：引き継ぎ途中で引き戻し、このタスクのラベルと表示を消します。モデルが web_keep_open でページをマークすると、そのページとこの表示が [keeper] に渡されます。
     * はバックグラウンドに留まり、実行を続けます (ディスプレイはオフになりません)。お渡しできない場合（ページが閉じられているか折りたたまれている場合）は、通常通り完全に閉じられます。
     */
    fun close() {
        runCatching { returnFromTakeover(loggedIn = false) }.onFailure { Log.w(TAG, "takeover not returned cleanly", it) }
        // まず、保持したいページをプールから取り出します。他のタブを閉じても、他のページがディスプレイに引っかからず、押し出されることはありません。
        val kept = runCatching { pool.closeKeeping(taskId, screen.keepMark?.tabId) }.onFailure { Log.w(TAG, "kept page not released", it) }.getOrNull()
        runCatching { pool.close() }
        val handed = kept != null && runCatching { handOff(kept) }.onFailure { Log.w(TAG, "kept page not handed off", it) }.getOrDefault(false)
        if (kept != null && !handed) runCatching { kept.destroy() }
        if (!handed) runCatching { host.close() }
        runCatching { files?.close() }
        transfers.cancel()
    }

    /** は残りのページを [keeper] に渡します。受け入れた後は true を返します。 */
    private fun handOff(tab: WebTab): Boolean {
        val k = keeper ?: return false
        val origin = keepOrigin?.invoke() ?: return false
        return k.keep(tab, host, origin) != null
    }

    companion object {
        private const val TAG = "MikaWebStack"

        /** セットを構築します。これは、システム WebView が利用できない場合や他の理由で構築できない場合にスローされ、呼び出し元は携帯電話インターフェイスのみを提供します。メインスレッドによって呼び出されます。 */
        fun create(
            context: Context, taskId: String, widthPx: Int, heightPx: Int, densityDpi: Int,
            assetLoaders: List<WebViewAssetLoader> = emptyList(),
            workspace: Workspace? = null,
            keeper: PageKeeper? = WebOwner.of(context).pageKeeper,
        ): WebStack {
            val host = WebHost(context, widthPx, heightPx, densityDpi)
            return try {
                val pool = WebTabPool(context, host, assetLoaders = assetLoaders, registry = WebSites.registry(context))
                WebStack(context.applicationContext, taskId, host, pool, workspace, keeper)
            } catch (e: Exception) {
                host.close()
                throw e
            }
        }
    }
}
