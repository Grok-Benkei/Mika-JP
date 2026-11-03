package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * リスト内のオプションモデルです。 [capabilities] はマージされた機能です。インターフェイスが提供されている場合は引き続き使用され、提供されていない場合はデフォルトの初期値が使用されます。
 * [toolCallsKnown]、[visionKnown] インターフェイスに明確に表示されている内容を書き留めます (それに応じて、インターフェイスには「未確認」のマークが付けられます)。
 * [chat] はインターフェイスですが、会話モデルであるとは言えません ([ListedModel.chat] を参照)。それ以外の場合は null です。
 * [typed] はリストされているモデルに含まれていないため、ユーザーが手動で入力します (インターフェース上に「手動入力」とマークされています)。
 * [visionByUser] は、画像を表示できるかどうかについてユーザーが言ったことです ([Provider.visionOverrides] を参照)。それが言われない場合、それは null です。記載されている内容は [capabilities] に含まれています。
 */
data class ModelOption(
    val id: String,
    val capabilities: Capabilities,
    val toolCallsKnown: Boolean,
    val visionKnown: Boolean,
    val contextKnown: Boolean,
    val displayName: String = "",
    val chat: Boolean? = null,
    val typed: Boolean = false,
    val visionByUser: Boolean? = null,
) {
    /** 参照用の機能タグ。ユーザーはモデルを選択するときにそれらでフィルターをかけません。 */
    val canBeMain: Boolean get() = capabilities.toolCalls
    val canSee: Boolean get() = capabilities.vision
}

object ModelOptions {
    /**
     * リストされたモデルとプリセット機能を結合します。メイン モデルをキューの先頭に配置し、残りはサービス プロバイダーが指定した順序のままにすることができます。
     * は、Mika が説明できないインターフェイス プロトコルのモデルのみを書き込みます ([ListedModel.usable] を参照)。呼び出しは必然的に失敗するため、リストには表示されません。
     */
    fun merge(listed: List<ListedModel>, base: Capabilities): List<ModelOption> = listed.filter { it.usable }.map { m ->
        ModelOption(
            id = m.id,
            capabilities = base.copy(
                toolCalls = m.toolCalls ?: base.toolCalls,
                vision = m.vision ?: base.vision,
                contextTokens = m.contextTokens ?: base.contextTokens,
                reasoning = m.reasoning ?: base.reasoning,
                // 写真に示されている位置決めの精度は、実際の測定に依存します。新しく接続したものは検証されていません。
                groundingVerified = false,
            ),
            toolCallsKnown = m.toolCalls != null,
            visionKnown = m.vision != null,
            contextKnown = m.contextTokens != null,
            displayName = m.displayName,
            chat = m.chat,
        )
    }.sortedBy { if (it.canBeMain) 0 else 1 }

    /**
     * はメイン モデルとして使用され、トライアル コールの順序として使用されます。メイン モデルとして使用でき、他の目的に使用されない限り ([looksNonChat] を参照)。
     * は当社独自の最初のものです (モデル名または表示名に [familyTokens] という単語が含まれています。[ProviderPreset.familyTokens] を参照)。
     * インターフェイスには、ツールを呼び出すことができることが明確に記載されています。独自の同じファイル内で、モデル名のバージョンを最初に新しいものから押します ([ModelVersion] を参照)
     * 同じバージョンの正式版が最初、読み込みできないバージョンは読み込み可能なバージョンの後に表示されます）、残りはリストの順に表示されます。
     * サービス プロバイダー リストの順序は通常、古いモデルが最初になります。古いモデルはオフラインになっている可能性があります。他のモデルには独自のバージョン番号があり、一緒に比較されません。
     */
    fun candidates(options: List<ModelOption>, familyTokens: List<String> = emptyList(), allowChineseModels: Boolean = false): List<String> =
        options.filter { it.canBeMain && !looksNonChat(it) && automaticAllowed(it, allowChineseModels) }
            .map { Triple(it, inFamily(it, familyTokens), ModelVersion.of(it.id)) }
            .sortedWith(
                compareBy<Triple<ModelOption, Boolean, ModelVersion>>({ if (it.second) 0 else 1 }, { if (it.first.toolCallsKnown) 0 else 1 })
                    .thenComparator { a, b -> if (a.second) ModelVersion.NEWEST_FIRST.compare(a.third, b.third) else 0 },
            )
            .map { it.first.id }

    /** 自動選択できる候補の先頭。候補がなければ用途違い・ツール非対応のモデルへ戻らない。 */
    fun defaultMain(options: List<ModelOption>, familyTokens: List<String> = emptyList(), allowChineseModels: Boolean = false): String? =
        candidates(options, familyTokens, allowChineseModels).firstOrNull()

    /** 補助の自動選択でも同じ制約を守る。地域・用途・ツール対応の判定を緩めない。手動選択は対象外。 */
    fun automaticFallback(options: List<ModelOption>, familyTokens: List<String> = emptyList(), allowChineseModels: Boolean = false): String? =
        candidates(options, familyTokens, allowChineseModels).firstOrNull()

    /** 接続先の明示選択だけが除外を解除する。familyTokensはモデルの並べ替えにだけ使う。 */
    private fun automaticAllowed(option: ModelOption, allowChineseModels: Boolean): Boolean =
        allowChineseModels || !isKnownChineseModel(option.id) && !isKnownChineseModel(option.displayName)

    private fun isKnownChineseModel(name: String): Boolean {
        val normalized = name.trim()
        return CHINA_MODEL.containsMatchIn(normalized) || CHINA_NAMESPACE.containsMatchIn(normalized) || CHINA_VERSIONED.containsMatchIn(normalized)
    }

    private val CHINA_MODEL = Regex(
        "(?:^|[/._:\\s-])(?:deepseek|qwen|qwq|qvq|chatglm|glm|zhipu|kimi|moonshot|minimax|stepfun|hunyuan|doubao|ernie|baichuan|ling|antdigital|antgroup|longcat|internlm|internvl)(?=$|[/._:\\s-]|[0-9])|(?:^|/)step-[0-9]",
        RegexOption.IGNORE_CASE,
    )
    /** 中継サービスで使われる既知の企業namespace。一般語の部分一致では判定しない。 */
    private val CHINA_NAMESPACE = Regex("^(?:bytedance|bytedance-seed|xiaomi|alibaba|inclusionai|kwaipilot|01-ai)/", RegexOption.IGNORE_CASE)
    /** namespaceのない別名は、先頭の系列名とバージョン番号が揃う場合だけ扱う。 */
    private val CHINA_VERSIONED = Regex("^(?:seed|mimo|tongyi|ring|kat|yi)[-_\\s]+v?[0-9](?:[.0-9a-z_-]|$)", RegexOption.IGNORE_CASE)

    /** アドレスが一致するプリセット独自のモデルワード。アドレスが認識されない場合は空になります。 */
    fun familyOf(baseUrl: String): List<String> = ProviderPresets.forBaseUrl(baseUrl.trim())?.familyTokens.orEmpty()

    /** 保存済みの明示接続でも、追加時と同じ自己ホスト・地域の方針を使う。 */
    fun allowsChineseModels(provider: Provider): Boolean =
        provider.explicitSelfHosted || ProviderPresets.allowsChineseModels(provider.baseUrl)

    private fun inFamily(o: ModelOption, tokens: List<String>): Boolean =
        tokens.any { t -> o.id.contains(t, ignoreCase = true) || o.displayName.contains(t, ignoreCase = true) }

    /**
     * は会話モデルのようには見えません (ベクトル、再配置、音声、描画、レビュー...): インターフェイスとしてインターフェイスについて言及されていますが、モデル名が句読点とスラッシュで区切られたセグメントに分割されていることについては言及されていません。
     * に [NON_CHAT_WORDS] の単語で始まる段落がある場合でも。
     */
    fun looksNonChat(o: ModelOption): Boolean =
        o.chat?.not() ?: o.id.lowercase().split(Regex("[^a-z0-9]+")).any { part -> NON_CHAT_WORDS.any { part.startsWith(it) } }

    private val NON_CHAT_WORDS = listOf(
        "embed", "rerank", "tts", "whisper", "transcribe", "speech", "audio", "realtime", "image", "dall", "moderation", "ocr", "asr",
    )

    /** コンテキスト長の短縮形: 64K、1M。 */
    fun contextLabel(tokens: Int): String = when {
        tokens >= 1_000_000 && tokens % 1_000_000 < 50_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1000 -> if (tokens % 1024 == 0) "${tokens / 1024}K" else "${(tokens + 500) / 1000}K"
        else -> "$tokens"
    }

    /**
     * モデルを使用して作業するときに、そのモデルを使用して画像を表示できるかどうか: ユーザーがこのモデルに関して言ったこと ([userSaid]) が優先されます。言われていない場合は、写真を表示するときにも使用できます ([usedForVision]:
     * (写真を閲覧するためのモデルとして選択するか、写真を閲覧するために「メイン モデルを使用する」) は、ユーザーが閲覧可能であると述べたことを意味します。利用できない場合は、[stored]をクリックしてください（リストに記載されているもの、記載されていないものはデフォルトの初期値です）。
     */
    fun seesImages(stored: Boolean, userSaid: Boolean?, usedForVision: Boolean): Boolean = userSaid ?: (usedForVision || stored)

    /** サービス プロバイダーの機能の初期値: アドレスがデフォルトと一致する場合はデフォルトを使用し、それ以外の場合は「カスタム」を使用します。 */
    fun baseOf(p: Provider): Capabilities = (ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.custom).capabilities

    /**
     * サービス プロバイダーで利用可能なすべてのモデル: 記憶されたリスト (機能のマージされた初期値。既に構成されている場合は、構成内の機能を使用します)、
     * と、設定されているがリストにないモデル (手動で入力されたモデルなど)、および手動で入力されたがまだ設定されていないモデル ([Provider.typedModels])。
     * ユーザーは、写真 ([Provider.visionOverrides]) を閲覧できるかどうかはユーザーの発言次第であると述べました。
     */
    fun ofProvider(p: Provider, configs: List<ProviderConfig>): List<ModelOption> = listedOf(p, configs).map { o ->
        val said = p.visionOverrides[o.id] ?: return@map o
        o.copy(capabilities = o.capabilities.copy(vision = said), visionByUser = said)
    }

    private fun listedOf(p: Provider, configs: List<ProviderConfig>): List<ModelOption> {
        val saved = configs.filter { it.providerId == p.id }
        val byModel = saved.associateBy { it.model }
        val listed = merge(p.models.orEmpty(), baseOf(p)).map { o -> byModel[o.id]?.let { o.copy(capabilities = it.capabilities) } ?: o }
        val rest = saved.filter { c -> listed.none { it.id == c.model } }.map {
            // モデルがリストにない場合、モデルは手動で入力されます。リストがある場合、後でリストが削除された場合、モデルはカウントされません。
            val typed = it.model in p.typedModels || p.models?.isEmpty() == true
            ModelOption(it.model, it.capabilities, toolCallsKnown = false, visionKnown = false, contextKnown = false, typed = typed)
        }
        // は手動で入力されており、いかなる目的にも使用されていません (構成なし)。通常どおりリストされており、能力はこの会社の初期値に基づいています。
        val typed = p.typedModels.filter { m -> listed.none { it.id == m } && rest.none { it.id == m } }.sorted().map {
            ModelOption(it, baseOf(p), toolCallsKnown = false, visionKnown = false, contextKnown = false, typed = true)
        }
        return listed + rest + typed
    }
}

/**
 * モデル名から読み取られるバージョンは、同じ会社のモデルをランク付けするためにのみ使用され、どれ（フラッシュ、プロ、ライト...）が優れているかを判断するものではありません。
 * モデル名は、`-` `_` `:` `/` (小文字、小数点は保持) に従って等しいセグメントに切り分けられます。
 * - [numbers] はバージョン番号のような最初のセグメントです: 1 ～ 2 の数字、小数点付き (`2.5`、`3.1`)、前に文字 (`qwen3`、`v3.2`、`k2`) `x4`)、
 *   の後に文字を続けることができます (`4o`、`1v`)。 b / k / m で終わるものはパラメータの量またはコンテキストの長さ (`72b`、`8k`) であり、カウントされません。
 *   の直後にある 1 ～ 2 桁の純粋な数値フィールドは、小さなバージョン (`claude-3-5` → 3.5、`seed-1-6` → 1.6) に接続されます。
 * - [date] は、残りの純粋な数値フィールドで構成される日付またはスナップショット番号です。年、月、日を含む 4 桁の年 (`-2025-08-07`、`-09-2025`)、
 *   それ以外の場合は、書かれた番号 (`-0827`、`-20241022`、`-001`。6 桁のものは 2 桁の年を持つ日付とみなされます)。そうでない場合は null です。
 * - [stage]: 0 正式版; 1 `latest` のような変化するエイリアス; 2 プレビュー、試験（`preview`、`exp`、`beta`、`alpha`、`rc`…）。
 * バージョン番号も日付もないものは読み取れない（[parsed] が偽）。
 */
internal data class ModelVersion(val numbers: List<Int>, val stage: Int, val date: Long?) {
    val parsed: Boolean get() = numbers.isNotEmpty() || date != null

    companion object {
        /** 読み取れるものが前; バージョンが大きいものが前; 同じバージョンでは正式版、エイリアス、プレビューの順; 日付のないもの（多くは最新のスナップショットを指す）が前、次に日付が新しいものが前。 */
        val NEWEST_FIRST: Comparator<ModelVersion> = Comparator { a, b ->
            when {
                a.parsed != b.parsed -> if (a.parsed) -1 else 1
                !a.parsed -> 0
                else -> compareNumbers(b.numbers, a.numbers).takeIf { it != 0 }
                    ?: a.stage.compareTo(b.stage).takeIf { it != 0 }
                    ?: compareValues(b.date ?: Long.MAX_VALUE, a.date ?: Long.MAX_VALUE)
            }
        }

        fun of(id: String): ModelVersion {
            val parts = id.lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }
            var numbers = emptyList<Int>()
            var at = -1
            for ((i, p) in parts.withIndex()) {
                val m = VERSION.matchEntire(p) ?: continue
                if (m.groupValues[3] in SIZE_SUFFIXES) continue
                numbers = m.groupValues[2].split('.').map { it.toInt() }
                at = i
                break
            }
            var end = at + 1
            if (at >= 0 && numbers.size == 1) {
                // ハイフンで区切られた小さなバージョン: claude-3-5、seed-1-6
                while (end < parts.size && numbers.size < 3 && MINOR.matches(parts[end])) numbers = numbers + parts[end++].toInt()
            }
            val rest = parts.filterIndexed { i, _ -> i < at || i >= end }
            val stage = when {
                rest.any { p -> PRE_RELEASE.any { p.startsWith(it) } } -> 2
                "latest" in rest -> 1
                else -> 0
            }
            return ModelVersion(numbers, stage, dateOf(rest.filter { p -> p.all { it.isDigit() } }))
        }

        private fun dateOf(digits: List<String>): Long? {
            if (digits.isEmpty()) return null
            val year = digits.firstOrNull { it.length == 4 && it.toInt() in 1990..2099 }
            if (year != null) {
                val md = (digits - year).take(2).map { it.take(2).toLong() }
                return year.toLong() * 10_000 + (md.getOrNull(0) ?: 0) * 100 + (md.getOrNull(1) ?: 0)
            }
            val joined = digits.joinToString("").take(14)
            return if (digits.size == 1 && joined.length == 6) 20_000_000 + joined.toLong() else joined.toLong()
        }

        private fun compareNumbers(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return 0
        }

        private val VERSION = Regex("([a-z]*)((?:0|[1-9]\\d?)(?:\\.\\d{1,3})*)([a-z]?)")
        private val MINOR = Regex("[1-9]?\\d")
        private val SIZE_SUFFIXES = setOf("b", "k", "m")
        private val PRE_RELEASE = listOf("preview", "exp", "beta", "alpha", "rc", "dev", "test")
    }
}

/** サービス プロバイダーのグループ化ルールと表示名: 同じインターフェイス タイプ、同じアドレス、および同じ名前は、同じサービス プロバイダーとしてカウントされます。 */
object ProviderConnection {
    fun keyOf(kind: ProviderConfig.Kind, baseUrl: String, name: String) = "${kind.name}|${normalizeUrl(baseUrl)}|${name.trim()}"

    /** は、名前のないアドレスのホスト名を使用します。 */
    fun displayName(c: ProviderConfig): String = c.name.ifBlank { nameForUrl(c.baseUrl) }

    fun nameForUrl(baseUrl: String): String =
        ProviderPresets.forBaseUrl(baseUrl)?.displayLabel ?: baseUrl.trim().toHttpUrlOrNull()?.host ?: baseUrl.trim()

    fun normalizeUrl(url: String) = url.trim().trimEnd('/')
}
