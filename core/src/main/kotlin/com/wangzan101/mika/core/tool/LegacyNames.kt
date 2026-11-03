package com.wangzan101.mika.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 名前変更前のツール名 → ルールに従って登録されたツールから生成された現在の名前 (手書きの比較表なし):
 * 機能のツール名は `<能力>_<其余>` です。複数の画面に存在する動詞には、以前は接頭辞のない 1 つの名前 (`tap`) しかありませんでしたが、現在は各画面に 1 つあります。
 * には画面が 1 つしかありません。以前の名前は、[formerPrefix] の機能の古いプレフィックスに残りを加えたものでした (携帯電話にはプレフィックス `launch_app` はありません。Web ページは `browser_` です)。
 * の残りの部分も変更され、[renamed] (現在の名前→以前の名前) に書き込まれます。
 * [retired] は削除された古い名前で、代替ツールはありません (古い名前→今後どうするか) をその文に書き換えました。
 * [moved] は上記のルールに従わず、名前全体を変更しました (旧名→現在の名前、1 つが複数に分かれています)。
 *
 * は、保存されたエクスペリエンス ルート、ランニング記録の表示、コンテキストを読み込む個人のスキルと実践、モデルが古い名前を呼び出したときのプロンプトなど、古いものを読み取るためにのみ使用されます。
 * 古い名前を呼び出しているツールは、実行時に新しい名前に自動的に置き換えられません。
 */
class LegacyNames(
    private val index: ToolIndex,
    formerPrefix: Map<String, String>,
    renamed: Map<String, String> = emptyMap(),
    private val retired: Map<String, String> = emptyMap(),
    moved: Map<String, List<String>> = emptyMap(),
) {
    private val table: Map<String, List<String>>

    init {
        val infos = index.names.mapNotNull { index[it] }
        val out = LinkedHashMap<String, MutableList<String>>()
        for (t in infos) {
            // 新しい携帯電話コマンドには、接頭辞のない歴史的な名前がありません。
            if (t.verb in setOf(Verb.Query, Verb.Collect, Verb.PhoneRun, Verb.PhoneFind, Verb.PhoneExtract,
                    Verb.PhoneDialog, Verb.PhoneEvent, Verb.PhoneGesture, Verb.PhoneCommand)) continue
            val cap = t.capability ?: continue
            val prefix = formerPrefix[cap] ?: continue
            if (!t.name.startsWith("${cap}_")) continue
            val rest = t.name.removePrefix("${cap}_")
            // 他の画面にも同じ動詞と同じ休符があります。以前は接頭辞のない一般的な名前でした
            val shared = infos.any { o -> o !== t && o.capability != cap && o.capability in formerPrefix && o.verb == t.verb && o.name == "${o.capability}_$rest" }
            val old = renamed[t.name] ?: if (shared) rest else prefix + rest
            if (old != t.name && old !in index.names) out.getOrPut(old) { mutableListOf() } += t.name
        }
        for ((old, news) in moved) news.filter { it in index.names }.takeIf { it.isNotEmpty() }?.let { out.getOrPut(old) { mutableListOf() } += it }
        table = out
    }

    /** 古い名前は現在の名前に対応します (複数の画面に動詞がいくつあるか)。古い名前は空ではありません。 */
    fun replacements(old: String): List<String> =
        table[old].orEmpty()

    /**
     * モデルが古い名前 [old] を呼び出すときに表示される新しい名前。1 つは知識とスキルの 2 つの部分に分かれています。パラメータの種類（または名前の `kind:` プレフィックス）に応じて、対応するものを選択します。
     * 区別できない場合は、両方を入力してください。 削除され、代替ツールがない古い名前
     */
    fun replacements(old: String, args: JsonObject): List<String> {
        val all = replacements(old)
        if (all.size < 2) return all
        val kind = args.text("kind") ?: args.text("name")?.substringBefore(':', "")
        val verbs = when (kind) {
            "app", "site" -> setOf(Verb.KnowledgeSearch, Verb.KnowledgeRead)
            "skill", "personal" -> setOf(Verb.SkillSearch, Verb.SkillRead)
            else -> return all
        }
        return all.filter { index.verb(it) in verbs }.ifEmpty { all }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** をどうするか。 null なのは古い名前ではありません。 */
    fun retiredAs(old: String): String? = retired[old]

    /** 古い名前が1つずつ登録されています。 */
    val names: Set<String> get() = table.keys

    /** は、[accept] の古い名前の新しい名前です (たとえば、ルートに記録できるもののみ)。古い名前でない場合、または適切な名前がない場合は、null になります。 */
    fun resolve(old: String, accept: (String) -> Boolean = { true }): String? = replacements(old).firstOrNull(accept)

    /**
     * 本文中に記載されている古いツール名は現在のものに置き換えられます。下線付きの古い名前 (`launch_app`、`browser_navigate`) はすべて置き換えられます。
     * 自体は英語です(`tap`、`back`)。バッククォート内の単語を置き換えるだけです。いくつかの画面では「`phone_tap` / `web_tap`」と表記されています。
     */
    fun rewrite(text: String): String = renamedIn(retiredIn(text))

    /**
     * 削除された古い名前を、以前の「通話 / 通話」アクションとともに、これから何をするかを示す文に置き換えます。 バッククォート内のすべてを置き換えます。 バッククォートのない
     * (英語の単語である可能性があります) は、ツール名である場所 (「call/call」の直後、または中国語で挟まれた場合) のみ置換されます。
     */
    private fun retiredIn(text: String): String {
        var out = text
        for ((old, instead) in retired) {
            val name = Regex.escape(old)
            out = Regex("(?:(?i:call)\\s+|调用\\s*)?`$name`").replace(out, instead)
            out = Regex("(?:(?i:call)\\s+|调用\\s*)(?<![A-Za-z0-9_])$name(?![A-Za-z0-9_])").replace(out, instead)
            out = Regex("(?<=[\\u4e00-\\u9fff])\\s*$name(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])$name\\s*(?=[\\u4e00-\\u9fff])").replace(out) { " $instead " }
        }
        return out
    }

    private fun renamedIn(text: String): String {
        if (table.isEmpty()) return text
        fun joined(news: List<String>, tick: Boolean): String {
            fun t(s: String) = if (tick) "`$s`" else s
            return news.joinToString(" / ") { t(it) }
        }
        val ticked = TICKED.replace(text) { m -> replacements(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { joined(it, true) } ?: m.value }
        return WORD.replace(ticked) { m ->
            val w = m.value
            if ('_' !in w) w else replacements(w).takeIf { it.isNotEmpty() }?.let { joined(it, false) } ?: w
        }
    }

    private companion object {
        val TICKED = Regex("`([a-z][a-z0-9_]*)`")
        val WORD = Regex("(?<![A-Za-z0-9_`])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![A-Za-z0-9_`])")
    }
}
