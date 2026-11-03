package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.KeyCombo
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.uistate.PageDialog

data class TabInfo(val tabId: String, val url: String, val title: String, val current: Boolean)

/**
 * [externalLink]: ページはアプリ (意図:/カスタム プロトコル/アプリ ストア) にジャンプしようとしていますが、ジャンプはありません。
 * [settled]: DevTools がある場合、ネットワークがアイドル状態でページの読み込み後に静かな場合は true、上限が静かでない場合は false になります。 DevTools がない場合は null になります。
 * [netError]: メインドキュメントが開かれていないときにブラウザーによって表示されるエラー コード (net::ERR_NAME_NOT_RESOLVED など)。開いたときは null です。
 * [upgradedFrom]: プレーンテキスト http が拒否された後、再試行するときに https (元の http URL) を使用します。
 * [download]: URL は実際にはファイルですが、今回は開かれてダウンロードに変換されます。そのダウンロード記録。
 */
data class NavResult(
    val tab: TabInfo,
    val loaded: Boolean,
    val httpStatus: Int?,
    val newTab: Boolean,
    val externalLink: ExternalLink?,
    val settled: Boolean? = null,
    val netError: String? = null,
    val upgradedFrom: String? = null,
    val download: DownloadInfo? = null,
    /** 読み込みページがポップアップ表示されますが、まだ応答を待っています (表示されると読み込み待機は停止します)。 */
    val dialog: PageDialog? = null,
)

/**
 * はページ テキスト全体の段落です。 [links] および [images] は (テキストまたは説明、アドレス) です。 [nextOffset] は次の段落の開始点であり、読み取り後は null になります。
 * [quality] は、ページの読み取り品質信号です ([PageRead] を押して読み取るときに発生します)。 [netError] は、バックグラウンド タブで URL を開くことができなかった場合にブラウザによって表示されるエラー コードです。
 */
data class PageText(
    val url: String,
    val title: String,
    val text: String,
    val links: List<Pair<String, String>>,
    val images: List<Pair<String, String>>,
    val truncated: Boolean,
    val nextOffset: Int?,
    val quality: ReadQuality? = null,
    val netError: String? = null,
)

/** [file] は、サンドボックス パス /workspace/downloads/<name> です。 [status] は完了|失敗|実行されました。 */
data class DownloadInfo(val file: String, val bytes: Long, val mime: String?, val status: String, val error: String?)

/**
 * web_keep_open の結果: [keep] が true の場合、このページ ([tabId]、[url]、[title]、サイト [host]) はバックグラウンドに残り、タスク終了後も実行され続けます。
 * は最大 [hours] 時間です。 [replaces] は、現時点で残っている別のページのサイト (タスクが終了すると閉じられます) であり、null ではありません。 [keep] が false の場合、このタスクの残りのページはキャンセルされます。
 */
data class KeepOpenResult(
    val keep: Boolean,
    val tabId: String?,
    val url: String?,
    val title: String?,
    val host: String?,
    val hours: Int,
    val replaces: String? = null,
)

/** 現在のタブは、ユーザーが Mega ブラウザーで見ているページ (借用) です。タスクによって閉じられないため、保持する必要はありません。 */
class WebTabIsUsers(val tabId: String) : Exception("tab $tabId is the user's own page")

/** web_site_mode の結果: [host] 現在のページ [mobile] のサイトは、モバイル バージョン [reloaded] を使用しています。 現在のページは、新しいバージョンに従ってリロードされました。 */
data class SiteModeResult(val host: String, val mobile: Boolean, val reloaded: Boolean)

sealed interface EvalJs {
    /** [json] は、減感と切り捨て後の結果です。 [fresh]: 今回は、新しいタグ読み取りワールドが作成され、以前に状態に存在していたタグ読み取りワールドは削除されます。 */
    data class Ok(val json: String, val truncated: Boolean, val fresh: Boolean = false) : EvalJs

    /**
     * [error] は script_error|timeout|read_only (スクリプトはページを変更しようとしています)|invalid_selector|blocked_network|blocked_navigation| です。
     * 制限を超えました|ネットワークエラー|大きすぎます|ナビゲートされました(ページが変更されたドキュメント)|devtools_unavailable|no_tab_open。
     */
    data class Failed(val error: String, val message: String) : EvalJs
}

/** は web_wait_for の条件です。 [wire] はツールパラメータに記載する方法です。 */
enum class WaitKind(val wire: String) {
    TEXT_PRESENT("text_present"), TEXT_ABSENT("text_absent"), NETWORK_IDLE("network_idle"), URL_CONTAINS("url_contains");

    companion object {
        fun of(wire: String): WaitKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** [met]: 制限時間内に条件が成立しました。 [url] が末尾のアドレスになります。 */
data class WaitOutcome(val met: Boolean, val waitedMs: Long, val url: String?)

/** 現在のラベルには DevTools セッションがありません (システムがサポートしていないため、接続できません): ネットワークを使用して記録されるツールの結果は、devtools_unavailable になります。 */
class DevtoolsUnavailable : Exception("devtools unavailable")

/** Web ページ タグ: 携帯電話の画面読み取り/操作用の同じインターフェイス セットに、独自のナビゲーション、ページ読み取り、タグ、Web ページへのダウンロードとアップロードを追加します。 */
interface WebPort : ScreenPort {
    suspend fun navigate(url: String, newTab: Boolean): NavResult
    /** [scrollLazy]: コンテンツを読み取る前に 1 回スクロールする遅延読み込みをトリガーします (ページが移動します)。証拠を検証および収集する場合は false であり、読み取り専用でスクロールしません。 */
    suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean = true): PageText

    /**
     * web_read_page の読み取り方法: 高品質信号、折りたたみリスト ブロック付き。 [PageRead.url] は、指定されると一時的なバックグラウンド タグで開かれ、読み取り後に閉じられ、現在のタグは移動されません。
     * がバックグラウンド読み取りをサポートしていない場合、URL は [UnsupportedOperationException] をスローします。
     */
    suspend fun read(req: PageRead): PageText =
        if (req.url == null) readPage(req.maxChars, req.offset, req.lazy) else throw UnsupportedOperationException("background reads are not available")
    /**
     * 読み取りスクリプト: 現在のタグの実ページの読み取りワールドで読み取り専用 [script] ([ReadScriptProtocol] を参照) を実行します (上限は [timeoutMs])。
     * [args] は、サイト コマンドのパラメーター (スクリプト内の引数) です。同じタグを持つ同じドキュメントに対する複数の呼び出しは、状態を共有します。
     * DevTools は使用できません。 devtools_unavailable を報告し、他のメソッドには戻りません。
     */
    suspend fun evaluate(script: String, timeoutMs: Long, args: JsonObject? = null): EvalJs
    suspend fun tabs(): List<TabInfo>
    suspend fun switchTab(tabId: String): TabInfo?
    suspend fun closeTab(tabId: String): Boolean
    /** [UnsupportedOperationException] は、ダウンロード機能が接続されていない場合にスローされ、ツールの結果は downloads_unavailable になります。 */
    suspend fun downloads(): List<DownloadInfo>
    suspend fun upload(ref: String, snapshotId: String, paths: List<String>): ActionResult
    suspend fun historyBack(): ActionResult
    /**
     * 現在のラベルのサイトが Web ページのモバイル バージョンまたはデスクトップ バージョン (デフォルトのデスクトップ バージョン) に変更され、サイトごとに記憶され、現在のページがリロードされます。 Web ページが開かれていない場合、
     * は null です。サポートされていない場合は、[UnsupportedOperationException] がスローされます。
     */
    suspend fun setSiteMode(mobile: Boolean): SiteModeResult? = throw UnsupportedOperationException("site mode is not available")

    /**
     * は現在のラベルを離れ、タスク完了後も実行を続けます ([keep] が false の場合、このタスクの残りのページはキャンセルされます)。デフォルトでは、タスクの終了時にすべてのタブが閉じられます。残りのページは
     * [KeepOpenResult.hours] 時間、グローバルに 1 ページだけが残ります。開かれていない Web ページは null です。現在のタグがユーザー自身のページの場合、[WebTabIsUsers] がスローされます。
     * は、時間スロー [UnsupportedOperationException] をサポートしていません。
     */
    suspend fun keepOpen(keep: Boolean, reason: String): KeepOpenResult? = throw UnsupportedOperationException("keeping a page open is not available")

    /** 現時点で現在のタグで使用されているバージョン: `mobile` または `desktop`。開いている Web ページがないか、null です。 */
    suspend fun siteMode(): String? = null

    /**
     * は、現在のラベルの実ページの分離された世界で [code] (非同期関数本体) を実行し、`mika.*` の呼び出しは [bridge] を介してミカに戻ります。上限は [timeoutMs] です。
     * DevTools は使用できません。 devtools_unavailable を報告し、他のメソッドには戻りません。
     */
    suspend fun runScript(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun =
        ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "Page scripts need the page's DevTools connection, which is not available here.")

    // web_interact: DevTools セッションを必要とするアクション。接続できない場合は devtools_unavailable を返します。接続したふりをしないでください
    suspend fun hover(at: WebPoint): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    suspend fun drag(from: WebPoint, to: WebPoint, steps: Int): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** は、項目を選択するためのネイティブのドロップダウン ボックスです。 [option] は、オプションの表示テキストまたは値です。 */
    suspend fun selectOption(ref: String, snapshotId: String, option: String): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** は、現在のラベルの上位応答のページ ポップアップ ボックスに応答します ([PageDialogs] を参照)。 [text] は入力ボックスのポップアップ ボックスのみを表示します。 no_dialog を報告するポップアップ ボックスはありません。 */
    suspend fun answerDialog(accept: Boolean, text: String?): ActionResult = ActionResult.Failed(NO_DIALOG)
    /** 現在のフォーカスに対してキーの組み合わせを 1 回押します。 */
    suspend fun pressCombo(combo: KeyCombo): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** 現在のタグによって記録された XHR/フェッチ応答 (ボディなし)、古いものから。 DevTools がない場合は、[DevtoolsUnavailable] をスローします。 */
    suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> = throw DevtoolsUnavailable()

    /** 応答を取得し、本文 (JSON/テキスト、大きすぎないもの) を取得できる場合はそれを返します。レコードにない場合は null です。 DevTools がない場合は、[DevtoolsUnavailable] をスローします。 */
    suspend fun capture(id: String): CapturedResponse? = throw DevtoolsUnavailable()

    /** など [kind] が確立され、[timeoutMs] までになります。ネットワークの状況によっては、DevTools がない場合に [DevtoolsUnavailable] がスローされます。 */
    suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome = throw DevtoolsUnavailable()

}

/** web_interact: 画面番号の読み取り、またはピクセル座標の表示の着地点。 */
sealed interface WebPoint {
    data class Ref(val ref: String, val snapshotId: String) : WebPoint
    data class Xy(val x: Int, val y: Int) : WebPoint
}

const val DEVTOOLS_UNAVAILABLE = "devtools_unavailable"
const val NO_DIALOG = "no_dialog"
/** ページ上に返信を待っているポップアップ ボックスがあります。他の操作は行わず、最初に返信してください。 */
const val DIALOG_OPEN = "dialog_open"

/** 現在のラベルは処理のためにユーザーに渡されています (引き継がれています)。 ActionResult 以外を返すメソッドはこのメソッドを使用してレポートし、ツールの結果は Handed_over になります。 */
class WebTabHandedOver(val tabId: String) : Exception("tab $tabId is with the user")

/** 現在のラベルのページ ポップアップ ボックスがハングしています (ページが停止しています): ページを読み取って URL を開くには、このメソッドを使用します。ツールの結果は、dialog_open です。 */
class WebDialogOpen(val tabId: String) : Exception("tab $tabId has a page dialog open")

/** タグのレンダリング プロセスがクラッシュし (またはシステムによってリサイクルされ)、タグが破棄されました。このメソッドを使用して非 ActionResult を報告すると、ツールの結果は tab_crashed になります。 */
class WebTabCrashed(val tabId: String) : Exception("tab $tabId crashed")
