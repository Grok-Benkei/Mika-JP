package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.PromptCaching
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * モデルを追加するときの共通サービス プロバイダー テンプレート: インターフェイス タイプ、ベース URL、および機能の初期チェックのみが事前に入力されます。
 * のモデル名とキーはユーザーが入力し、機能を変更することもできます。
 * [sessionHeader] は、サービスプロバイダーが会話セッション番号を入れるために必要なリクエストヘッダー名です ([com.wangzan101.mika.core.model.ModelSession] を参照)。必要ない場合は null です。
 * [selfHosted] は、ユーザー自身のマシンで実行されているサービスです。[baseUrl] は、マシンのデフォルト ポートにすぎません。このフォームを使用すると、ユーザーはそれをそのマシンのアドレスに変更できます。キーを入力する必要はありません。
 * [familyTokens] は、この会社独自のモデルの名前に含まれる単語 (小文字) です。キーの下に他のモデルがリストされている場合、デフォルトでは最初に独自のモデルを試します ([ModelOptions.candidates] を参照)。
 * は、複数のモデルをホストして自分で実行するユーザーには提供されません。
 */
data class ProviderPreset(
    val label: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val capabilities: Capabilities,
    val sessionHeader: String? = null,
    val selfHosted: Boolean = false,
    val familyTokens: List<String> = emptyList(),
) {
    /** 保存済み設定の識別名を保ちつつ、画面では地域が分かる名称を使う。 */
    val displayLabel: String get() = when (label) {
        "通义千问" -> "Qwen（中国・北京）"
        "Kimi" -> "Kimi（中国本土）"
        "智谱" -> "GLM（中国本土）"
        "智谱 Coding Plan" -> "GLM Coding Plan（中国本土）"
        "硅基流动" -> "SiliconFlow（中国本土）"
        "火山方舟" -> "Volcengine Ark（中国本土）"
        "MiniMax" -> "MiniMax（中国本土）"
        "阶跃星辰" -> "StepFun"
        "腾讯混元" -> "Tencent Hunyuan（中国本土）"
        "百度千帆" -> "Baidu Qianfan（中国本土）"
        "Qwen Global" -> "Qwen（シンガポール）"
        "Kimi Global" -> "Kimi"
        "MiniMax Global" -> "MiniMax"
        else -> label
    }
}

object ProviderPresets {
    private fun caps(vision: Boolean = false, context: Int = 64_000, caching: PromptCaching = PromptCaching.Implicit) =
        Capabilities(toolCalls = true, vision = vision, contextTokens = context, reasoning = false, promptCaching = caching)

    /** ベース URL に一致するテンプレート (追加時に別のテンプレートを選択した場合、またはテストでアドレスが直接指定された場合は、アドレスに基づいて機能の初期値を入力します)。そうでない場合は null です。 */
    fun forBaseUrl(url: String): ProviderPreset? = all.firstOrNull { it.baseUrl.isNotEmpty() && url.trimEnd('/') == it.baseUrl.trimEnd('/') }

    /**
     * ホスト名はサービス プロバイダー テンプレートのアドレスと同じです (パスは異なる場合があります): 特定の企業の正式なアドレスをカスタマイズする場合は、代わりにそのテンプレートを使用することをお勧めします。
     * 自分で実行したサービス（ローカルマシンアドレス）はカウントされません。アドレスが認識されない場合、または一致しない場合は、null になります。
     */
    fun forHost(url: String): ProviderPreset? {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return null
        // Zhipu の標準 API とコーディング プランは、同じホスト上に異なるパスを持っています。共通のパスはより具体的なプリセットです。
        return all.filter { !it.selfHosted && it.baseUrl.isNotEmpty() && it.baseUrl.toHttpUrlOrNull()?.host == host }
            .maxByOrNull { pathOverlap(it.baseUrl, url) }
    }

    // 国際向けの接続先を先に並べる。APIキーはサービスとリージョンごとに異なる。
    val all: List<ProviderPreset> = listOf(
        ProviderPreset("OpenAI", ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1", caps(vision = true, context = 400_000), familyTokens = listOf("gpt")),
        ProviderPreset("Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", caps(vision = true, context = 200_000), familyTokens = listOf("claude")),
        ProviderPreset("Gemini", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com", caps(vision = true, context = 128_000), familyTokens = listOf("gemini")),
        ProviderPreset("OpenRouter", ProviderConfig.Kind.OpenAiCompat, "https://openrouter.ai/api/v1", caps()),
        ProviderPreset("OpenCode Go", ProviderConfig.Kind.OpenAiCompat, "https://opencode.ai/zen/go/v1", caps(), sessionHeader = "x-opencode-session"),
        ProviderPreset("xAI", ProviderConfig.Kind.OpenAiCompat, "https://api.x.ai/v1", caps(vision = true, context = 256_000), familyTokens = listOf("grok")),
        ProviderPreset("Mistral", ProviderConfig.Kind.OpenAiCompat, "https://api.mistral.ai/v1", caps(vision = true, context = 128_000), familyTokens = listOf("mistral")),
        ProviderPreset("Groq", ProviderConfig.Kind.OpenAiCompat, "https://api.groq.com/openai/v1", caps(context = 128_000)),
        ProviderPreset("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", caps(), familyTokens = listOf("deepseek")),
        ProviderPreset("Qwen Global", ProviderConfig.Kind.OpenAiCompat, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", caps(caching = PromptCaching.Explicit), familyTokens = listOf("qwen")),
        ProviderPreset("Kimi Global", ProviderConfig.Kind.OpenAiCompat, "https://api.moonshot.ai/v1", caps(), familyTokens = listOf("kimi", "moonshot")),
        ProviderPreset("MiniMax Global", ProviderConfig.Kind.OpenAiCompat, "https://api.minimax.io/v1", caps(vision = true, context = 200_000), familyTokens = listOf("minimax")),
        ProviderPreset("阶跃星辰", ProviderConfig.Kind.OpenAiCompat, "https://api.stepfun.com/v1", caps(), familyTokens = listOf("step")),
        ProviderPreset("Ollama", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:11434/v1", caps(), selfHosted = true),
        ProviderPreset("LM Studio", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:1234/v1", caps(), selfHosted = true),
        ProviderPreset("通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1", caps(caching = PromptCaching.Explicit), familyTokens = listOf("qwen")),
        ProviderPreset("Kimi", ProviderConfig.Kind.OpenAiCompat, "https://api.moonshot.cn/v1", caps(), familyTokens = listOf("kimi", "moonshot")),
        ProviderPreset("智谱", ProviderConfig.Kind.OpenAiCompat, "https://open.bigmodel.cn/api/paas/v4", caps(), familyTokens = listOf("glm")),
        ProviderPreset("智谱 Coding Plan", ProviderConfig.Kind.OpenAiCompat, "https://open.bigmodel.cn/api/coding/paas/v4", caps(), familyTokens = listOf("glm")),
        ProviderPreset("硅基流动", ProviderConfig.Kind.OpenAiCompat, "https://api.siliconflow.cn/v1", caps()),
        ProviderPreset("火山方舟", ProviderConfig.Kind.OpenAiCompat, "https://ark.cn-beijing.volces.com/api/v3", caps(vision = true, context = 128_000), familyTokens = listOf("doubao")),
        ProviderPreset("MiniMax", ProviderConfig.Kind.OpenAiCompat, "https://api.minimax.cn/v1", caps(vision = true, context = 200_000), familyTokens = listOf("minimax")),
        ProviderPreset("腾讯混元", ProviderConfig.Kind.OpenAiCompat, "https://tokenhub.tencentmaas.com/v1", caps(context = 256_000), familyTokens = listOf("hunyuan")),
        ProviderPreset("百度千帆", ProviderConfig.Kind.OpenAiCompat, "https://qianfan.baidubce.com/v2", caps(), familyTokens = listOf("ernie")),
        ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
    )

    private val mainlandLabels = setOf("通义千问", "Kimi", "智谱", "智谱 Coding Plan", "硅基流动", "火山方舟", "MiniMax", "腾讯混元", "百度千帆")
    val recommended: List<ProviderPreset> get() = all.filterNot { it.label in mainlandLabels }
    val otherRegions: List<ProviderPreset> get() = all.filter { it.label in mainlandLabels }

    val custom: ProviderPreset get() = all.last()

    /** 同じホストに複数のプリセットがある場合は、セグメントごとのパスの同じ長さに基づいて、より近いものを選択します。ルートアドレスが並列している場合、事前に設定された順序を維持します。 */
    private fun pathOverlap(presetUrl: String, inputUrl: String): Int {
        val preset = presetUrl.trimEnd('/').toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() } ?: return 0
        val input = inputUrl.trim().toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() } ?: return 0
        return preset.indices.count { preset[it] == input.getOrNull(it) }
    }
}
