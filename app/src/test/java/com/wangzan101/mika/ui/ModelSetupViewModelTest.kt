package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ClientSetup
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.core.net.CleartextPolicy
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModelTest {
    @Before fun freshDestination() {
        com.wangzan101.mika.core.security.ModelEndpointWatch.previousKey = null
    }

    @After fun lanCleartextOff() = CleartextPolicy.setAllowLanHttp(false)

    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    /** は、アドレスごとの列モデルの結果を示します。書き込まれていないアドレスについては、空のテーブルがリストされます。 */
    private val listing = mutableMapOf<String, ModelListResult>()
    private val listCalls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
    private var ids = 0

    private fun store(dir: java.io.File = tmp.root) = ProviderStore(
        dir, AesGcmKeyCipher { key },
        newId = { "id${++ids}" },
        userAgent = "Mika/1.2.3",
        modelFactory = { cfg, _, _, setup -> built += cfg.model to setup; Fake(cfg.capabilities) { reply() } },
        modelLister = { kind, url, apiKey, _ -> listCalls += Triple(kind, url, apiKey); listing[url] ?: ModelListResult.Ok(emptyList()) },
    )

    /** 構築されたモデル (モデル名、リクエストヘッダーなど)。 「テストして保存」のトライアル呼び出しで返される内容は [reply] によって決まり、デフォルトでトライアル ツールが呼び出されます。 */
    private val built = mutableListOf<Pair<String, ClientSetup>>()
    private var reply: suspend () -> ModelReply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
    private val probes = mutableListOf<Triple<String, List<Message>, List<ToolSpec>>>()

    private inner class Fake(override val capabilities: Capabilities, private val answer: suspend () -> ModelReply) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            probes += Triple(system, messages, tools)
            return answer()
        }
    }

    // 互換APIのフィクスチャはDeepSeekを明示する。日本語版の初期選択は下の専用テストで確認する。
    private fun TestScope.vm(s: ProviderStore = store()) =
        ModelSetupViewModel(s, backgroundScope, io = StandardTestDispatcher(testScheduler)).apply { selectPreset("DeepSeek") }

    @Test fun japaneseForkStartsWithTheInternationalProvider() = runTest {
        val vm = ModelSetupViewModel(store(), backgroundScope, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals("OpenAI", vm.state.value.form.preset)
        assertEquals(ProviderConfig.Kind.OpenAiResponses, vm.state.value.form.kind)
        assertEquals("https://api.openai.com/v1", vm.state.value.form.baseUrl)
        assertTrue(listCalls.isEmpty())
    }

    @Test fun relaySetupProbesOnlyTheDefaultAllowedModel() = runTest {
        val url = "https://openrouter.ai/api/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("deepseek/deepseek-v4", toolCalls = true),
            ListedModel("qwen/qwen3-max", toolCalls = true),
            ListedModel("openai/gpt-6.1-sol", toolCalls = true),
        ))
        val store = store()
        val vm = vm(store)
        vm.selectPreset("OpenRouter")
        vm.setApiKey("test-key")
        vm.testAndSave()
        runCurrent()
        assertEquals(listOf("openai/gpt-6.1-sol"), built.map { it.first })
        assertEquals(listOf("openai/gpt-6.1-sol"), store.list().map { it.model })
        assertEquals(store.list().single().id, store.roles.value.main)
    }

    @Test fun relayWithOnlyChineseModelsMakesNoAutomaticProbeOrAssignment() = runTest {
        val url = "https://openrouter.ai/api/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("deepseek-v4"), ListedModel("qwen3-max")))
        val store = store()
        val vm = vm(store)
        vm.selectPreset("OpenRouter")
        vm.setApiKey("test-key")
        vm.testAndSave()
        runCurrent()
        assertTrue(built.isEmpty())
        assertTrue(probes.isEmpty())
        assertNull(store.roles.value.main)
        assertEquals(listOf("deepseek-v4", "qwen3-max"), store.providers.value.single().models!!.map { it.id })
        assertEquals(store.providers.value.single().id, vm.state.value.pickToProbe)
        assertTrue(vm.state.value.probeNote.orEmpty(), "自動選択できるモデル候補がありません" in vm.state.value.probeNote.orEmpty())
        assertTrue(vm.state.value.probeNote.orEmpty(), "手動で選択" in vm.state.value.probeNote.orEmpty())
    }

    @Test fun filteringChineseCandidatesDoesNotProbeAnEmbeddingFallback() = runTest {
        val url = "https://openrouter.ai/api/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("deepseek-v4", toolCalls = true),
            ListedModel("text-embedding-v4"),
            ListedModel("not-chat", toolCalls = true, chat = false),
            ListedModel("chat-without-tools", toolCalls = false, chat = true),
        ))
        val store = store()
        val vm = vm(store)
        vm.selectPreset("OpenRouter")
        vm.setApiKey("test-key")
        vm.testAndSave()
        runCurrent()
        assertTrue(built.isEmpty())
        assertTrue(probes.isEmpty())
        assertNull(store.roles.value.main)
        assertEquals(4, store.providers.value.single().models!!.size)
    }

    @Test fun explicitlySelectedChineseGatewayDoesNotRequireACompanyFamilyToken() = runTest {
        val preset = ProviderPresets.all.single { it.label == "硅基流动" }
        assertTrue(preset.familyTokens.isEmpty())
        listing[preset.baseUrl] = ModelListResult.Ok(listOf(ListedModel("Qwen/Qwen3.5", toolCalls = true)))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(preset.label)
        vm.setApiKey("test-key")
        vm.testAndSave()
        runCurrent()
        assertEquals(listOf("Qwen/Qwen3.5"), built.map { it.first })
        assertEquals("Qwen/Qwen3.5", store.configFor(ModelRole.Main)?.model)
    }

    @Test fun existingChineseGatewayCanBeExplicitlyUsedAsMain() = runTest {
        val preset = ProviderPresets.all.single { it.label == "硅基流动" }
        val store = store()
        val provider = store.connect("SiliconFlow", preset.kind, preset.baseUrl, "test-key", listOf(ListedModel("deepseek-v4", toolCalls = true)))
        val vm = vm(store)
        runCurrent()
        assertNull(store.roles.value.main)
        vm.useAsMain(provider.id)
        runCurrent()
        assertEquals("deepseek-v4", store.configFor(ModelRole.Main)?.model)
    }

    @Test fun explicitLocalModelServicesCanAutomaticallySelectQwen() = runTest {
        CleartextPolicy.setAllowLanHttp(true)
        val local = listOf("Ollama" to "http://127.0.0.1:11434/v1", "LM Studio" to "http://192.168.1.20:1234/v1")
        for ((label, url) in local) {
            listing[url] = ModelListResult.Ok(listOf(ListedModel("qwen3.5", toolCalls = true)))
            val store = store()
            val vm = vm(store)
            vm.selectPreset(label)
            vm.setBaseUrl(url)
            vm.setApiKey("test-key")
            vm.testAndSave()
            runCurrent()
            assertEquals(label, "qwen3.5", store.configFor(ModelRole.Main)?.model)
        }
        CleartextPolicy.setAllowLanHttp(false)
    }

    @Test fun existingLocalModelServiceCanBeExplicitlyUsedAsMain() = runTest {
        CleartextPolicy.setAllowLanHttp(true)
        val store = store()
        val provider = store.connect("Local models", ProviderConfig.Kind.OpenAiCompat, "http://models.local:11434/v1", "test-key", listOf(ListedModel("qwen3.5", toolCalls = true)))
        val vm = vm(store)
        runCurrent()
        assertNull(store.roles.value.main)
        vm.useAsMain(provider.id)
        runCurrent()
        assertEquals("qwen3.5", store.configFor(ModelRole.Main)?.model)
    }

    @Test fun selfHostedIntentSurvivesReuseOnTailscaleCgnatAndCustomDomains() = runTest {
        val connections = listOf(
            "Ollama" to "https://100.100.100.100:11434/v1",
            "LM Studio" to "https://models.my-tailnet.ts.net/v1",
            "Ollama" to "https://owned-model.example.com/v1",
        )
        for ((label, url) in connections) {
            com.wangzan101.mika.core.security.ModelEndpointWatch.previousKey = null
            val dir = tmp.newFolder()
            val saved = store(dir)
            listing[url] = ModelListResult.Ok(listOf(ListedModel("qwen3.5", toolCalls = true)))
            val setup = vm(saved)
            setup.selectPreset(label)
            setup.setBaseUrl(url)
            setup.setApiKey("test-key")
            setup.testAndSave()
            runCurrent()
            val p = saved.providers.value.single()
            assertTrue(label, p.explicitSelfHosted)
            assertEquals(p.id, saved.configFor(ModelRole.Main)?.providerId)
            val reloaded = store(dir)
            com.wangzan101.mika.core.security.ModelEndpointWatch.previousKey = null
            val other = reloaded.connect("Other", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "test-key", listOf(ListedModel("chat-model")))
            reloaded.use(ModelRole.Main, other.id, "chat-model", ProviderPresets.custom.capabilities)
            val reused = vm(reloaded)
            runCurrent()
            reused.useAsMain(p.id)
            runCurrent()
            assertEquals(label, p.id, reloaded.configFor(ModelRole.Main)?.providerId)
            assertEquals(label, "qwen3.5", reloaded.configFor(ModelRole.Main)?.model)
        }
    }

    @Test fun useAsMainWithNoAutomaticCandidateShowsTheManualResolution() = runTest {
        val store = store()
        val p = store.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, "https://openrouter.ai/api/v1", "test-key", listOf(ListedModel("bytedance/ui-tars-1.5-7b", toolCalls = true)))
        val vm = vm(store)
        runCurrent()
        vm.useAsMain(p.id)
        runCurrent()
        assertNull(store.roles.value.main)
        assertTrue(built.isEmpty())
        assertEquals(p.id, vm.state.value.pickToProbe)
        assertTrue(vm.state.value.probeNote.orEmpty(), "自動選択できるモデル候補がありません" in vm.state.value.probeNote.orEmpty())
        assertTrue(vm.state.value.probeNote.orEmpty(), "手動で選択" in vm.state.value.probeNote.orEmpty())
    }

    private val deepseekUrl = "https://api.deepseek.com"
    private val qwenUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val deepseek = ModelListResult.Ok(
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder", toolCalls = false)),
    )

    @Test fun testAndSaveStoresProviderAndPicksFirstModelAsMain() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        runCurrent()
        assertTrue("プロバイダーがない場合はフォームを展開する", vm.state.value.showForm)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.canNext)
        vm.setApiKey("sk-1")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave()
        assertEquals(ListState.Running, vm.state.value.form.test)
        runCurrent()
        assertEquals(Triple(ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk-1"), listCalls.single())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals(3, p.models?.size)
        assertEquals("sk-1", store.providerKey(p.id))
        // は最初のモデルをメイン モデルとして自動的に選択します (この構成のみが保存されます)
        assertEquals(listOf("deepseek-chat"), store.list().map { it.model })
        assertEquals(store.list().single().id, store.roles.value.main)
        val s = vm.state.value
        assertEquals(listOf(p), s.providers)
        assertFalse("保存後はフォームを閉じる", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue(s.canNext)
    }

    @Test fun failedTestShowsReasonAndSavesNothing() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store)
        vm.setApiKey("bad"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("good")
        assertEquals(ListState.Idle, vm.state.value.form.test)
        assertEquals("good", vm.state.value.form.apiKey)
    }

    /** モデルをリストした後、メイン モデルになるモデルでツールを 1 回呼び出します。パラメーターなしでツールを呼び出し、応答の上限は 1 回の呼び出しに十分であり、クライアント ID を指定します。成功した場合のみ保存します。 */
    @Test fun testAndSaveSendsOneTinyRequestToTheMainCandidate() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val (model, setup) = built.first()
        assertEquals("deepseek-chat", model)
        assertEquals(1024, setup.maxOutputTokens)
        assertEquals("Mika/1.2.3", setup.headers["User-Agent"])
        assertEquals("DeepSeek はセッションヘッダーを宣言していない", setOf("User-Agent"), setup.headers.keys)
        val (_, messages, tools) = probes.single()
        assertEquals(1, messages.size)
        assertEquals(listOf("get_time"), tools.map { it.name })
        assertEquals(1, store.providers.value.size)
        assertNull(vm.state.value.probeNote)
    }

    /** インターフェイスは機能し、モデルは次の応答のみを返します。次に、メイン モデルとして使用できる次のモデルを試してください (ツールを呼び出さないと言われる場合は、試行しないでください)。応答のみの場合は、サービスプロバイダーと無期限のメインモデルが保存され、ユーザーが選択するように求められます。選択後、リマインダーは閉じられます。 */
    @Test fun modelThatOnlyAnswersTextIsSavedButNotMadeMain() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { ModelReply(listOf(Part.Text("It is noon.")), StopReason.EndTurn, null) }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals(3, p.models?.size)
        assertNull("メインモデルを自動選択しない", store.roles.value.main)
        assertTrue(store.list().isEmpty())
        val s = vm.state.value
        assertFalse("保存した後、フォームを閉じる", s.showForm)
        assertEquals(ListState.Idle, s.form.test)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), built.map { it.first })
        assertEquals(
            "プロバイダーには接続できましたが、「deepseek-chat」はテストに通りませんでした: ツールを呼び出さず、Mika にはツールを呼び出せるモデルが必要です。一覧から、アカウントで有効になっているモデルを選んでください。\n" +
                "deepseek-chat、deepseek-reasoner を試しましたが、どれもテストに通りませんでした。",
            s.probeNote,
        )
        assertEquals(p.id, s.pickToProbe)
        assertTrue(s.probeBeforePick(ModelRole.Main, p.id))
        assertFalse(s.probeBeforePick(ModelRole.Vision, p.id))
        assertFalse(s.canNext)
        vm.pick(ModelRole.Main, p.id, "deepseek-reasoner"); runCurrent()
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
        assertEquals("deepseek-reasoner", store.configFor(ModelRole.Main)?.model)
    }

    /** Tongyi Qianwen のキーの下では、他のモデルが最初にリストされます。最初に自分の (qwen) を試してください。最初のものが失敗した場合は、次のものを試してください。合格したものがマスター モデルとなり、最初に試したどれが失敗したかを伝えます。 */
    @Test fun notActivatedModelFallsBackToTheNextCandidate() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true), ListedModel("text-embedding-v4"),
            ListedModel("qwen3-max-preview"), ListedModel("qwen-plus"),
        ))
        reply = {
            if (built.last().first != "qwen-plus") throw ModelError.ModelNotAvailable(built.last().first, "The product is not activated.")
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("自社モデルを先に試し、ベクトルモデルは試さない", listOf("qwen3-max-preview", "qwen-plus"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals("qwen-plus", store.configFor(ModelRole.Main)?.model)
        assertTrue("qwen-plus" in p.verifiedModels)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.pickToProbe)
        assertEquals(
            "最初に試した qwen3-max-preview はテストに通りませんでした: このアカウントではこのモデルが有効になっていないか、アクセス権がありません(プロバイダーからの応答: The product is not activated.)。メインモデルは、テストに通った「qwen-plus」です。",
            s.probeNote,
        )
    }

    /**
     * アグリゲーション サービス プロバイダー (リスト内の多数のモデル): 先頭のモデルは下流 (502) ですが、次のモデルが使用できます。
     * 5xx は、家族全員が利用できないものとみなされるべきではありません。モデルが失敗した場合、モデルは通常どおり保存され、合格したモデルがメイン モデルとして使用されます。
     */
    @Test fun serverErrorOnTheFirstCandidateFallsBackToTheNext() = runTest {
        val url = "https://relay.test/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("vendor/first-chat"), ListedModel("vendor/second-chat"),
        ))
        reply = {
            if (built.last().first == "vendor/first-chat") throw ModelError.Server(502)
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("vendor/first-chat", "vendor/second-chat"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals("vendor/second-chat", store.configFor(ModelRole.Main)?.model)
        assertTrue("vendor/second-chat" in p.verifiedModels)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.pickToProbe)
        assertEquals(
            "最初に試した vendor/first-chat はテストに通りませんでした: サーバーがエラーを返しました(HTTP 502)。" +
                "メインモデルは、テストに通った「vendor/second-chat」です。",
            s.probeNote,
        )
    }

    /**
     * リストは、特定のモデルが現在のインターフェイス タイプを使用していないことを示します (たとえば、集約サービスは、/messages のみをサポートするモデルを最初にランク付けします)。それは試行せず、次のタイプを試してください。
     * 試しても必ず失敗し、失敗のたびに PROBE_TIMEOUT_MS いっぱい待つことになる。
     */
    @Test fun modelOnAnotherProtocolIsSkippedWithoutProbing() = runTest {
        val url = "https://api.commandcode.ai/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("claude-sonnet-5-5", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("openai/gpt-test", protocols = listOf(ModelProtocol.ChatCompletions, ModelProtocol.Responses)),
        ))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("openai/gpt-test"), built.map { it.first })
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.probeNote)
        assertEquals("openai/gpt-test", store.configFor(ModelRole.Main)?.model)
    }

    /**
     * 一覧が特定のモデルに個別のエンドポイントを指定している場合（[ListedModel.endpoint] 参照）: 呼び出しはそのモデル自身が宣言したプロトコルで行き、ここで選んだインターフェース種別とは無関係であり、
     * `supported_endpoints` に現在のインターフェース種別が含まれていないことを理由に除外してはいけない。
     */
    @Test fun modelWithItsOwnEndpointIsProbedWhateverTheKindIs() = runTest {
        val url = "https://api.commandcode.ai/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel(
                "claude-sonnet-5-5",
                endpoint = ModelEndpoint(ModelProtocol.Messages, "https://api.commandcode.ai/provider/v1"),
                protocols = listOf(ModelProtocol.Messages),
            ),
        ))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("claude-sonnet-5-5"), built.map { it.first })
        assertEquals("claude-sonnet-5-5", store.configFor(ModelRole.Main)?.model)
    }

    /** 一覧のモデルがすべて現在のインターフェース種別を使わないと明記している場合: どれも試さず、従来どおりプロバイダーを保存し、理由を明示する。 */
    @Test fun allModelsOnAnotherProtocolSaveWithAReason() = runTest {
        val url = "https://api.commandcode.ai/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("claude-sonnet-5-5", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("claude-sonnet-5", protocols = listOf(ModelProtocol.Messages)),
        ))
        reply = { throw AssertionError("呼び出してはいけない: これらのモデルは OpenAI 互換 API を使わない") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertTrue("呼び出してはいけない", built.isEmpty())
        val p = store.providers.value.single()
        val s = vm.state.value
        assertFalse(s.showForm)
        // はどれも合格しませんでした。サービス プロバイダーは依然としてそれを保存し、理由を説明し、ユーザーにリストから選択するよう求めました。
        // これら 2 つのモデルは一度も試していないため、「X、Y を試しました」という行は表示されません。
        // 理由は、インターフェイスの種類がそれらに到達できないためです。最後の文では、人々がそれを「アクティブ化」することを許可できません（それはここでは問題ではありません）。インターフェイス タイプを変更するか、このインターフェイス タイプを変更することで到達できるモデルを変更する必要があります。
        assertEquals(p.id, s.pickToProbe)
        assertEquals(
            "プロバイダーには接続できましたが、「claude-sonnet-5-5」はテストに通りませんでした: 選んだインターフェースの種類では提供されていないため、リクエストが届きません。" +
                "このインターフェースの種類で届くモデルに変えるか、インターフェースの種類を変えてください。",
            s.probeNote,
        )
    }

    /** は上記と同じですが、モデルが 1 つだけ、インターフェイスの別のセットのみが含まれています。「インターフェイス タイプの変更」という同じ文には「試した」とは書かれておらず、誰もアクティブ化できません。 */
    @Test fun oneModelOnAnotherProtocolSaysToChangeTheInterfaceType() = runTest {
        val url = "https://api.commandcode.ai/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("claude-sonnet-5-5", protocols = listOf(ModelProtocol.Messages))))
        reply = { throw AssertionError("呼び出してはいけない: このモデルは OpenAI 互換 API を使わない") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertTrue("呼び出してはいけない", built.isEmpty())
        assertEquals(
            "プロバイダーには接続できましたが、「claude-sonnet-5-5」はテストに通りませんでした: 選んだインターフェースの種類では提供されていないため、リクエストが届きません。" +
                "このインターフェースの種類で届くモデルに変えるか、インターフェースの種類を変えてください。",
            vm.state.value.probeNote,
        )
    }

    /**
     * は `supported_endpoints` としてリストされておらず、最上位モデルは別のインターフェイス セットのみを使用します。サービス プロバイダーは 400 unsupported_model を返します。アダプターを介して同じ一連のエラーをマッピングし、
     * これはどのモデルに関する問題であり、次のモデルを試して、合格したモデルがマスター モデルとなり、プロンプトにはサービス プロバイダーのオリジナルの言葉が含まれます。
     */
    @Test fun endpointMismatchOnTheFirstCandidateFallsBackToTheNext() = runTest {
        val url = "https://relay.example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("vendor/shape-a"), ListedModel("vendor/shape-b")))
        val said = "Model 'vendor/shape-a' must be called via /provider/v1/messages."
        reply = {
            val m = built.last().first
            if (m == "vendor/shape-a") {
                throw ModelHttp.openAiError(400, null, """{"error":{"message":"$said","param":"model","code":"unsupported_model"}}""", m)
            }
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("vendor/shape-a", "vendor/shape-b"), built.map { it.first })
        assertEquals("vendor/shape-b", store.configFor(ModelRole.Main)?.model)
        assertEquals(
            "最初に試した vendor/shape-a はテストに通りませんでした: このアカウントではこのモデルが有効になっていないか、アクセス権がありません(プロバイダーからの応答: Model 'vendor/shape-a' must be called via /provider/v1/messages.)。" +
                "メインモデルは、テストに通った「vendor/shape-b」です。",
            vm.state.value.probeNote,
        )
    }

    /** 2 つのことを同時に実行します。まず、現在のインターフェイス タイプを使用しないものを除外し、試行しません。最初に残ったインターフェイスは間違ったインターフェイスで 400 を返し、次のインターフェイスを試してください。 */
    @Test fun listedProtocolsFilterFirstThenEndpointMismatchFallsThrough() = runTest {
        val url = "https://relay.example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("vendor/messages-only", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("vendor/unknown"),
            ListedModel("vendor/chat", protocols = listOf(ModelProtocol.ChatCompletions)),
        ))
        reply = {
            val m = built.last().first
            if (m == "vendor/unknown") throw ModelHttp.openAiError(400, null, """{"error":{"code":"unsupported_model","param":"model"}}""", m)
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("/messages 専用と明記されたモデルは試さない", listOf("vendor/unknown", "vendor/chat"), built.map { it.first })
        assertEquals("vendor/chat", store.configFor(ModelRole.Main)?.model)
    }

    /**
     * キーの問題は、「このモデルはこのインターフェイスを使用できない」と見なすことはできません。401 または 403 は、キーがこのインターフェイスを使用できないことを意味します。
     * 最初の候補は保存全体をブロックし、本当の理由を示し、何も保存しません。
     */
    @Test fun keyErrorsThatMentionTheEndpointStillBlockTheSave() = runTest {
        val url = "https://relay.example.com/provider/v1"
        for ((status, body) in listOf(
            401 to """{"error":{"message":"Invalid API key","code":"unsupported_model"}}""",
            403 to """{"error":{"message":"Your API key is not supported for this endpoint"}}""",
        )) {
            listing[url] = ModelListResult.Ok(listOf(ListedModel("vendor/a"), ListedModel("vendor/b")))
            built.clear()
            reply = { throw ModelHttp.openAiError(status, null, body, built.last().first) }
            val store = store()
            val vm = vm(store)
            vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
            assertEquals("HTTP $status: 最初のモデルだけを試す", listOf("vendor/a"), built.map { it.first })
            val failed = vm.state.value.form.test as ListState.Failed
            assertTrue(failed.message, failed.message.contains("APIキーが無効"))
            assertTrue(store.providers.value.isEmpty())
            assertNull(vm.state.value.pickToProbe)
        }
    }

    /** 私が試したものはすべて 5xx でした。ほとんどが利用できず (アドレスは正しいが、サービスが稼働していません)、まだ存在しません。 */
    @Test fun serverErrorOnEveryCandidateSavesNothing() = runTest {
        val url = "https://api.qnaigc.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("a-model"), ListedModel("b-model"), ListedModel("c-model")))
        reply = { throw ModelError.Server(502) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("試すのは最大 3 件", listOf("a-model", "b-model", "c-model"), built.map { it.first })
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("HTTP 502"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertNull(store.roles.value.main)
    }

    /** 最初に試したのは 5xx でしたが、次に試したのは Key でした。間違い: 以前の 5xx では、キーが使用できることが証明されておらず、キーはまだ存在していません。 */
    @Test fun serverErrorThenAuthErrorSavesNothing() = runTest {
        val url = "https://api.qnaigc.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("a-model"), ListedModel("b-model")))
        reply = {
            if (built.last().first == "a-model") throw ModelError.Server(502)
            throw ModelError.Auth()
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("a-model", "b-model"), built.map { it.first })
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("APIキーが無効"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    /** いくつか試したものはどれもアクティブ化されませんでした。キーは正しいですが、サービス プロバイダーはまだ保存されています。メイン モデルは不明です。どれが試されたのか教えてください。リストから選択してください。選択するときは、まず電話してみてください。失敗した場合は選択しないでください。 */
    @Test fun allCandidatesNotActivatedSavesProviderAndAsksToPick() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true), ListedModel("qwen-plus"), ListedModel("qwen-max"), ListedModel("qwen-turbo"),
        ))
        reply = { throw ModelError.ModelNotAvailable(built.last().first, "The product is not activated.") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("試すのは最大 3 件", listOf("qwen-plus", "qwen-max", "qwen-turbo"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(4, p.models?.size)
        assertEquals("sk", store.providerKey(p.id))
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertEquals(ListState.Idle, s.form.test)
        assertEquals(
            "プロバイダーには接続できましたが、「qwen-plus」はテストに通りませんでした: このアカウントではこのモデルが有効になっていないか、アクセス権がありません(プロバイダーからの応答: The product is not activated.)。一覧から、アカウントで有効になっているモデルを選んでください。\n" +
                "qwen-plus、qwen-max、qwen-turbo を試しましたが、どれも有効になっていません。",
            s.probeNote,
        )
        assertEquals(p.id, s.pickToProbe)
        // ユーザーが選択したものです。最初に呼び出してみて、アクティブになっていない場合は理由を伝え、選択しません。
        var picked = 0
        vm.pickManual(ModelRole.Main, p.id, "antdigital/Ling-3.1-flash") { picked++ }; runCurrent()
        assertEquals(
            ListState.Failed("このアカウントでは「antdigital/Ling-3.1-flash」が有効になっていないか、アクセス権がありません(プロバイダーからの応答: The product is not activated.)。別のモデルを選ぶか、プロバイダーのコンソールで有効にしてください。"),
            vm.state.value.manualPick[p.id],
        )
        assertEquals(0, picked)
        assertNull(store.roles.value.main)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickManual(ModelRole.Main, p.id, "qwen-turbo") { picked++ }; runCurrent()
        assertEquals(1, picked)
        assertEquals("qwen-turbo", store.configFor(ModelRole.Main)?.model)
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
    }

    /** 1 つ目は、キーが間違っている (またはモデル以外の問題がある) ことです。他に何も試さなければ、保存されません。 */
    @Test fun authErrorOnTheFirstCandidateSavesNothing() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-plus"), ListedModel("qwen-max")))
        reply = { throw ModelError.Auth() }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("qwen-plus"), built.map { it.first })
        assertTrue(vm.state.value.form.test is ListState.Failed)
        assertTrue(store.providers.value.isEmpty())
        assertNull(vm.state.value.pickToProbe)
    }

    /** トライアルコールが失敗しました (サービスプロバイダーが要求するリクエストヘッダーが含まれていないなど): サーバーから報告されたエラーがそのまま他の人に表示され、何も保存されません。 */
    @Test fun failedTinyRequestShowsTheServerErrorAndSavesNothing() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { throw ModelError.BadResponse("HTTP 400 {\"error\":{\"type\":\"MissingSessionID\"}}") }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("MissingSessionID"))
        assertTrue(failed.message.contains("deepseek-chat"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertTrue(vm.state.value.showForm)
    }

    @Test fun tinyRequestThatNeverAnswersTimesOut() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { awaitCancellation() }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Running, vm.state.value.form.test)
        advanceTimeBy(ModelSetupViewModel.PROBE_TIMEOUT_MS + 1); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("20"))
        assertTrue(store.providers.value.isEmpty())
    }

    /** はデフォルトでセッション ヘッダーを宣言します (OpenCode Go)。呼び出し時にそれを持ってくると、保存されたサービス プロバイダーもそれを記憶します。 */
    @Test fun presetSessionHeaderIsUsedForTheTestAndRemembered() = runTest {
        val go = ProviderPresets.all.first { it.sessionHeader != null }
        listing[go.baseUrl] = ModelListResult.Ok(listOf(ListedModel("chat-model", toolCalls = true)))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(go.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        val setup = built.first().second
        assertTrue(setup.headers[go.sessionHeader!!].orEmpty().isNotEmpty())
        assertEquals(go.sessionHeader, store.providers.value.single().sessionHeader)
    }

    /** モデルがリストされていません。最初に保存したり試したりせず、モデル名を尋ねてください。入力してからテストし、それを使用して呼び出しを試みます。リストを提供していないサービスプロバイダーとして保存されている場合、それがメインモデルとなり、検証済みとして記録されます。 */
    @Test fun noListAsksForAModelAndProbesIt() = runTest {
        CleartextPolicy.setAllowLanHttp(true)
        val url = "http://192.168.1.5:8000/v1"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.testAndSave(); runCurrent()
        assertTrue(probes.isEmpty())
        assertTrue("保存していない", store.providers.value.isEmpty())
        val f = vm.state.value.form
        assertTrue(f.noList)
        assertEquals(ListState.Idle, f.test)
        assertFalse("モデル名が未入力ならテストできない", f.canTest)
        vm.setApiKey("k")
        assertTrue("Key を変更してもモデル名の入力欄を閉じない", vm.state.value.form.noList)
        vm.setModel(" qwen3 ")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("qwen3", built.single().first)
        val p = store.providers.value.single()
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(setOf("qwen3"), p.verifiedModels)
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        assertEquals(emptyList<String>(), vm.state.value.unverified(p))
    }

    /** にはモデルがリストされておらず、手入力されたモデルは依然として、モデルを呼び出そうとしたときに「見つからない: 保存されていません」と表示されます。どのモデル、アドレス、インターフェイスのタイプが間違っているのかを明確に説明し、例を使用して一致するアドレスを持つ会社を見つけてください。 */
    @Test fun noListAndModelNotFoundSavesNothing() = runTest {
        val url = "https://generativelanguage.googleapis.com"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        reply = { throw ModelError.ModelNotFound("gemini") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
        vm.testAndSave(); runCurrent()
        vm.setModel("gemini"); vm.testAndSave(); runCurrent()
        assertEquals(
            ListState.Failed("モデルを一覧にできず、「gemini」でのテスト呼び出しでも見つかりませんでした。アドレスかインターフェースの種類が間違っているか、そのモデルが存在しない可能性が高いです(例: gemini-flash-latest)。"),
            vm.state.value.form.test,
        )
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        // はアドレスを変更しました:再出品したい場合は、最初にモデル名を非表示にしてください。
        vm.setBaseUrl("$url/")
        assertFalse(vm.state.value.form.noList)
    }

    /** のデフォルト (通常はリスト インターフェイスが利用可能) ではモデルがリストされず、手動で入力したモデルも見つかりません。単にモデルが存在しないと表示されるだけです。 */
    @Test fun presetWithoutListReportsTheMissingModel() = runTest {
        val ark = ProviderPresets.all.first { it.label == "火山方舟" }
        listing[ark.baseUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        reply = { throw ModelError.ModelNotFound("doubao") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ark.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        assertTrue(vm.state.value.form.noList)
        vm.setModel("doubao"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed("モデル「doubao」は存在しません。プロバイダーのドキュメントにある完全なモデル ID を入力してください(例: deepseek-chat)。"), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
    }

    /** は特定の会社の公式アドレスをカスタマイズしました。その会社のデフォルトを使用することをお勧めします。アドレスはデフォルトであり、インターフェイスのタイプは異なります。変更するときはキーを保管してください。 */
    @Test fun officialHostSuggestsThePreset() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.suggestedPreset)
        vm.setBaseUrl("https://generativelanguage.googleapis.com")
        vm.setApiKey("gk")
        val f = vm.state.value.form
        assertEquals("Gemini", f.suggestedPreset?.label)
        assertTrue(f.presetKindMismatch)
        assertEquals(
            "これは Gemini の公式のアドレスです。プリセットを使うと、アドレスとインターフェースの種類が自動で入力されます。インターフェースの種類も合っていません: Gemini は Gemini インターフェースを使いますが、OpenAI 互換 が選ばれています。",
            ModelSetupCopy.presetSuggestion(f),
        )
        assertEquals(ProviderConfig.Kind.Gemini, f.exampleKind)
        // 同じホストへの他のパス (互換性のあるインターフェイスなど): それでも推奨されますが、インターフェイスのタイプが間違っているとは言えません
        vm.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals("Gemini", vm.state.value.form.suggestedPreset?.label)
        assertFalse(vm.state.value.form.presetKindMismatch)
        vm.setKind(ProviderConfig.Kind.Gemini)
        vm.setBaseUrl("https://api.anthropic.com")
        assertEquals("Anthropic", vm.state.value.form.suggestedPreset?.label)
        assertTrue(vm.state.value.form.presetKindMismatch)
        vm.selectPreset("Anthropic")
        val after = vm.state.value.form
        assertEquals(ProviderConfig.Kind.Anthropic, after.kind)
        assertEquals("gk", after.apiKey)
        assertNull("プリセットを選択したら提案を消す", after.suggestedPreset)
    }

    /** 選択ページでモデル名を手動で入力します。最初にそれを呼び出してみて、失敗した場合は選択せず、その理由を伝えます。成功した場合にのみ選択すると、検証済みとして記録されます。 */
    @Test fun manualPickProbesFirst() = runTest {
        CleartextPolicy.setAllowLanHttp(true)
        val store = store()
        val p = store.connect("192.168.1.5", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        val vm = vm(store)
        runCurrent()
        reply = { throw ModelError.ModelNotFound("gemini") }
        var picked = 0
        vm.pickManual(ModelRole.Main, p.id, "gemini") { picked++ }
        assertEquals(ListState.Running, vm.state.value.manualPick[p.id])
        runCurrent()
        assertEquals(ListState.Failed("モデル「gemini」は存在しません。プロバイダーのドキュメントにある完全なモデル ID を入力してください(例: deepseek-chat)。"), vm.state.value.manualPick[p.id])
        assertEquals(0, picked)
        assertNull(store.roles.value.main)
        // ツールを呼ばずに返信だけする場合はメインモデルにはなれません。
        reply = { ModelReply(listOf(Part.Text("noon")), StopReason.EndTurn, null) }
        vm.pickManual(ModelRole.Main, p.id, "chatty") { picked++ }; runCurrent()
        assertEquals(ListState.Failed("chatty はツールを呼び出しませんでした。Mika がタスクをこなすには、ツールを呼び出せるモデルが必要です。別のメインモデルを選んでください。"), vm.state.value.manualPick[p.id])
        assertNull(store.roles.value.main)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickManual(ModelRole.Main, p.id, " qwen3 ") { picked++ }; runCurrent()
        assertEquals(1, picked)
        assertNull(vm.state.value.manualPick[p.id])
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("qwen3"), store.provider(p.id)?.verifiedModels)
    }

    /** 古いバージョンで保存されたサービス プロバイダーはリストを提供しません (手動入力モデルを試したことはありません): リマインダー、「再テスト」に合格すると、リマインダーは消えて書き留められます。失敗した場合は、その理由が説明されます。 */
    @Test fun unverifiedManualModelsWarnUntilRetested() = runTest {
        val store = store()
        val p = store.connect("generativelanguage.googleapis.com", ProviderConfig.Kind.OpenAiCompat, "https://generativelanguage.googleapis.com", "k", emptyList())
        store.use(ModelRole.Main, p.id, "gemini", ProviderPresets.custom.capabilities)
        val vm = vm(store)
        runCurrent()
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        reply = { throw ModelError.ModelNotFound("gemini") }
        vm.retest(p.id); runCurrent()
        assertEquals(
            ListState.Failed("モデル「gemini」は存在しません。プロバイダーのドキュメントにある完全なモデル ID を入力してください(例: gemini-flash-latest)。"),
            vm.state.value.retest[p.id],
        )
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.retest(p.id); runCurrent()
        assertNull(vm.state.value.retest[p.id])
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.single()))
        // サービスプロバイダーにリストを通知しないでください
        listing[deepseekUrl] = deepseek
        vm.openForm(); vm.selectPreset("DeepSeek"); vm.setApiKey("sk")
        vm.testAndSave(); runCurrent()
        assertEquals("https://api.deepseek.com:443/", vm.state.value.pendingDestination)
        assertEquals(1, store.providers.value.size)
        vm.confirmDisplayedDestination()
        vm.testAndSave(); runCurrent()
        val ds = vm.state.value.providers.first { it.name == "DeepSeek" }
        vm.pick(ModelRole.Light, ds.id, "deepseek-coder"); runCurrent()
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.first { it.name == "DeepSeek" }))
    }

    /** クラウドの平文 HTTP は保存できない。LAN は明示設定がオフのとき拒否し、オンのときだけ警告つきで試せる。 */
    @Test fun plainHttpToARemoteHostWarnsButDoesNotBlock() = runTest {
        CleartextPolicy.setAllowLanHttp(false)
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://192.168.1.10:11434/v1")
        assertEquals("LAN への平文 HTTP は、下のスイッチを入れたときだけ使えます。クラウドの http:// は使えません。", vm.state.value.form.urlProblem)
        assertFalse(vm.state.value.form.canTest)
        vm.setAllowLanCleartext(true)
        assertNull(vm.state.value.form.urlProblem)
        assertEquals("このアドレスは暗号化されていません。API キーやチャットの内容が、同じネットワークの他の人に見られる可能性があります。", vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.urlProblem)
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("http://models.example.com/v1")
        assertEquals("このアドレスは暗号化されていません。API キーやチャットの内容が、同じネットワークの他の人に見られる可能性があります。", vm.state.value.form.urlProblem)
        vm.setApiKey("k")
        assertFalse(vm.state.value.form.canTest)
        CleartextPolicy.setAllowLanHttp(false)
    }

    @Test fun addingTwoProvidersInARowKeepsTheFirstMain() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-vl", vision = true), ListedModel("qwen-max")))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val main = store.roles.value.main
        vm.openForm()
        assertTrue(vm.state.value.showForm)
        vm.selectPreset("通义千问")
        vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        assertEquals("https://dashscope.aliyuncs.com:443/compatible-mode/v1", vm.state.value.pendingDestination)
        assertEquals(listOf("DeepSeek"), vm.state.value.providers.map { it.name })
        vm.confirmDisplayedDestination()
        vm.testAndSave(); runCurrent()
        assertEquals(listOf("DeepSeek", "Qwen（中国・北京）"), vm.state.value.providers.map { it.name })
        assertEquals(listOf("sk", "qk"), store.providers.value.map { store.providerKey(it.id) })
        assertEquals("2 件目のプロバイダー追加ではメインモデルを変更しない", main, store.roles.value.main)
        assertEquals(1, store.list().size)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun visionCanComeFromAnotherProvider() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-max"), ListedModel("qwen-plain", toolCalls = false)))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.openForm(); vm.selectPreset("通义千问"); vm.setApiKey("qk")
        vm.confirmDisplayedDestination(); vm.testAndSave(); runCurrent()
        val qwen = vm.state.value.providers.first { it.name == "Qwen（中国・北京）" }
        // 選択ページには、機能によるフィルタリングを行わずに、すべてのサービス プロバイダーのすべてのモデルがリストされます。
        assertEquals(listOf(3, 2), vm.state.value.providers.map { vm.state.value.options(it).size })
        vm.pick(ModelRole.Vision, qwen.id, "qwen-plain"); runCurrent()
        val vision = store.configFor(ModelRole.Vision)!!
        assertEquals("qwen-plain", vision.model)
        assertEquals(qwen.id, vision.providerId)
        assertTrue("画像用に選んだら画像対応として記録する", vision.capabilities.vision)
        assertEquals("qk", store.apiKey(vision.id))
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(listOf(ModelRole.Vision), vm.state.value.rolesOf(qwen))
        // はメイン モデルを使用します。メイン モデルは画像の表示に使用され、画像を表示するための別の構成はなくなりました。
        vm.choose(ModelRole.Vision, RoleChoice.SameAsMain); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Vision)?.model)
        assertFalse(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(1, store.list().size)
        assertEquals(emptyList<ModelRole>(), vm.state.value.rolesOf(qwen))
        // 不要
        vm.choose(ModelRole.Vision, RoleChoice.Off); runCurrent()
        assertNull(store.configFor(ModelRole.Vision))
        assertEquals(RoleChoice.Off, vm.state.value.roles.vision)
    }

    @Test fun pickingMainMarksToolCallsAndSwitchesProvider() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        vm.pick(ModelRole.Main, p.id, "deepseek-coder"); runCurrent()
        val main = store.configFor(ModelRole.Main)!!
        assertEquals("deepseek-coder", main.model)
        assertTrue(main.capabilities.toolCalls)
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertEquals("使用されていない設定を削除する", 1, store.list().size)
    }

    @Test fun nextNeedsAMainModel() = runTest {
        CleartextPolicy.setAllowLanHttp(true)
        val store = store()
        val vm = vm(store)
        vm.selectPreset("自定义")
        vm.setBaseUrl("http://192.168.1.5:8000/v1")
        assertTrue("カスタムの OpenAI 互換 API は Key を省略できる", vm.state.value.form.canTest)
        listing["http://192.168.1.5:8000/v1"] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()
        // このサービスはリストを提供しません。最初にモデル名が必要ですが、保存されません。
        assertTrue(store.providers.value.isEmpty())
        assertFalse(vm.state.value.canNext)
        // モデルは応答のみを行い、ツールを呼び出しません: サービス プロバイダーは保存されますが、メイン モデルは保存されません
        reply = { ModelReply(listOf(Part.Text("noon")), StopReason.EndTurn, null) }
        vm.setModel("chatty"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals("192.168.1.5", p.name)
        assertEquals(emptyList<ListedModel>(), p.models)
        assertFalse(vm.state.value.canNext)
        vm.pick(ModelRole.Main, p.id, " qwen3 "); runCurrent()
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.canNext)
        // メインモデルになる前から手書きで埋められたチャッティーが記憶に残っています。選択したときはまだありました。
        assertEquals(listOf("qwen3", "chatty"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
    }

    @Test fun pickerListsProvidersWithoutRememberedListOnce() = runTest {
        val store = store()
        store.save(ProviderConfig("old", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", "claude-x",
            Capabilities(true, true, 200_000, false), "Anthropic"), "ak")
        store.choose(ModelRole.Main, RoleChoice.Model("old"))
        listing["https://api.anthropic.com"] = ModelListResult.Ok(listOf(ListedModel("claude-x"), ListedModel("claude-y")))
        val vm = vm(store)
        runCurrent()
        val p = vm.state.value.providers.single()
        assertNull(p.models)
        assertEquals(listOf("claude-x"), vm.state.value.options(p).map { it.id })
        vm.ensureListed(); runCurrent()
        vm.ensureListed(); runCurrent()
        assertEquals(1, listCalls.size)
        assertEquals("ak", listCalls.single().third)
        assertEquals(listOf("claude-x", "claude-y"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
        assertTrue("既存の設定はその能力を保持する", vm.state.value.options(vm.state.value.providers.single()).first().capabilities.vision)
    }

    @Test fun refreshFailureIsShownAndRemovingProviderDropsItsRoles() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Network)
        vm.refresh(p.id); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Network.text), vm.state.value.listing[p.id])
        assertEquals("失敗時は元の一覧を保持する", 3, store.providers.value.single().models?.size)
        vm.changeKey(p.id, "sk2"); runCurrent()
        assertEquals("sk2", store.providerKey(p.id))
        vm.removeProvider(p.id); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertEquals(com.wangzan101.mika.settings.RoleChoices(), store.roles.value)
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
    }

    @Test fun copyTagsMarkGuessedCapabilities() {
        val o = com.wangzan101.mika.settings.ModelOptions.merge(
            listOf(ListedModel("a"), ListedModel("b", toolCalls = true, contextTokens = 64_000), ListedModel("c", toolCalls = false)),
            Capabilities(true, false, 64_000, false),
        )
        assertEquals(listOf("ツール呼び出し · 未確認" to false), ModelSetupCopy.tags(o[0]))
        assertEquals(listOf("ツール呼び出し" to true, "64K" to false), ModelSetupCopy.tags(o[1]))
        assertEquals(listOf("ツール呼び出し非対応" to false), ModelSetupCopy.tags(o[2]))
    }

    /** カスタム サービス プロバイダーは名前を付けることができます (先頭と末尾の空白を削除します)。アドレスのホスト名は入力しないでください。同じ住所で名前が違うのが２世帯です。 */
    @Test fun customProviderUsesTheGivenNameOrFallsBackToTheHost() = runTest {
        val url = "https://relay.example.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("m1")))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k1")
        assertEquals("relay.example.com", vm.state.value.form.defaultName)
        vm.setName("  我的中转  ")
        vm.testAndSave(); runCurrent()
        assertEquals(listOf("我的中转"), store.providers.value.map { it.name })
        assertEquals("", vm.state.value.form.name)

        vm.openForm()
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k2"); vm.setName("   ")
        vm.testAndSave(); runCurrent()
        val names = store.providers.value.map { it.name }
        assertEquals(listOf("我的中转", "relay.example.com"), names)
        assertEquals(listOf("k1", "k2"), store.providers.value.map { store.providerKey(it.id) })
    }

    /** 名前には長さの上限があります。デフォルトに変更するとクリアされます。アドレスは特定の会社によって事前に設定されており、デフォルト名はデフォルト名です。デフォルト名はフォーム内の名前の影響を受けません。 */
    @Test fun customNameIsCappedAndOnlyAppliesToCustom() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setName("名".repeat(ModelSetupViewModel.MAX_NAME + 8))
        assertEquals(ModelSetupViewModel.MAX_NAME, vm.state.value.form.name.length)
        // 文字ごとにカット: 絵文字は半分にカットされません
        vm.setName("😀".repeat(ModelSetupViewModel.MAX_NAME + 3))
        val capped = vm.state.value.form.name
        assertEquals(ModelSetupViewModel.MAX_NAME, capped.codePointCount(0, capped.length))
        assertEquals("😀".repeat(ModelSetupViewModel.MAX_NAME), capped)
        vm.setBaseUrl(deepseekUrl)
        assertEquals("DeepSeek", vm.state.value.form.defaultName)
        vm.selectPreset("DeepSeek")
        assertEquals("", vm.state.value.form.name)
        assertEquals("DeepSeek", vm.state.value.form.copy(name = "别的名字").providerName)
    }

    /**
     * リストを見ずにモデル名を手動で入力した結果: リストは取得されます (リストなし)、空のテーブルがリストされます、リスト インターフェイスはありません、キーが間違っています、サーバーにエラーがあり、応答は JSON ではありません。
     * は、手動で入力された単なる試行通話です。試行通話が終了した後にのみ保存され、手動で入力されたものとして記録されます。
     */
    private fun TestScope.typedModelIsProbedWhatever(listed: ModelListResult) {
        val url = "https://relay.example.com/v1"
        listing[url] = listed
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
        assertFalse("押す前はモデル名を表示しない", vm.state.value.form.showModel)
        vm.setManual(true)
        assertTrue(vm.state.value.form.showModel)
        vm.setModel(" plan-model ")
        vm.testAndSave(); runCurrent()
        assertEquals("手動入力したモデルだけを試す", listOf("plan-model"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(setOf("plan-model"), p.verifiedModels)
        assertEquals(setOf("plan-model"), p.typedModels)
        assertEquals("plan-model", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.options(p).single { it.id == "plan-model" }.typed)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun typedModelWithAListThatLacksIt() = runTest { typedModelIsProbedWhatever(deepseek) }
    @Test fun typedModelWithAnEmptyList() = runTest { typedModelIsProbedWhatever(ModelListResult.Ok(emptyList())) }
    @Test fun typedModelWithoutAListEndpoint() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.NotSupported)) }
    @Test fun typedModelWhenListingSaysTheKeyIsWrong() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.Auth)) }
    @Test fun typedModelWhenListingHitsAServerError() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.Server, "HTTP 500")) }
    @Test fun typedModelWhenListingIsNotJson() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.BadResponse)) }

    /** 列モデル エラー、記入する手はありません。真実を正直に伝えてください (キーが間違っている場合は、キーが間違っているとだけ言ってください)、モデル名の列を展開し、記入してからテストします。合格するには、まだ呼び出しを試行する必要があります。 */
    @Test fun listErrorShowsTheReasonAndOpensTheModelField() = runTest {
        for ((i, failure) in listOf(ListFailure.Auth, ListFailure.Server, ListFailure.BadResponse).withIndex()) {
            val url = "https://relay$i.example.com/v1"
            listing[url] = ModelListResult.Failed(failure)
            val store = store()
            val vm = vm(store)
            vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
            vm.testAndSave(); runCurrent()
            val f = vm.state.value.form
            assertEquals(ListState.Failed(failure.text), f.test)
            assertTrue("エラー後は手動入力できる", f.showModel)
            assertTrue(store.providers.value.isEmpty())
            // トライアルコールでも「キーが間違っています: 存在しません」と表示されます
            reply = { throw ModelError.Auth() }
            vm.setModel("plan-model"); vm.testAndSave(); runCurrent()
            assertTrue(vm.state.value.form.test is ListState.Failed)
            assertTrue(store.providers.value.isEmpty())
            reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
            vm.closeForm()
        }
    }

    /** リストには手動入力があります。試してみるのみで、手動入力とはマークしないでください。 */
    @Test fun typedModelThatIsListedIsNotMarkedTyped() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.setManual(true); vm.setModel("deepseek-reasoner"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("deepseek-reasoner"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(emptySet<String>(), p.typedModels)
        assertEquals("deepseek-reasoner", store.configFor(ModelRole.Main)?.model)
        // 手動で入力する場合、入力する必要のないものを非表示にします
        vm.openForm(); vm.setApiKey("sk"); vm.setManual(true); vm.setModel("x"); vm.setManual(false)
        assertEquals("", vm.state.value.form.typedModel)
    }

    /** 選択ページにリストがある場合は、手動で入力することもできます。合格した場合は、手動入力としてマークされます。リストを更新してもまだ存在する場合は、「手動入力」としてマークされます。 */
    @Test fun pickerAllowsTypingAModelForAListedProvider() = runTest {
        val store = store()
        val p = store.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk", deepseek.models)
        val vm = vm(store)
        runCurrent()
        vm.pickManual(ModelRole.Light, p.id, "deepseek-v9"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
        assertEquals("ファイルに保存する", setOf("deepseek-v9"), store().provider(p.id)?.typedModels)
        store.rememberModels(p.id, deepseek.models)
        vm.pick(ModelRole.Light, p.id, "deepseek-chat"); runCurrent()
        val options = vm.state.value.options(store.provider(p.id)!!)
        assertTrue(options.single { it.id == "deepseek-v9" }.typed)
        assertFalse(options.single { it.id == "deepseek-chat" }.typed)
        // 選択リストの項目は手動入力として記録されません。
        vm.pickManual(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
    }

    // ---------- テストせずに直接保存してください ----------

    /** は一度もネットワークにアクセスしません。モデルをリストせず、呼び出しも試行せず、アドレス、キーを保存するだけで、「テストされていません」。 */
    @Test fun saveWithoutTestStoresProviderWithoutListingOrProbe() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.saveWithoutTest(); runCurrent()
        assertTrue("モデル一覧取得を一度も呼び出していない", listCalls.isEmpty())
        assertTrue("テスト呼び出しを一度も行っていない", built.isEmpty())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals("sk-1", store.providerKey(p.id))
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(emptySet<String>(), p.verifiedModels)
        assertTrue("未テストとして記録する", p.savedUntested)
        // セーブファイルもあります
        assertTrue(store().provider(p.id)!!.savedUntested)
        // にはメインモデルがありません。ユーザーが選択することができます (選択するときは、最初に電話してみてください)
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertEquals(p.id, s.pickToProbe)
        assertFalse("保存後はフォームを閉じる", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue("保存後、未テストであることを明示する", s.probeNote?.contains(p.name) == true)
    }

    /** モデル名は手動で入力されます。手動で入力されたものとしてのみ記録され、自動的にメイン モデルにはなりません。メイン機種として選択する場合は、一度呼び出してみて、動作してからご利用ください。 設定を再起動したとき (インターフェイスのステータスが消えたとき) */
    @Test fun saveWithoutTestWithTypedModelDoesNotBecomeMainUntilProbed() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.setManual(true); vm.setModel("deepseek-v9")
        vm.saveWithoutTest(); runCurrent()
        assertTrue(built.isEmpty())
        assertTrue(listCalls.isEmpty())
        val p = store.providers.value.single()
        assertEquals(setOf("deepseek-v9"), p.typedModels)
        assertEquals(emptySet<String>(), p.verifiedModels)
        assertTrue(p.savedUntested)
        assertNull("未テストのモデルを自動でメインモデルにしない", store.roles.value.main)
        assertTrue(store.list().isEmpty())
        assertEquals(p.id, vm.state.value.pickToProbe)
        // にも同じことが当てはまります。テストをスキップして、選択したマスター モデルを保存し、最初にそれを呼び出してみます。
        val again = vm(store)
        runCurrent()
        assertNull(again.state.value.pickToProbe)
        assertTrue(again.state.value.probeBeforePick(ModelRole.Main, p.id))
        assertFalse(again.state.value.probeBeforePick(ModelRole.Light, p.id))
        again.pickManual(ModelRole.Main, p.id, "deepseek-v9"); runCurrent()
        assertEquals(listOf("deepseek-v9"), built.map { it.first })
        assertEquals("deepseek-v9", store.configFor(ModelRole.Main)?.model)
        assertFalse("テストに合格したら未テストとは扱わない", store.provider(p.id)!!.savedUntested)
        assertFalse(again.state.value.probeBeforePick(ModelRole.Main, p.id))
    }

    /** すでにメイン モデルがある場合は、テストをスキップして別のモデルを保存します。メイン モデルは変更されません。 */
    @Test fun saveWithoutTestKeepsTheCurrentMainModel() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val main = store.configFor(ModelRole.Main)
        assertEquals("deepseek-chat", main?.model)
        vm.openForm(); vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://127.0.0.1:11434/v1"); vm.setManual(true); vm.setModel("local-model")
        vm.saveWithoutTest(); runCurrent()
        assertEquals(main, store.configFor(ModelRole.Main))
        assertNull(vm.state.value.pickToProbe)
    }

    /** アドレス形式が間違っているか、キーが入力されていないため、何も保存されません。 */
    @Test fun saveWithoutTestRefusesAnInvalidForm() = runTest {
        val store = store()
        val vm = vm(store)
        assertFalse("Key がまだ入力されていない", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("api.deepseek.com")
        vm.setApiKey("sk")
        assertFalse("アドレスにプロトコルがない", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        // 実行するサービスにはキーは必要ありません。キーは保存できます。
        vm.setBaseUrl("http://127.0.0.1:11434/v1")
        vm.setApiKey("")
        assertTrue(vm.state.value.form.canSaveUntested)
    }

    /** テスト済みのものはテストをスキップして再度保存します。サービス プロバイダーは繰り返されず、キーは新しいものに置き換えられ、検証されたモデルは保持されます。 */
    @Test fun saveWithoutTestKeepsExistingProviderAndItsVerifiedModels() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals(setOf("deepseek-chat"), p.verifiedModels)
        assertFalse(p.savedUntested)
        vm.openForm(); vm.selectPreset("DeepSeek"); vm.setApiKey("sk-2"); vm.saveWithoutTest(); runCurrent()
        assertEquals("重複して追加しない", 1, store.providers.value.size)
        val after = store.provider(p.id)!!
        assertEquals("sk-2", store.providerKey(p.id))
        assertEquals("取得済みの一覧を保持する", listOf("deepseek-chat", "deepseek-reasoner", "deepseek-coder"), after.models!!.map { it.id })
        assertEquals(setOf("deepseek-chat"), after.verifiedModels)
        assertFalse("確認済みのモデルがあれば、そのプロバイダーを未テストとは扱わない", after.savedUntested)
    }
}
