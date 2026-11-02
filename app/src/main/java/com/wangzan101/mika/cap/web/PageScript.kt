package com.wangzan101.mika.cap.web

import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.PageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.resume

/** は、クリックする前に番号でチェックされます。クリックが取得され ([x]、[y] はトップ ビューポートの CSS ピクセル)、他の要素で覆われ、ビューポートの外側にあり、要素は存在しません。 */
sealed interface HitResult {
    data class Hit(val x: Int, val y: Int) : HitResult
    data class Obscured(val byName: String) : HitResult
    data object Offscreen : HitResult
    data object Gone : HitResult
}

/** 障害物の判定: ページ上にパスワード ボックスがあるかどうか、およびクロスオリジン フレームの完全なアドレス。 */
data class PageFacts(val passwordField: Boolean, val frameUrls: List<String>)

/** 入力ボックスの現在の値。パスワードボックスには長さのみが表示されます。 */
sealed interface FieldValue {
    data class Text(val value: String) : FieldValue
    data class Password(val length: Int) : FieldValue
    data object NotAField : FieldValue
}

/**
 * ページ スクリプトのアプリ側、assets/web/page.js: 非同期結果の挿入、呼び出し、ポーリング。モデルが書いたスクリプトはここを通過しません
 * (読み取りスクリプトとページ スクリプトはそれぞれ独自の隔離された世界で実行されます)、ここでは独自の固定コードのみが呼び出されます。
 * システムがサポートしている場合、各ドキュメントの先頭 (ページ独自のスクリプトの前、元のエントリは最初にページによって変更されません) に挿入されます。それ以外の場合は、読み込みの完了後に挿入されます。
 * ページには、ランダムな名前のブリッジ関数のみがあります。インターフェイスを取得するには、ここでのみ知られているキーを使用する必要があります。ページはアプリへの入り口を取得できません。 すべての
 * の結果は、evaluateJavascript を介してここから取得されます。メインドキュメントが変更され、スクリプトが存在しない場合、次回呼び出されたときにスクリプトが自動的に挿入されます。
 */
/** [useDocumentStart] が false の場合、読み込みが完了した後にのみ挿入されます (これは、システムが最初からドキュメントの挿入をサポートしていない場合に当てはまります。これはテストにも使用されます)。 */
class PageScript(private val tab: WebTab, private val useDocumentStart: Boolean = true) {
    /** ページ上のブリッジ関数の名前。読み込み完了後の挿入時に、ドキュメントごとに 1 つ (キーとともに) 変更します。原稿の投入が開始されると、登録時のペアが固定される。 */
    @Volatile internal var bridgeName = newBridgeName()
        private set
    @Volatile private var secret = UUID.randomUUID().toString()
    private val source: String by lazy {
        tab.view.context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }
    private val bootstrap: String get() = "(\n$source\n)(${quote(bridgeName)},${quote(secret)})"
    private var documentStartRegistered = false

    /** ドキュメントから注入を開始するかどうか。 false の場合、ページ スクリプトはこのスクリプトの前にグローバル オブジェクトを変更する可能性があります。 */
    var documentStart: Boolean = false
        private set

    /** は、登録されたドキュメントの挿入を開始し (1 回のみ)、現在のドキュメントにない場合はそれを挿入します。 */
    suspend fun install() {
        withContext(Dispatchers.Main) {
            if (!documentStartRegistered) {
                documentStartRegistered = true
                if (useDocumentStart && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(tab.view, bootstrap, setOf("*"))
                    documentStart = true
                }
            }
        }
        if (!present()) inject()
    }

    private suspend fun present(): Boolean =
        raw("(function(){var g=window[${quote(bridgeName)}];return typeof g==='function'&&!!g(${quote(secret)});})()") == "true"

    // ロード後のインジェクション: 新しいドキュメントごとに新しい名前とキーが使用されます。前の文書で漏洩した名前は、新しい文書では役に立ちません。
    private suspend fun inject() {
        if (!documentStart) {
            bridgeName = newBridgeName()
            secret = UUID.randomUUID().toString()
        }
        raw(bootstrap)
    }

    /**
     * ビューポートに表示される要素の JSON (PageTree の入力形式)。ページを読み取れない場合は、空のツリーが表示されます。
     * 番号テーブルは [key] (スナップショット番号) として保存され、最新の番号テーブルがページに保持されます。 [key] が null の場合、読み取り専用となり登録されません。
     */
    suspend fun tree(key: String? = DEFAULT_KEY): String =
        call("tree(${key?.let(::quote) ?: "null"})")?.takeIf { it is JsonObject }?.toString() ?: EMPTY_TREE

    /**
     * は、DevTools を介してページのメイン環境で評価される式です。数値テーブル [key] の要素配列を返します (null は最後に読み取られた画面です)。スクリプトが使用できない場合は null を返します。
     * は、[call] を経由せず、DevTools によってのみ使用されます (要素オブジェクトを返します)。
     */
    internal fun refNodesExpression(key: String?): String =
        "(function(){var g=window[${quote(bridgeName)}];var m=typeof g==='function'&&g(${quote(secret)});" +
            "return m?m.refNodes(${key?.let(::quote) ?: "null"}):null;})()"

    suspend fun facts(): PageFacts {
        val r = call("facts()") as? JsonObject ?: return PageFacts(false, emptyList())
        val frames = (r["frames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return PageFacts(r["password"]?.jsonPrimitive?.booleanOrNull == true, frames)
    }

    suspend fun mutationCount(): Long = (call("mutationCount()") as? JsonPrimitive)?.long ?: 0L

    /** まず要素をビューポートの中心までスクロールし、次に表示部分 (上部のビューポート CSS ピクセル) の中心に配置します。要素は null ではなくなりました。 */
    suspend fun centerOf(ref: String, key: String = DEFAULT_KEY): Pair<Int, Int>? = pair(call("centerOf(${quote(ref)},${quote(key)})"))

    suspend fun hitTest(ref: String, key: String = DEFAULT_KEY): HitResult {
        val r = call("hitTest(${quote(ref)},${quote(key)})") as? JsonObject ?: return HitResult.Gone
        return when (r["k"]?.jsonPrimitive?.content) {
            "hit" -> HitResult.Hit(r["x"]!!.jsonPrimitive.int, r["y"]!!.jsonPrimitive.int)
            "obscured" -> HitResult.Obscured(r["by"]?.jsonPrimitive?.content.orEmpty())
            "offscreen" -> HitResult.Offscreen
            else -> HitResult.Gone
        }
    }

    /** [ref] が null の場合、現在フォーカスがある入力ボックスを読み取ります。 */
    /** ページ内の BLOB: URL のコンテンツ。 [maxBytes]を超えて読み取れない場合は理由を記載してください。 */
    sealed interface Blob {
        class Ok(val mime: String?, val bytes: ByteArray) : Blob
        class Failed(val error: String) : Blob
    }

    /**
     * を使用すると、ページ スクリプトで blob: コンテンツを Base64 に読み取り、セグメントで取得してデコードできます。ページの長さと各段落の内容は、[BlobChunks] の上限に従って計算されます。
     * およびその他の結果のタイムアウトやエラーにより、ページはこのコンテンツを破棄します (ページには有効期限切れと自動消去もあります)。
     */
    suspend fun readBlob(url: String, maxBytes: Long): Blob {
        val token = nextToken()
        try {
            if (call("startBlob(${quote(token)},${quote(url)},$maxBytes)") == null) return Blob.Failed(BlobChunks.UNREADABLE)
            val meta = poll(token, BLOB_TIMEOUT_MS) as? JsonObject ?: return Blob.Failed(BlobChunks.UNREADABLE)
            if ((meta["ok"] as? JsonPrimitive)?.booleanOrNull != true) return Blob.Failed((meta["error"] as? JsonPrimitive)?.content ?: BlobChunks.UNREADABLE)
            val length = (meta["length"] as? JsonPrimitive)?.intOrNull ?: return Blob.Failed(BlobChunks.UNREADABLE)
            val chunks = BlobChunks(length, maxBytes)
            chunks.refusal()?.let { return Blob.Failed(it) }
            while (!chunks.complete) {
                val part = (call("blobChunk(${quote(token)},${chunks.received})") as? JsonPrimitive)?.content ?: return Blob.Failed(BlobChunks.UNREADABLE)
                if (part.isEmpty()) break
                chunks.add(part)?.let { return Blob.Failed(it) }
            }
            return when (val o = chunks.finish()) {
                is BlobChunks.Outcome.Ok -> Blob.Ok((meta["mime"] as? JsonPrimitive)?.content?.ifBlank { null }, o.bytes)
                is BlobChunks.Outcome.Failed -> Blob.Failed(o.error)
            }
        } finally {
            call("blobDone(${quote(token)})")
        }
    }

    /**
     * ページの [url] を指すリンクに指定されたダウンロード名 (`<a download="…">`): クリックされたときにページ スクリプトによって記録されるか、ドキュメント内でそれを指すリンクに指定されます。
     * は null ではありません。名前は Web ページによって付けられ、ファイル名の規則に従ってダウンローダーによってクリーンアップされます。非常に長いデータ: URL は比較できません。
     */
    suspend fun downloadName(url: String): String? {
        if (url.length > MAX_NAMED_URL_CHARS) return null
        return (call("downloadName(${quote(url)})") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** メインドキュメント応答の HTTP ステータス。利用できない場合は null。 */
    suspend fun navStatus(): Int? = (call("navStatus()") as? JsonPrimitive)?.intOrNull

    /** この要素 (またはその編集可能領域) の現在のフォーカスです。 */
    suspend fun isActive(ref: String, key: String = DEFAULT_KEY): Boolean =
        (call("isActive(${quote(ref)},${quote(key)})") as? JsonPrimitive)?.booleanOrNull == true

    suspend fun fieldValue(ref: String?, key: String = DEFAULT_KEY): FieldValue {
        val r = call("fieldValue(${ref?.let(::quote) ?: "null"},${quote(key)})") as? JsonObject ?: return FieldValue.NotAField
        return when (r["k"]?.jsonPrimitive?.content) {
            "password" -> FieldValue.Password(r["len"]!!.jsonPrimitive.int)
            "text" -> FieldValue.Text(r["v"]?.jsonPrimitive?.content.orEmpty())
            else -> FieldValue.NotAField
        }
    }

    /**
     * 読み取り可能なテキストの全ページ (PageText の JSON)。 [scroll] が true の場合、最初のページを読み取る前に最大 5 画面下にスクロールして遅延読み込みをトリガーし、読み取り後にリセットします。
     * ページのスクロール位置に触れずに結果を確認する場合は false を使用します。
     */
    suspend fun readPage(maxChars: Int, offset: Int, scroll: Boolean = true): String {
        val token = nextToken()
        val max = maxChars.coerceIn(1, MAX_READ_CHARS)
        if (call("startRead(${quote(token)},$max,${offset.coerceAtLeast(0)},$scroll)") == null) return error("read_failed", "page script unavailable")
        val r = poll(token, READ_TIMEOUT_MS)
        if (r == null) {
            call("discard(${quote(token)})")
            return error("read_failed", "page did not finish reading")
        }
        return r.toString()
    }

    /** web_read_page 全ページ読み取り (セグメント、折りたたみ可能なリスト ブロック、品質表示。形式については PageDigest を参照)、スクロールなし。読み取られない場合は null。 */
    suspend fun digest(): JsonObject? {
        val token = nextToken()
        if (call("startDigest(${quote(token)},${PageDigest.scriptOptions})") == null) return null
        val r = poll(token, READ_TIMEOUT_MS)
        if (r == null) call("discard(${quote(token)})")
        return (r as? JsonObject)?.takeIf { it["error"] == null }
    }

    /** テキストの (長さ、要約)。テキストが安定しているかどうかを判断するために使用されます。読み取れない場合は null です。 */
    suspend fun contentSample(): Pair<Int, Int>? = runCatching { pair(call("contentSample()")) }.getOrNull()

    /**
     * いくつかの画面を下にスクロールして遅延読み込みをトリガーし、スクロール後に戻ります。その後、[endLazy] を調整してスクロール位置をリセットする必要があります。トークンを返します。ページスクリプトが存在しない場合はnull。
     */
    suspend fun startLazy(): String? {
        val token = nextToken()
        if (call("startLazy(${quote(token)})") == null) return null
        poll(token, LAZY_TIMEOUT_MS)
        return token
    }

    suspend fun endLazy(token: String) {
        call("endLazy(${quote(token)})")
    }

    /** スクロール位置 (CSS ピクセル)。 [ref] が null の場合、それはページ自体です。要素は null ではなくなりました。 */
    suspend fun scrollState(ref: String?, key: String = DEFAULT_KEY): Pair<Int, Int>? =
        pair(call("scrollState(${ref?.let(::quote) ?: "null"},${quote(key)})"))

    private suspend fun poll(token: String, timeoutMs: Long): JsonElement? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = call("take(${quote(token)})")
            if (r != null && r !is JsonNull) return r
            if (tab.crashed) return null
            delay(POLL_MS)
        }
        return null
    }

    /** ページ スクリプトを呼び出すメソッド。スクリプトが使用できなくなった場合 (メインドキュメントが変更された場合)、スクリプトを挿入して再試行してください。結果が取得できない場合はnullとなります。 */
    private suspend fun call(expr: String): JsonElement? {
        repeat(2) { attempt ->
            val js = "(function(){var g=window[${quote(bridgeName)}];var m=typeof g==='function'&&g(${quote(secret)});" +
                "if(!m)return {missing:1};return {v:m.$expr};})()"
            val r = raw(js) ?: return null
            if (r == "null") return null
            val o = runCatching { Json.parseToJsonElement(r) }.getOrNull() as? JsonObject ?: return null
            if (o["missing"] == null) return o["v"] ?: JsonNull
            if (attempt == 0) inject()
        }
        return null
    }

    /** EvaluateJavascript の生のコールバック文字列。タイムアウトしたか、ラベルが無効化されて null になりました。 */
    private suspend fun raw(js: String, timeoutMs: Long = CALL_TIMEOUT_MS): String? {
        if (tab.crashed) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(js) { c.resume(it) } }
            }
        }
    }

    private fun pair(e: JsonElement?): Pair<Int, Int>? {
        val a = e as? JsonArray ?: return null
        if (a.size != 2) return null
        return a[0].jsonPrimitive.int to a[1].jsonPrimitive.int
    }

    private fun nextToken() = UUID.randomUUID().toString()

    private fun error(code: String, message: String) = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()

    private fun quote(s: String) = JsonPrimitive(s).toString()

    companion object {
        private fun newBridgeName() = "_" + UUID.randomUUID().toString().replace("-", "").take(16)
        private const val ASSET = "web/page.js"
        private const val MAX_NAMED_URL_CHARS = 100_000
        /** スナップショット番号が指定されていない場合にページ スクリプトによって使用される番号テーブル。 */
        const val DEFAULT_KEY = "default"
        private const val MAX_READ_CHARS = 30_000
        private const val READ_TIMEOUT_MS = 15_000L
        /** いくつかの画面をスクロールし、各画面でしばらく待ちます。通常、スクロールは 2 秒以内に完了します。ページがスタックしている場合、ページの読み取りは保留されません。 */
        private const val LAZY_TIMEOUT_MS = 5_000L
        private const val BLOB_TIMEOUT_MS = 30_000L
        private const val CALL_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 100L
        private const val EMPTY_TREE = """{"els":[],"vw":0,"vh":0,"dpr":1,"trunc":false,"mut":0}"""
    }
}
