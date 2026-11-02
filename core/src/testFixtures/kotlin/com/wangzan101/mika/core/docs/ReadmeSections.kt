package com.wangzan101.mika.core.docs

import com.wangzan101.mika.core.knowledge.IndexEntry
import com.wangzan101.mika.core.knowledge.KnowledgeIndex
import java.io.File

/**
 * README の変更リスト (組み込みナレッジ、サービス プロバイダーのプリセット) はソースから生成され、`<!-- generated:<名>:start -->` と `<!-- generated:<名>:end -->` の間に挟まれています。
 * 正本のREADME.mdと、旧パスを保持したdocs/readme/README.zh-CN.mdに、同じ日本語の内容を生成する。
 * `./gradlew updateReadme` これら 2 つの README を書き換えてください。コアの `ReadmeSectionsTest` を使用して、生成された結果との一貫性を保ちます。他の翻訳には段落が生成されません。
 *
 * ナレッジ リストはインデックスの構築に使用したのと同じ [KnowledgeIndex.build] を使用し、順序はインデックスの順序になります。サービスプロバイダーはアプリの`ProviderPresets.kt`にあらかじめ設定されており、
 * coreはAndroidモジュールのクラスをロードできないため、ProviderPresetの宣言をソースから読み取る。
 * アプリの `ReadmePresetsTest` は、実際のオブジェクト `ProviderPresets.all` と比較されます。読み取りメソッドがソース コードに追いつかない場合、読み取りは失敗します。
 */
object ReadmeSections {
    /** 日本語フォークの正本となるREADME。 */
    const val README = "README.md"
    const val README_ZH = "docs/readme/README.zh-CN.md"
    const val KNOWLEDGE = "knowledge"
    const val PROVIDERS = "providers"
    const val RESOURCES = "core/src/main/resources"
    const val PRESETS_SOURCE = "app/src/main/java/com/wangzan101/mika/settings/ProviderPresets.kt"
    private const val NOTE = "<!-- ./gradlew updateReadme で生成します。手作業で編集しないでください -->"
    private const val NOTE_EN = NOTE

    fun start(name: String) = "<!-- generated:$name:start -->"
    fun end(name: String) = "<!-- generated:$name:end -->"

    /** はソース コードのデフォルトです。 [kind] は `ProviderConfig.Kind` の列挙名で、「カスタマイズ」するには [baseUrl] は空です。 */
    data class Preset(val label: String, val kind: String, val baseUrl: String)

    private val PRESET = Regex("""ProviderPreset\(\s*"([^"]+)"\s*,\s*ProviderConfig\.Kind\.(\w+)\s*,\s*"([^"]*)"""")

    fun presets(source: String): List<Preset> =
        PRESET.findAll(source).map { Preset(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()

    private fun kindName(kind: String) = when (kind) { "OpenAiCompat" -> "OpenAI互換"; "OpenAiResponses" -> "OpenAI Responses"; else -> kind }
    private fun kindNameEn(kind: String) = when (kind) { "OpenAiCompat" -> "OpenAI互換"; "OpenAiResponses" -> "OpenAI Responses"; else -> kind }

    /** 「カスタム」で選べるAPIの種類。プリセットに含まれる種類を使い、Responsesを最後にする。画面の順序（`ProviderConfig.Kind.withApiKey`）と一致させる。 */
    private fun customKinds(presets: List<Preset>) = presets.map { it.kind }.distinct().sortedBy { it == "OpenAiResponses" }

    /** 候補を読点で区切って列挙する。 */
    private fun orList(items: List<String>) =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + "、" + items.last()

    fun knowledge(entries: List<IndexEntry>): String {
        fun names(kind: String) = entries.filter { it.kind == kind }.joinToString("、") { it.display }
        return listOf(
            NOTE,
            "| 種類 | 内蔵 |",
            "|---|---|",
            "| **アプリ** | ${names(KnowledgeIndex.APP)} |",
            "| **ウェブサイト** | ${names(KnowledgeIndex.SITE)} |",
            "| **スキル** | ${names(KnowledgeIndex.SKILL)} |",
        ).joinToString("\n")
    }

    fun knowledgeEn(entries: List<IndexEntry>): String = knowledge(entries)

    /** 利用できるプリセット（接続先が空の「カスタム」を除く）と、カスタムで使えるAPIの種類を1文で示す。[customKinds]を参照。 */
    fun providers(presets: List<Preset>): String {
        val names = presets.filter { it.baseUrl.isNotEmpty() }.joinToString("、") { it.label }
        val kinds = customKinds(presets).joinToString("、") { kindName(it) }
        return listOf(NOTE, "$names などのプロバイダーを内蔵しています。その他は「カスタム」で接続できます。対応API: $kinds。").joinToString("\n")
    }

    /** 旧英語版APIとの互換用。現在は[providers]と同じ日本語を返す。 */
    fun providersEn(presets: List<Preset>): String = providers(presets)

    /** リポジトリのルート[root]から生成する各節の内容。言語引数は互換用に保持し、どちらのREADMEにも日本語を返す。 */
    @Suppress("UNUSED_PARAMETER")
    fun sections(root: File, english: Boolean = true, japanese: Boolean = true): Map<String, String> {
        val index = KnowledgeIndex.build(File(root, RESOURCES))
        check(index.errors.isEmpty()) { "内蔵ナレッジの検証に失敗しました:\n" + index.errors.joinToString("\n") }
        val presetSource = File(root, PRESETS_SOURCE).readText(Charsets.UTF_8)
        val presets = presets(presetSource)
        check(presets.isNotEmpty()) { "$PRESETS_SOURCE にProviderPresetが見つかりません" }
        run {
            fun names(kind: String) = index.entries.filter { it.kind == kind }.joinToString("、") { it.display }
            val knowledge = listOf(NOTE_EN, "| 種類 | 内蔵 |", "|---|---|",
                "| アプリ | ${names(KnowledgeIndex.APP)} |", "| ウェブサイト | ${names(KnowledgeIndex.SITE)} |",
                "| スキル | ${names(KnowledgeIndex.SKILL)} |").joinToString("\n")
            val labels = Regex("\"([^\"]+)\"\\s*->\\s*\"([^\"]+)\"").findAll(presetSource)
                .associate { it.groupValues[1] to it.groupValues[2] }
            val providers = listOf(NOTE_EN, presets.filter { it.baseUrl.isNotEmpty() }
                .joinToString("、") { labels[it.label] ?: it.label } + "を内蔵しています。その他は「カスタム」で接続できます。中国本土向けは「他のリージョン」に表示します。")
                .joinToString("\n")
            return mapOf(KNOWLEDGE to knowledge, PROVIDERS to providers)
        }

    }

    /** は、[readme] の各マークを [sections] の内容に置き換えます。マークがないか、順序が間違っていると、直接失敗します。 */
    fun render(readme: String, sections: Map<String, String>): String =
        sections.entries.fold(readme) { text, (name, body) ->
            val s = text.indexOf(start(name))
            val e = text.indexOf(end(name))
            check(s >= 0 && e > s) { "READMEに必要なマーカーがありません: ${start(name)} … ${end(name)}" }
            text.substring(0, s + start(name).length) + "\n" + body + "\n" + text.substring(e)
        }

    /** `updateReadme`: `<仓库根目录>` への入り口。 */
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        listOf(README to true, README_ZH to false).forEach { (name, english) ->
            val file = File(root, name)
            val before = file.readText(Charsets.UTF_8)
            val after = render(before, sections(root, english, japanese = true))
            if (after != before) file.writeText(after, Charsets.UTF_8)
            println(if (after != before) "$name を更新しました" else "$name の更新は不要です")
        }
    }
}
