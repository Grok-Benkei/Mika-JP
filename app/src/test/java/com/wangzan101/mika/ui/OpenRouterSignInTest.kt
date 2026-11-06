package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/**
 * OpenRouter アカウントでログインし、プロセスを実行します。ブラウザー (偽) で認証ページを開き、コードを使用してローカル コールバックに戻り、Key に変更します。
 * OpenRouter を押してデフォルトでサービスプロバイダーとして保存し、メインモデルを選択して詳細を開きます。終了し、本機のキーのみを削除してください。キーを変更するためのインターフェイスは、ローカルの偽のサービスです。
 */
class OpenRouterSignInTest {
    @Before fun freshDestination() {
        com.wangzan101.mika.core.security.ModelEndpointWatch.previousKey = null
    }

    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val listCalls = mutableListOf<Pair<String, String?>>()

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        openRouterAuth = OpenRouterAuth(http, keysUrl = server.url("/api/v1/auth/keys").toString()),
        modelLister = { _, url, apiKey, _ ->
            synchronized(listCalls) { listCalls += url to apiKey }
            ModelListResult.Ok(listOf(ListedModel("openai/gpt-x"), ListedModel("anthropic/claude-x")))
        },
    )

    /** 偽ブラウザ: 認証ページを開くときに、ユーザーが同意したコードと状態を使用してローカル コールバックを要求します。 [pages] コールバック後にブラウザに表示される結果に注目してください。 */
    private inner class Browser(private val approve: Boolean = true) : SignInBrowser {
        val opened = mutableListOf<String>()
        val pages = mutableListOf<String?>()
        override fun open(url: String) {
            opened += url
            val u = url.toHttpUrl()
            val callback = u.queryParameter("callback_url")!!.toHttpUrl().newBuilder().host("127.0.0.1")
            if (approve) callback.addQueryParameter("code", "the-code").addQueryParameter("state", u.queryParameter("state"))
            else callback.addQueryParameter("error", "access_denied")
            Thread { http.newCall(Request.Builder().url(callback.build()).build()).execute().close() }.start()
        }
        override fun returnToApp() {}
        override fun page(failure: String?): String { pages += failure; return "<p>ok</p>" }
    }

    private fun ModelSetupViewModel.signInAndWait(provider: AccountProvider) = runBlocking {
        signInWithAccount(provider)
        withTimeout(15_000) { state.first { !it.accountSignIn.running && (it.signedInProvider != null || it.accountSignIn.failure != null) } }
    }

    @Test fun signInStoresAnEncryptedKeyAndOpensTheProvider() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"sk-or-v1-SECRET"}"""))
        val s = store()
        val browser = Browser()
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)

        val p = s.openRouterAccount()!!
        assertEquals(p.id, ui.signedInProvider)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals(OpenRouterAuth.API_BASE, p.baseUrl)
        assertEquals("OpenRouter", p.name)
        assertEquals("sk-or-v1-SECRET", s.providerKey(p.id))
        assertEquals(listOf(OpenRouterAuth.API_BASE to "sk-or-v1-SECRET"), listCalls)
        assertTrue(ui.openRouterAccount!!.signedIn)
        assertNotNull("メインモデルがない場合は 1 つ選ぶ", ui.roles.main)
        assertEquals(listOf<String?>(null), browser.pages)
        assertTrue(browser.opened.single().startsWith("https://openrouter.ai/auth?"))
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent, sent.contains("\"code\":\"the-code\""))
        // キーは暗号化して保存するだけであり、構成ファイルには入力されません
        tmp.root.walk().filter(File::isFile).forEach { f -> assertFalse(f.name, f.readText().contains("SECRET")) }
        // は、再読み取り時にまだ承認済みとして認識します。
        assertEquals(p.id, store().openRouterAccount()?.id)

        vm.signedInShown()
        assertNull(vm.state.value.signedInProvider)
        runBlocking {
            vm.signOutAccount(p.id)
            withTimeout(5_000) { vm.state.first { it.openRouterAccount?.signedIn == false } }
        }
        assertNull(s.providerKey(p.id))
        assertNotNull("プロバイダーとモデルを保持する", s.provider(p.id)?.models)
        assertTrue(vm.state.value.signedOut(s.provider(p.id)!!))
    }

    @Test fun deniedAuthorizationSaysSoAndSavesNothing() {
        val s = store()
        val browser = Browser(approve = false)
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)
        assertEquals(AccountProvider.OpenRouter, ui.accountSignIn.provider)
        assertEquals("ログインがキャンセルされました。", ui.accountSignIn.failure)
        assertEquals(listOf<String?>("ログインがキャンセルされました。"), browser.pages)
        assertTrue(s.providers.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    /**
     * ユーザーが自分で OpenRouter キーを入力しました。ログインを認証し、キーを削除せずに別のキーに保存します。ログアウトし、許可されたキーのみを削除してください。
     * 次に、キーを自分で入力して「テストして保存」し、承認されたキーではなく、入力したキーのみを更新します。
     */
    @Test fun signInKeepsAKeyTheUserTypedForOpenRouter() {
        val s = store()
        val typed = s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed", emptyList())
        val signed = s.connectOpenRouter("sk-or-v1-signed", emptyList())
        assertFalse(typed.id == signed.id)
        assertEquals("sk-typed", s.providerKey(typed.id))
        assertEquals("sk-or-v1-signed", s.providerKey(signed.id))
        assertNull(typed.signIn)
        assertEquals(signed.id, s.openRouterAccount()?.id)
        // 再度ログインして承認されたものを更新します。3 番目のものはもう存在しません。
        assertEquals(signed.id, s.connectOpenRouter("sk-or-v1-again", emptyList()).id)
        assertEquals(2, s.providers.value.size)
        // もう一度入力してください キー: 自分のものを更新してください
        assertEquals(typed.id, s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed-2", emptyList()).id)
        assertEquals("sk-or-v1-again", s.providerKey(signed.id))
        s.signOutOpenRouter(signed.id)
        assertNull(s.providerKey(signed.id))
        assertEquals("sk-typed-2", s.providerKey(typed.id))
        // Keyを入力した会社はログインまたはログアウトとみなされません。
        s.signOutOpenRouter(typed.id)
        assertEquals("sk-typed-2", s.providerKey(typed.id))
        // 読み返してみると、やはり両家族は離れ離れになっていました。
        val reloaded = store()
        assertEquals(signed.id, reloaded.openRouterAccount()?.id)
        assertEquals("sk-typed-2", reloaded.providerKey(typed.id))
    }
}
