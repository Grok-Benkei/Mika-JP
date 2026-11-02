package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenFacts
import com.wangzan101.mika.core.uistate.ScreenState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * Web ページで収集された事実: [base] は携帯電話と同じ (表示されるテキスト、パスワード ボックス、黒いフレームなど) に加えて、メイン ドキュメントの HTTP ステータス、
 * ページ内のフレームのアドレス (クロスオリジンのみ) と現在のページのホスト名。
 */
data class WebFacts(val base: ScreenFacts, val httpStatus: Int?, val frameUrls: List<String>, val host: String?)

/** Web ページの障害物の判定: 最初に Web ページの固有の兆候を確認し、次に携帯電話 [ScreenDiagnosis.classify] と同じセットに分類されます。 */
object WebBarrier {
    private val RISK_STATUS = setOf(403, 429, 503)
    private val RISK_WORDS = listOf("访问受限", "频繁", "异常", "blocked", "too many requests")

    /** 人間とマシンの検証サービスのフレームワーク ホスト (ドメイン名のサフィックスで識別)。 */
    private val CAPTCHA_HOSTS = listOf(
        "recaptcha.net", "hcaptcha.com", "challenges.cloudflare.com", "geetest.com", "arkoselabs.com", "funcaptcha.com",
    )
    /** ホスト名自体は特に検証されず、パス (ホスト、パス プレフィックス) によって区別されます。 */
    private val CAPTCHA_PATHS = listOf("google.com" to "/recaptcha", "gstatic.com" to "/recaptcha")
    private val CAPTCHA_HOST_WORDS = listOf("captcha")

    /**
     * リスク コントロール インターセプト ページのステートメント (Web サイトは、この訪問は人間のようには見えず、環境が間違っていると感じています)。単独で表示される場合でも (大文字と小文字に関係なく、比較中に空白を削除します)。
     * は、ページ上の単語が少ない場合にのみ認識されます。長いテキスト (そのような問題に関する記事) を含むページはカウントされません。
     */
    private val INTERSTITIAL_WORDS = listOf(
        "访问异常", "异常访问", "访问行为异常", "访问存在异常", "环境异常", "环境存在异常", "存在安全风险", "当前访问存在风险",
        "操作频繁", "操作过于频繁", "访问过于频繁", "请求过于频繁", "访问频繁",
        "unusualtraffic", "unusualactivity", "suspiciousactivity", "automatedqueries", "automatedrequests", "checkingyourbrowser",
        "accesstemporarilyrestricted", "requestwasblocked", "requesthasbeenblocked",
    )
    /** はタイトル (「安全に関するヒント」と「セキュリティ チェック」) だけで、同じページに異常、検証、頻繁などの単語もあります。 */
    private val INTERSTITIAL_TITLES = listOf("安全提示", "安全检查", "安全警告", "securitycheck", "securitynotice", "securityverification")
    private val INTERSTITIAL_CUES = listOf("异常", "验证", "频繁", "风险", "unusual", "verify", "suspicious", "robot", "automated")
    /** 「ページに含まれる単語数が少ない」の上限を計算します。つまり、表示される単語の総数 (空白を除く) と行数です。 */
    private const val INTERSTITIAL_MAX_CHARS = 800
    private const val INTERSTITIAL_MAX_TEXTS = 40

    /** インターフェイス応答は、リスク制御によってブロックされたことを示します。[INTERSTITIAL_WORDS] と同じタイプですが、インターフェイスの一般的な書き込みメソッドが追加されています。 */
    private val API_RISK_WORDS = INTERSTITIAL_WORDS + listOf(
        "环境存在风险", "账号存在风险", "请求异常", "访问受限", "abnormal", "riskcontrol", "toomanyrequests", "ratelimit",
    )
    /** は、トップレベルのレポート情報に応じて、これらのフィールド (小文字) のみを確認します。 */
    private val API_MESSAGE_KEYS = setOf("message", "msg", "errmsg", "errormsg", "error_msg", "errormessage", "error", "tip", "tips", "toast", "reason")
    private const val API_MESSAGE_MAX = 200

    /** 比較する場合は空白を削除してください。「続行するにはアプリをダウンロードしてください」は「続行するにはアプリをダウンロードしてください」と同じです。 */
    private val UNSUPPORTED_WORDS = listOf(
        "请在浏览器中打开", "当前浏览器不支持", "不支持此浏览器", "请下载app继续", "browserisnotsupported", "browsernotsupported",
    )

    fun classify(f: WebFacts): ScreenCheck {
        // 携帯電話に関する同じ一連のルール (パスワード ボックス、確認コード ワード、支払い) が個人によって処理されることが決定されており、これが優先されます。
        val base = ScreenDiagnosis.classify(f.base)
        if (base.state == ScreenState.NEEDS_HUMAN) return base
        val text = f.base.texts.joinToString("\n").lowercase()
        val compact = text.filterNot(Char::isWhitespace)
        val app = f.base.currentApp ?: f.host
        if (f.httpStatus in RISK_STATUS) {
            RISK_WORDS.firstOrNull { it in text }?.let {
                return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "access_blocked", "HTTP ${f.httpStatus} with text: $it")
            }
        }
        f.frameUrls.firstOrNull { captchaFrame(it, f.host) }?.let {
            return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "captcha", "verification frame from ${WebUrlPolicy.host(it)}")
        }
        interstitial(f.base.texts, compact)?.let {
            return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "access_blocked", "risk-control page: \"$it\"")
        }
        UNSUPPORTED_WORDS.firstOrNull { it in compact }?.let {
            return ScreenCheck(ScreenState.NORMAL, app, false, ScreenDiagnosis.UNSUPPORTED_BROWSER,
                "the site refuses this embedded browser (\"$it\"): switch route to the app if it is installed, or end_not_done")
        }
        return base
    }

    /** 単語数の少ないページ上のリスク管理傍受ページのステートメント。 nullではありません。 */
    private fun interstitial(texts: List<String>, compact: String): String? {
        if (texts.size > INTERSTITIAL_MAX_TEXTS || compact.length > INTERSTITIAL_MAX_CHARS) return null
        INTERSTITIAL_WORDS.firstOrNull { it in compact }?.let { return it }
        val title = INTERSTITIAL_TITLES.firstOrNull { it in compact } ?: return null
        return INTERSTITIAL_CUES.firstOrNull { it in compact }?.let { "$title … $it" }
    }

    /**
     * ページ自体が受信したインターフェイス応答 [body] は、ページがリスク制御によってブロックされたことを示しています。JSON オブジェクトの最上位レポートのフィールド (メッセージ、メッセージ、エラーなど) にそのようなステートメントがあります。
     * はステートメント (切り捨てられた) を返します。 JSON ではないか、そのようなフィールドがないか、言及されていない場合は null です。
     */
    fun apiRisk(body: String?): String? {
        val text = body?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        for ((k, v) in o) {
            if (k.lowercase() !in API_MESSAGE_KEYS) continue
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val compact = s.lowercase().filterNot(Char::isWhitespace)
            if (compact.length > API_MESSAGE_MAX) continue
            if (API_RISK_WORDS.any { it in compact }) return s.trim().take(API_MESSAGE_MAX)
        }
        return null
    }

    /** クロスオリジン (異なるホスト名とページ) であり、マンマシン認証サービス フレームワークを指します。 */
    private fun captchaFrame(url: String, pageHost: String?): Boolean {
        val h = WebUrlPolicy.host(url) ?: return false
        if (pageHost != null && h == pageHost.lowercase().removePrefix("www.")) return false
        fun under(d: String) = h == d || h.endsWith(".$d")
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return CAPTCHA_HOSTS.any(::under) || CAPTCHA_PATHS.any { (d, p) -> under(d) && path.startsWith(p) } ||
            CAPTCHA_HOST_WORDS.any { it in h }
    }
}
