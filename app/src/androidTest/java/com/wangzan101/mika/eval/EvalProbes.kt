package com.wangzan101.mika.eval

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.eval.APP_CAPABILITIES
import com.wangzan101.mika.core.eval.AppCapabilities
import com.wangzan101.mika.core.eval.PROBE_ALLOWLIST
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.cap.web.PageScript
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 評価者自身のデバイスの読み取り専用ビュー: ホワイトリスト プローブ、前提条件、およびタスク完了後に仮想画面上でアプリを開いて単語を検索します。
 * [sh] はコマンドをシェルとして実行します。 [loggedIn] は、手動で「ログイン」とマークされたパッケージ名リスト ファイルです (1 行に 1 つのパッケージ名)。
 */
class EvalProbes(private val sh: (String) -> String, private val loggedIn: File) {

    /** は [PROBE_ALLOWLIST] のコマンドのみを実行します。タスク ファイルにコマンドを書き込むことはできません。 */
    fun run(name: String): String {
        val cmd = PROBE_ALLOWLIST[name] ?: error("probe not in allowlist: $name")
        return sh(cmd).trim()
    }

    /**
     * この携帯電話で特定のタイプのものを処理できるアプリ ([APP_CAPABILITIES] を参照): 標準インテントによってチェックされ、デフォルトでそれを処理するアプリが最初にランク付けされます。
     * コマンドは機能テーブルからのみ記述されており、タスク ファイルに書き込むことはできません。標準インテントには null を返す機能はありません。
     */
    fun appCandidates(capability: String): List<String> = APP_CAPABILITIES[capability].orEmpty()
        .flatMap { i -> AppCapabilities.commands(i).flatMap { cmd -> AppCapabilities.packagesIn(runCatching { sh(cmd) }.getOrDefault("")) } }
        .distinct()

    /**
     * `installed:<パッケージ名>`、`app:<機能>`、`signedIn:<パッケージ名またはサイト>` (旧表記 `login:<パッケージ名>`、`site:<ホスト名>`)。
     * ログイン ステータス: ウェブサイトは、サイト登録フォームの「ユーザーがミカにログインしている」ことのみを認識します (「ウェブサイト ログイン」設定でログインしています)。アプリは手動タグ ([loggedIn]) のみを認識します。
     * エバリュエーターは、ユーザーのアプリのログイン ステータスを判断しません (すべてのアプリに対して信頼できる検出方法はありません)。ログインしていない質問はスキップされ、その理由は「ログインしていません」と表示されます。
     */
    fun requirementMet(req: String): Pair<Boolean, String> {
        val kind = req.substringBefore(':')
        val pkg = req.substringAfter(':', "")
        when (kind) {
            "site" -> return siteSignedIn(pkg)?.let { if (it) true to "" else false to "未ログイン: $pkg は設定の「ウェブサイトのログイン」でログインしていません" }
                ?: (false to "前提条件の形式が不正です: $req")
            "signedIn" -> return if (siteSignedIn(pkg) == true || appMarked(pkg)) true to ""
                else false to "未ログイン: $pkg（ウェブサイトは「ウェブサイトのログイン」でログインし、アプリは ${loggedIn.name} に記録してください）"
            "app" -> return if (appCandidates(pkg).isNotEmpty()) true to "" else false to "この端末には「$pkg」を処理できるアプリがありません"
        }
        if (!PACKAGE.matches(pkg)) return false to "前提条件の形式が不正です: $req"
        return when (kind) {
            "installed" ->
                if (sh("pm path $pkg").contains("package:")) true to "" else false to "$pkg がインストールされていません"
            "login" -> if (appMarked(pkg)) true to "" else false to "未ログイン: $pkg はログイン済みとして記録されていません（${loggedIn.name}）"
            else -> false to "未対応の前提条件です: $req"
        }
    }

    /** ログイン済みとして手動でマークされたアプリ (1 行に 1 つのパッケージ名)。 */
    private fun appMarked(pkg: String) = runCatching { loggedIn.readLines().map { it.trim() } }.getOrDefault(emptyList()).contains(pkg)

    /** Web サイトは、「Web サイト ログイン」設定でログインしています。同じ登録可能なドメイン名がカウントされます (登録可能なドメイン名が認識できない場合は、ホスト名がまったく同じである必要があります)。書き込みメソッドが Web サイトでない場合は、null になります。 */
    private fun siteSignedIn(site: String): Boolean? {
        val host = WebUrlPolicy.host("https://$site") ?: return null
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val want = WebUrlPolicy.registrable(host) ?: host
        return WebSites.registry(ctx).list().any { it.loggedInByUser && (WebUrlPolicy.registrable(it.host) ?: it.host) == want }
    }

    /** 新しい仮想画面で [pkg] をコールド ブートし (またはそれを使用してディープ リンクを開き)、[text] が表示されるかタイムアウトになるまで画面を読み取ります。 */
    suspend fun screenHas(pkg: String, text: String, deepLink: String?): Boolean {
        require(PACKAGE.matches(pkg)) { "bad package: $pkg" }
        val shell = DeviceSupport.shell()
        val metrics = DisplayMetrics().also {
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val display = AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display))
            if (deepLink == null) {
                if (screen.openApp(pkg, cold = true) !is ActionResult.Ok) return false
            } else {
                // リンクはデータを am に送信するだけであり、引用符を使用して他のコマンドを記述することはできません。
                require('\'' !in deepLink && '\n' !in deepLink) { "bad deep link" }
                sh("am start --display ${display.displayId} -a android.intent.action.VIEW -d '$deepLink' -p $pkg")
            }
            val want = text.replace(WS, "")
            val end = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (true) {
                val seen = screen.snapshot().elements.joinToString("") { it.name }.replace(WS, "")
                if (want in seen) return true
                if (System.currentTimeMillis() > end) return false
                delay(500)
            }
        } finally {
            display.release()
        }
    }

    /**
     * 新しいバックグラウンド Web ページ タブで [url] を開き、テキストのページ全体 ([WEB_PAGES] 段落まで) を読み、[text] を見つけます (空白は無視します)。
     * は mika Web ページを使用して設定されており、ログイン ステータスはエージェントが使用するものと同じです。
     */
    suspend fun webHas(url: String, text: String): Boolean {
        require(WebUrlPolicy.checkNavigable(url) == null) { "bad url: $url" }
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val metrics = DisplayMetrics().also {
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        lateinit var host: WebHost
        lateinit var tab: WebTab
        withContext(Dispatchers.Main) {
            host = WebHost(ctx, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            tab = WebTab(ctx, "probe", "eval")
            host.attach(tab)
        }
        try {
            tab.load(url)
            val page = PageScript(tab)
            page.install()
            val want = text.replace(WS, "")
            var offset = 0
            repeat(WEB_PAGES) {
                val o = runCatching { Json.parseToJsonElement(page.readPage(30_000, offset)).jsonObject }.getOrNull() ?: return false
                val got = (o["text"] as? JsonPrimitive)?.content.orEmpty().replace(WS, "")
                if (want in got) return true
                offset = (o["nextOffset"] as? JsonPrimitive)?.intOrNull ?: return false
            }
            return false
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                tab.destroy()
                host.close()
            }
        }
    }

    private companion object {
        const val WEB_PAGES = 5
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        val WS = Regex("\\s+")
        const val SCREEN_WAIT_MS = 10_000L
    }
}
