package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.PromptCaching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsTest {
    private val base = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Explicit)

    @Test fun unknownCapabilitiesKeepPresetValues() {
        val o = ModelOptions.merge(listOf(ListedModel("m")), base).single()
        assertEquals(base, o.capabilities)
        assertFalse(o.toolCallsKnown)
        assertFalse(o.visionKnown)
        assertFalse(o.contextKnown)
    }

    @Test fun knownCapabilitiesOverridePresetButKeepCaching() {
        val o = ModelOptions.merge(listOf(ListedModel("m", toolCalls = false, vision = true, contextTokens = 1_000_000, reasoning = true)), base).single()
        assertEquals(Capabilities(false, true, 1_000_000, true, promptCaching = PromptCaching.Explicit), o.capabilities)
        assertTrue(o.toolCallsKnown && o.visionKnown && o.contextKnown)
        assertFalse(o.canBeMain)
    }

    @Test fun userSaidVisionWinsOverListingAndPreset() {
        assertTrue("未指定でも、ユーザーが画像対応と指定した場合は有効にする", ModelOptions.seesImages(stored = false, userSaid = true, usedForVision = false))
        assertTrue("画像用モデルとしても使用する", ModelOptions.seesImages(stored = false, userSaid = null, usedForVision = true))
        assertFalse("ユーザーが画像非対応と指定した場合は無効にする", ModelOptions.seesImages(stored = true, userSaid = false, usedForVision = true))
        assertFalse("一覧で画像非対応とされ、ユーザーの変更がない場合は無効にする", ModelOptions.seesImages(stored = false, userSaid = null, usedForVision = false))
        val p = Provider("p", "P", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1",
            models = listOf(ListedModel("a"), ListedModel("b", vision = false)), visionOverrides = mapOf("a" to true))
        val (a, b) = ModelOptions.ofProvider(p, emptyList())
        assertTrue(a.canSee)
        assertEquals(true, a.visionByUser)
        assertFalse(b.canSee)
        assertEquals(null, b.visionByUser)
    }

    @Test fun toolCapableFirstAndDefaultMainPrefersConfirmed() {
        val listed = listOf(ListedModel("embed", toolCalls = false), ListedModel("guess"), ListedModel("sure", toolCalls = true))
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("guess", "sure", "embed"), options.map { it.id })
        assertEquals("sure", ModelOptions.defaultMain(options))
        assertEquals("guess", ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("guess")), base)))
        assertEquals(null, ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("embed", toolCalls = false)), base)))
    }

    /** 独自の (デフォルトのモデル単語) から始めて、次にツールを呼び出すことができることを明確に示すインターフェイス、そして残りはリストの順序で記述します。ベクター/スピーチ/描画など、ツールを呼び出すことができないと明確に示されているものは試さないでください。 */
    @Test fun candidatesPreferTheProvidersOwnFamily() {
        val listed = listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true),
            ListedModel("text-embedding-v4"),
            ListedModel("gte-rerank-v2"),
            ListedModel("qwen3-tts-flash"),
            ListedModel("wanx-image", chat = false),
            ListedModel("qwen-max"),
            ListedModel("qwen-plus", toolCalls = true),
            ListedModel("Other/Chat", displayName = "Qwen inside"),
            ListedModel("qwen-coder", toolCalls = false),
            ListedModel("moonshot-v1"),
        )
        val options = ModelOptions.merge(listed, base)
        val qwen = ProviderPresets.all.single { it.label == "通义千问" }.familyTokens
        assertEquals(listOf("qwen"), qwen)
        assertEquals(
            listOf("qwen-plus", "qwen-max", "Other/Chat", "antdigital/Ling-3.1-flash", "moonshot-v1"),
            ModelOptions.candidates(options, qwen, allowChineseModels = true),
        )
        assertEquals("qwen-plus", ModelOptions.defaultMain(options, qwen, allowChineseModels = true))
        // 標準の自動選択では中国系モデルを外す。中立なIDでも表示名がQwenなら中国系として扱う。
        assertEquals(emptyList<String>(), ModelOptions.candidates(options))
        assertEquals(qwen, ModelOptions.familyOf("https://dashscope.aliyuncs.com/compatible-mode/v1/"))
        assertEquals(emptyList<String>(), ModelOptions.familyOf("https://openrouter.ai/api/v1"))
    }

    @Test fun relayAutomaticCandidatesExcludeKnownChineseModelFamilies() {
        val chinese = listOf("deepseek/deepseek-v4", "Qwen/Qwen3.5", "z-ai/glm-5", "kimi-k2.7", "minimax-m3", "step-3.5", "tencent/hunyuan", "antdigital/Ling-3.1-flash", "longcat-flash")
        val options = ModelOptions.merge((chinese + listOf("openai/gpt-6.1-sol", "anthropic/claude-sonnet", "google/gemini-pro")).map { ListedModel(it, toolCalls = true) }, base)
        assertEquals(listOf("openai/gpt-6.1-sol", "anthropic/claude-sonnet", "google/gemini-pro"), ModelOptions.candidates(options))
        assertEquals("openai/gpt-6.1-sol", ModelOptions.defaultMain(options))
        assertEquals("openai/gpt-6.1-sol", ModelOptions.automaticFallback(options))
    }

    @Test fun automaticFallbackDoesNotRestoreAChineseOnlyList() {
        val options = ModelOptions.merge(listOf(ListedModel("deepseek-v4", toolCalls = true), ListedModel("qwen3-max", toolCalls = true)), base)
        assertTrue(ModelOptions.candidates(options).isEmpty())
        assertEquals(null, ModelOptions.defaultMain(options))
        assertEquals(null, ModelOptions.automaticFallback(options))
        // 明示的に中国系プリセットを選んだときは、利用者の選択を保持する。
        assertEquals("deepseek-v4", ModelOptions.defaultMain(options, listOf("deepseek"), allowChineseModels = true))
        assertEquals("qwen3-max", ModelOptions.defaultMain(options, listOf("qwen"), allowChineseModels = true))
        assertEquals(listOf("deepseek-v4", "qwen3-max"), options.map { it.id })
    }

    @Test fun automaticFallbackNeverSelectsANonChatOrToollessModel() {
        val options = ModelOptions.merge(listOf(
            ListedModel("text-embedding-v4"),
            ListedModel("not-chat", toolCalls = true, chat = false),
            ListedModel("chat-without-tools", toolCalls = false, chat = true),
        ), base)
        assertTrue(ModelOptions.candidates(options).isEmpty())
        assertEquals(null, ModelOptions.defaultMain(options))
        assertEquals(null, ModelOptions.automaticFallback(options))
        assertEquals(null, ModelOptions.defaultMain(options, allowChineseModels = true))
        assertEquals(null, ModelOptions.automaticFallback(options, allowChineseModels = true))
        assertEquals(listOf("text-embedding-v4", "not-chat", "chat-without-tools"), options.map { it.id })
    }

    @Test fun automaticPolicyDoesNotGuessAnUnknownModelFamily() {
        val options = ModelOptions.merge(listOf(ListedModel("customer-chat", displayName = "社内モデル"), ListedModel("step-by-step-chat")), base)
        assertEquals(listOf("customer-chat", "step-by-step-chat"), ModelOptions.candidates(options))
    }

    @Test fun familyRankingDoesNotGrantAnExplicitConnectionOptIn() {
        val options = ModelOptions.merge(listOf(ListedModel("qwen3-max"), ListedModel("claude-sonnet")), base)
        assertEquals(listOf("claude-sonnet"), ModelOptions.candidates(options, listOf("qwen")))
        assertEquals(listOf("qwen3-max", "claude-sonnet"), ModelOptions.candidates(options, listOf("qwen"), allowChineseModels = true))
    }

    @Test fun relayExcludesKnownNamespacesAndVersionedAliasesWithoutMatchingGenericWords() {
        val chinese = listOf(
            "bytedance/ui-tars-1.5-7b", "bytedance-seed/seed-2.0", "xiaomi/mimo-v2.5-pro", "alibaba/tongyi-deepresearch-30b", "inclusionai/ring-1t",
            "kwaipilot/kat-coder-pro", "01-ai/yi-1.5-34b-chat", "seed-2.0", "mimo-v2.5", "tongyi-2.1", "ring-1t", "kat-2", "yi-1.5",
        )
        val neutral = listOf("seed-chat", "mimo-helper", "ring-chat", "kat", "yi", "spring-1.0", "my-seed-2", "custom/ring-chat", "not-alibaba/tongyi-helper", "not-bytedance/ui-tars-1.5")
        val options = ModelOptions.merge((chinese + neutral).map { ListedModel(it, toolCalls = true) }, base)
        assertEquals(neutral, ModelOptions.candidates(options))
        assertEquals(chinese + neutral, ModelOptions.candidates(options, allowChineseModels = true))
    }

    private fun ids(listed: List<ListedModel>, tokens: List<String>, allowChineseModels: Boolean = false) = ModelOptions.candidates(ModelOptions.merge(listed, base), tokens, allowChineseModels)

    /** 独自のモデルは、インターフェイスの順序に関係なく、最新バージョンの順にリストされています。同じバージョンが順番にリストされており、Flash、Pro、Lite のどれが優れているかについてはコメントされていません。 */
    @Test fun ownModelsNewestVersionFirst() {
        assertEquals(
            listOf("x4", "x-3.1-pro", "x-3-flash", "x-3-pro", "x-2.5-flash", "x-2.0-flash-001"),
            ids(listOf("x-2.5-flash", "x-3-flash", "x-2.0-flash-001", "x-3.1-pro", "x4", "x-3-pro").map { ListedModel(it) }, listOf("x")),
        )
        // 水平線で区切られた小さいバージョン、文字付きのバージョン番号。 b/k はパラメータの量とコンテキストの長さで終わり、バージョンはカウントされません
        assertEquals(
            listOf("claude-haiku-4-5-20251001", "claude-opus-4-1-20250805", "claude-3-5-haiku-20241022"),
            ids(listOf("claude-3-5-haiku-20241022", "claude-opus-4-1-20250805", "claude-haiku-4-5-20251001").map { ListedModel(it) }, listOf("claude")),
        )
        assertEquals(listOf("qwen3-max", "qwen2.5-72b-instruct"), ids(listOf(ListedModel("qwen2.5-72b-instruct"), ListedModel("qwen3-max")), listOf("qwen"), allowChineseModels = true))
    }

    /** 同じバージョン: 公式バージョンが最初に来て、次に最新のエイリアスが来て、プレビューとテストのエイリアスが最後に来ます。同じファイル内では、日付のないバージョンが最初に来て、次に新しいバージョンが最初に来ます。 */
    @Test fun stableBeforePreviewAtTheSameVersion() {
        assertEquals(
            listOf("x-2.5-flash", "x-2.5-flash-2025-08-07", "x-2.5-flash-latest", "x-2.5-flash-preview-09-2025", "x-2.5-flash-exp-0827", "x-2.5-flash-preview-05-20"),
            ids(
                listOf("x-2.5-flash-exp-0827", "x-2.5-flash-preview-05-20", "x-2.5-flash-latest", "x-2.5-flash-preview-09-2025", "x-2.5-flash-2025-08-07", "x-2.5-flash")
                    .map { ListedModel(it) },
                listOf("x"),
            ),
        )
        // たとえ更新版がプレビューであっても、旧バージョンの正式版よりも上位にランクされています。
        assertEquals(listOf("x-3-pro-preview", "x-2.5-pro"), ids(listOf(ListedModel("x-2.5-pro"), ListedModel("x-3-pro-preview")), listOf("x")))
    }

    /** 読み込みできないバージョン（エイリアスなど）は、読み込み可能なバージョンの後に、リスト順に記載されています。 */
    @Test fun unparsableVersionsAfterParsedOnes() {
        assertEquals(
            listOf("x-2-mini", "x-1.5", "x-flash-latest", "x-chat", "x-pro"),
            ids(listOf("x-flash-latest", "x-chat", "x-1.5", "x-pro", "x-2-mini").map { ListedModel(it) }, listOf("x")),
        )
    }

    /** 外側の順序は変更されません。独自のバージョンが最初に来て、次にツールを呼び出すことができることを明確に示すインターフェイスが続きます。バージョン比較は自社バージョンの同一ファイル内のみであり、他社バージョンはリスト順に並んでいます。 */
    @Test fun versionOrderStaysInsideTheOuterOrder() {
        val listed = listOf(
            ListedModel("other-9"),
            ListedModel("x-1", toolCalls = true),
            ListedModel("x-3"),
            ListedModel("x-2", toolCalls = true),
            ListedModel("other-1", toolCalls = true),
            ListedModel("other-5"),
        )
        assertEquals(listOf("x-2", "x-1", "x-3", "other-1", "other-9", "other-5"), ids(listed, listOf("x")))
        // には独自の言葉はありません: いつものように、バージョンごとに整理されていません
        assertEquals(listOf("x-1", "x-2", "other-1", "other-9", "x-3", "other-5"), ids(listed, emptyList()))
    }

    /** インターフェイスでは、古いインターフェイス 2.5 が最初にリストされます。最新のインターフェイスが最初に試行され、他社のベクトル モデルとジェムマは最初に試行されません。 */
    @Test fun realisticGeminiListTriesTheNewestFirst() {
        val listed = listOf(
            ListedModel("gemini-2.5-flash", displayName = "Gemini 2.5 Flash"),
            ListedModel("gemini-2.5-pro", displayName = "Gemini 2.5 Pro"),
            ListedModel("gemini-2.0-flash", displayName = "Gemini 2.0 Flash"),
            ListedModel("gemini-2.0-flash-001", displayName = "Gemini 2.0 Flash 001"),
            ListedModel("gemini-2.0-flash-lite", displayName = "Gemini 2.0 Flash-Lite"),
            ListedModel("gemini-2.5-flash-preview-09-2025", displayName = "Gemini 2.5 Flash Preview Sep 2025"),
            ListedModel("gemini-flash-latest", displayName = "Gemini Flash Latest"),
            ListedModel("gemini-3-pro-preview", displayName = "Gemini 3 Pro Preview"),
            ListedModel("gemma-3-27b-it", displayName = "Gemma 3 27B"),
            ListedModel("gemini-3.1-pro", displayName = "Gemini 3.1 Pro"),
            ListedModel("gemini-3-flash", displayName = "Gemini 3 Flash"),
            ListedModel("gemini-embedding-001", displayName = "Gemini Embedding 001"),
        )
        val gemini = ProviderPresets.all.single { it.label == "Gemini" }.familyTokens
        assertEquals(
            listOf(
                "gemini-3.1-pro", "gemini-3-flash", "gemini-3-pro-preview", "gemini-2.5-flash", "gemini-2.5-pro",
                "gemini-2.5-flash-preview-09-2025", "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.0-flash-001",
                "gemini-flash-latest", "gemma-3-27b-it",
            ),
            ids(listed, gemini),
        )
        assertEquals("gemini-3.1-pro", ModelOptions.defaultMain(ModelOptions.merge(listed, base), gemini))
    }

    @Test fun nonChatModelsAreRecognised() {
        fun opt(id: String, chat: Boolean? = null) = ModelOptions.merge(listOf(ListedModel(id, chat = chat)), base).single()
        for (id in listOf("text-embedding-3-small", "bge-reranker", "whisper-1", "gpt-4o-mini-tts", "gpt-4o-transcribe", "dall-e-3",
            "gpt-image-1", "qwen-vl-ocr", "paraformer-realtime-v2", "omni-moderation-latest", "qwen3-asr-flash")) {
            assertTrue(id, ModelOptions.looksNonChat(opt(id)))
        }
        for (id in listOf("qwen-plus", "deepseek-chat", "gpt-5-mini", "claude-sonnet-4-5", "qwen-vl-max", "step-2-16k")) {
            assertFalse(id, ModelOptions.looksNonChat(opt(id)))
        }
        assertFalse("API が対話モデルと示した場合はその情報を使う", ModelOptions.looksNonChat(opt("my-embed-chat", chat = true)))
        assertTrue(ModelOptions.looksNonChat(opt("x", chat = false)))
        // 用途が違うモデルしかない場合は、自動選択の入口を変えてもメインモデルへ設定しない。
        val only = ModelOptions.merge(listOf(ListedModel("text-embedding-v4")), base)
        assertEquals(emptyList<String>(), ModelOptions.candidates(only))
        assertEquals(null, ModelOptions.defaultMain(only))
        assertEquals(null, ModelOptions.automaticFallback(only))
    }

    @Test fun modelsOnlyReachableByUnsupportedProtocolsAreNotOffered() {
        val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://gw.test/openai/v1")
        val listed = listOf(
            ListedModel("realtime-only", toolCalls = true, unsupportedApis = listOf("realtime")),
            ListedModel("chat", toolCalls = true, endpoint = chat),
            ListedModel("responses", toolCalls = true, endpoint = ModelEndpoint(ModelProtocol.Responses, "https://gw.test/openai/v1")),
            ListedModel("plain"),
        )
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("chat", "responses", "plain"), options.map { it.id })
        assertEquals("chat", ModelOptions.defaultMain(options))
    }

    @Test fun openCodeGoPresetListsFromItsCatalogAddress() {
        val p = ProviderPresets.all.single { it.label == "OpenCode Go" }
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals("https://opencode.ai/zen/go/v1", p.baseUrl)
        assertEquals(p, ProviderPresets.forBaseUrl("https://opencode.ai/zen/go/v1/"))
    }

    @Test fun contextLabels() {
        assertEquals("64K", ModelOptions.contextLabel(64_000))
        assertEquals("128K", ModelOptions.contextLabel(131_072))
        assertEquals("200K", ModelOptions.contextLabel(200_000))
        assertEquals("1M", ModelOptions.contextLabel(1_048_576))
        assertEquals("2M", ModelOptions.contextLabel(2_000_000))
    }

    @Test fun sameProviderKeyIgnoresTrailingSlash() {
        assertEquals(
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "DeepSeek"),
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "DeepSeek "),
        )
        val local = ProviderConfig("3", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "local", base)
        assertEquals("192.168.1.5", ProviderConnection.displayName(local))
    }

    @Test fun providerOptionsAreRememberedListPlusSavedConfigs() {
        val p = Provider("p", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", listOf(ListedModel("a"), ListedModel("b")))
        val savedCaps = base.copy(vision = true)
        val configs = listOf(
            ProviderConfig("1", p.kind, p.baseUrl, "b", savedCaps, p.name, "p"),
            ProviderConfig("2", p.kind, p.baseUrl, "typed", base, p.name, "p"),
            ProviderConfig("3", p.kind, p.baseUrl, "other", base, p.name, "q"),
        )
        val options = ModelOptions.ofProvider(p, configs)
        assertEquals(listOf("a", "b", "typed"), options.map { it.id })
        assertTrue("既存の設定には保存済みの能力を使う", options.first { it.id == "b" }.capabilities.vision)
        assertEquals(emptyList<String>(), ModelOptions.ofProvider(p.copy(models = null), emptyList()).map { it.id })
    }
}
