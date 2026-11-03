package com.wangzan101.mika.cap.web

import android.util.Log
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.util.concurrent.Executors

/** ページに表示されるログイン ステータス。 */
enum class LoginState { SignedIn, SignedOut, Unknown }

/**
 * ログイン ステータスを判断するために使用されるページ サインは、ページ上部のアカウント領域 (ヘッダー、ナビゲーション バー、バナー、またはページの上部セクション) に表示されるもののみです。
 * [url] 現在のアドレス。 [passwordVisible] のページにはパスワード ボックスが表示されます。 [account] では、アカウント領域にリンクとボタン テキストが表示されます。
 * [avatars] アカウント領域に表示されるアバター (コメント、リスト、記事はカウントされません)。
 */
data class LoginSignals(
    val url: String?,
    val passwordVisible: Boolean,
    val account: List<String>,
    val avatars: Int,
)

/**
 * 一般的なログイン状態の判断、特定の Web サイトを認識しません:
 * - ログイン済み: アカウント領域には、「ログアウト/ログアウト」(全文がそれである、またはそれで始まります) などのリンク、または「パーソナル センター/マイ アカウント」などの入り口が表示されます。
 *   、目に見えるログイン入り口はありません。
 * - 終了: パスワード ボックスが表示されるか、アカウント領域に「ログイン/サインイン」入り口が表示されますが、アカウント入り口やアバターはありません。
 * - アバターは「ログアウト」を拒否するためにのみ使用されます。ログイン状態であるかどうかは判断できません。アドレスがログイン ページ (シングル サインオン、パス ジャンプ) のような場合、結論は得られません。
 * 以降は [LoginState.Unknown]: 登録フォームは移動しません。
 */
object LoginProbe {
    private val LOGIN_PATH = Regex("""(^|[/_.-])(log-?in|sign-?in|sign_in|logon|passport|sso|oauth|authorize|auth/login|account/login|user/login)([/_.?#-]|$)""", RegexOption.IGNORE_CASE)
    /** これはテキスト全体としてカウントされます (非常に一般的ですが、これで始まるほとんどの単語は「全画面表示を終了する」など、他の意味を持ちます)。 */
    private val LOGOUT_EXACT = setOf("退出", "注销", "logout", "signout", "ログアウト", "サインアウト")
    /** テキスト全体、またはテキスト全体 (その後に最大 [LOGOUT_SLACK] 文字が続きます)。 */
    private val LOGOUT = listOf("退出登录", "退出账号", "安全退出", "注销登录", "log out", "sign out", "log off", "ログアウト", "サインアウト")
    /** これらの単語は終了リンク (アカウントのキャンセル手順、ヘルプ ページ) ではありません。 */
    private val NOT_LOGOUT = listOf("说明", "帮助", "须知", "协议", "账号注销", "注销账号", "注销账户", "help", "how to", "delete account", "ログアウトの方法", "ログアウトできない", "アカウント削除", "退会", "ヘルプ")
    private val SIGN_IN = listOf("登录", "登入", "请登录", "立即登录", "登录/注册", "注册/登录", "登录 / 注册", "注册 / 登录", "log in", "login", "sign in", "signin", "sign in / register", "sign up / log in", "ログイン", "サインイン", "ログインする", "ログイン・新規登録", "新規登録・ログイン")
    private val ACCOUNT = listOf("个人中心", "我的账号", "我的账户", "账号设置", "账户设置", "my account", "your account", "account settings", "マイアカウント", "アカウント設定", "マイページ")
    /** 「終了」の単語の後には、最大でも数単語が続きます (たとえば、「ログアウト >」や「例からログアウト」はカウントされません。長すぎるものは文となります)。 */
    private const val LOGOUT_SLACK = 4

    fun classify(s: LoginSignals): LoginState {
        val texts = s.account.map(::norm)
        val logout = texts.any(::isLogout)
        val account = texts.any { t -> ACCOUNT.any { t == it || t.startsWith(it) } }
        val signIn = texts.any { it in SIGN_IN }
        // ログイン、シングル サインオン、および認証ページが頻繁にリダイレクトされます。結論は導き出されず、登録フォームは移動しません。
        if (loginUrl(s.url)) return LoginState.Unknown
        if (s.passwordVisible && !logout) return LoginState.SignedOut
        if ((logout || account) && !signIn && !s.passwordVisible) return LoginState.SignedIn
        if (signIn && !logout && !account && s.avatars == 0) return LoginState.SignedOut
        return LoginState.Unknown
    }

    private fun isLogout(t: String) =
        NOT_LOGOUT.none { t.contains(it) } &&
            (t in LOGOUT_EXACT || LOGOUT.any { w -> t == w || (t.startsWith(w) && t.length <= w.length + LOGOUT_SLACK) })

    /** アドレスは、ログイン、シングル サインオン、または認証ページです。パスによって識別されます (クエリ文字列内のログインの言及はカウントされません)。 */
    fun loginUrl(url: String?): Boolean {
        val path = url?.let { runCatching { URI(it.trim()).rawPath }.getOrNull() } ?: return false
        return LOGIN_PATH.containsMatchIn(path)
    }

    private fun norm(t: String) = t.trim().lowercase().replace(Regex("\\s+"), " ")

    /** は、[SCRIPT] (JSON 文字列リテラル) の EvaluateJavascript によって返された結果を解析します。読み取れない場合は null になります。 */
    fun parse(raw: String?): LoginSignals? {
        val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        return LoginSignals(
            url = (o["url"] as? JsonPrimitive)?.contentOrNull,
            passwordVisible = (o["pw"] as? JsonPrimitive)?.booleanOrNull == true,
            account = (o["account"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            avatars = (o["avatars"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /**
     * ページのメイン フレーム内の署名を収集する読み取り専用スクリプト。DOM の読み取りのみを行い、ページは変更せず、リクエストは送信しません。文書順に [SCAN_BUDGET] 要素まで進みます。
     * アカウント領域はヘッダー/ナビゲーション/バナー内にある、または上端がページの上部 160 ピクセル以内にあります。コメント、リスト項目、記事内のアバターはカウントされません。
     */
    val SCRIPT = """(function(){try{
var out={url:location.href,pw:false,account:[],avatars:0},budget=$SCAN_BUDGET;
function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return null;var c=getComputedStyle(e);return c.display!=='none'&&c.visibility!=='hidden'&&+c.opacity>0.05?r:null;}
function top(e,r){return r.top+scrollY<160||!!e.closest('header,nav,[role=banner],[role=navigation]');}
var w=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_ELEMENT),e,n=0;
while((e=w.nextNode())&&n++<budget){var t=e.tagName,r;
if(t==='INPUT'){var y=(e.getAttribute('type')||'').toLowerCase();if(y==='password'&&!out.pw&&vis(e))out.pw=true;if(y!=='submit'&&y!=='button')continue;}
if(t==='A'||t==='BUTTON'||t==='INPUT'||e.getAttribute('role')==='button'||e.getAttribute('role')==='link'||e.getAttribute('role')==='menuitem'){
if(out.account.length>=60)continue;var s=String(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim();
if(s&&s.length<=24&&(r=vis(e))&&top(e,r))out.account.push(s);continue;}
if(t==='IMG'&&out.avatars<3){var k=(e.className&&typeof e.className==='string'?e.className:'')+' '+(e.getAttribute('alt')||'')+' '+(e.getAttribute('src')||'');
if(/avatar|头像/i.test(k)&&(r=vis(e))&&top(e,r)&&!e.closest('article,li,[class*=comment],[class*=list],[class*=feed]'))out.avatars++;}}
return JSON.stringify(out);}catch(x){return null;}})()"""

    private const val SCAN_BUDGET = 4000
    private const val TAG = "MikaLoginProbe"
    /** の解析、判定、登録テーブルの書き込みはすべてその上に置かれており、メインスレッドを占有しません。 */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-login-probe").apply { isDaemon = true } }

    /**
     * メインスレッドの調整: [tab] の現在のページでログイン ステータスを確認し (ページ上でスクリプトが実行されます)、分析と判断はバックグラウンド スレッドで行われ、コールバック (サイトのホスト名、ステータス) もバックグラウンド スレッドで行われます。
     * タグは利用できなくなり、まだ読み込み中なので読み取ることができません。また、読み取り中に別の Web サイトに変更されてもコールバックしません。
     */
    fun probe(tab: WebTab, onResult: (String, LoginState) -> Unit) {
        if (tab.destroyed || tab.crashed || tab.loading) return
        val host = tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host) ?: return
        tab.view.evaluateJavascript(SCRIPT) { raw ->
            worker.execute {
                val signals = parse(raw) ?: return@execute
                if (signals.url?.let(WebUrlPolicy::host) != host) return@execute
                val state = classify(signals)
                Log.i(TAG, "login probe $host: $state")
                onResult(host, state)
            }
        }
    }
}
