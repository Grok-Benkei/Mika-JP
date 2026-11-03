package com.wangzan101.mika.i18n

import com.wangzan101.mika.R
import org.junit.rules.ExternalResource
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM 単一テストのインターフェイス テキスト: `src/main/res/<folder>/` の下のリソース ファイルを直接読み取り、Android ルールに従ってエスケープを削除します。
 * テキストを取得するためだけに Android 環境をセットアップする必要はありません。 [folder] は、`values` (英語)、`values-zh` (簡体字中国語)、または `values-b+zh+Hant` (繁体字中国語) です。
 */
class ResFileTexts(folder: String, private val locale: Locale) : UiTexts {
    val strings: Map<String, String>
    val plurals: Map<String, Map<String, String>>

    init {
        val s = mutableMapOf<String, String>()
        val p = mutableMapOf<String, Map<String, String>>()
        resFiles(folder).forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> s[name] = unescape(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        p[name] = (0 until items.length).associate { j ->
                            val item = items.item(j) as Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                    }
                }
            }
        }
        strings = s
        plurals = p
    }

    override fun string(id: Int, args: Array<out Any?>): String {
        val name = stringNames[id] ?: error("no R.string with id $id")
        val raw = strings[name] ?: fallback?.strings?.get(name) ?: error("no string $name")
        return if (args.isEmpty()) raw else String.format(locale, raw, *args)
    }

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
        val name = pluralNames[id] ?: error("no R.plurals with id $id")
        val forms = plurals[name] ?: error("no plurals $name")
        // 英語: 1 は 1 を使用します。中国語はその他のみを使用します
        val raw = (if (count == 1) forms["one"] else null) ?: forms.getValue("other")
        return String.format(locale, raw, *(if (args.isEmpty()) arrayOf(count) else args))
    }

    /** 未翻訳の単語 (translatable="false") はデフォルト ファイルにのみ存在します。 */
    private val fallback: ResFileTexts? = if (folder == "values") null else japanese

    companion object {
        /** 既定（フォールバック）リソース。日本語専用フォークでは日本語。 */
        val japanese: ResFileTexts by lazy { ResFileTexts("values", Locale.JAPANESE) }
        /** ja 修飾子のリソース。既定と同一内容であることをテストで確認する。 */
        val japaneseQualified: ResFileTexts by lazy { ResFileTexts("values-ja", Locale.JAPANESE) }

        fun resFiles(folder: String): List<File> =
            (File("src/main/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") } ?: emptyArray())
                .sortedBy { it.name }

        private fun names(cls: Class<*>): Map<Int, String> = cls.fields.associate { it.getInt(null) to it.name }
        private val stringNames by lazy { names(R.string::class.java) }
        private val pluralNames by lazy { names(R.plurals::class.java) }

        /** Android リソース テキストのエスケープ: 引用符内の空白はそのままにし、引用符の外側の連続空白を 1 つにマージします。バックスラッシュをエスケープします。 */
        fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var i = 0
            var space = false
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        val n = raw[i + 1]
                        out.append(
                            when (n) {
                                'n' -> '\n'
                                't' -> '\t'
                                'u' -> raw.substring(i + 2, i + 6).toInt(16).toChar().also { i += 4 }
                                else -> n
                            },
                        )
                        i += 2
                        space = false
                        continue
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c.isWhitespace() -> {
                        if (!space) out.append(' ')
                        space = true
                        i++
                        continue
                    }
                    else -> out.append(c)
                }
                space = false
                i++
            }
            return if (raw.trim().startsWith('"')) out.toString() else out.toString().trim(' ')
        }
    }
}

/** テストで界面文字を日本語（既定リソース）に固定する。 */
class JapaneseTexts : ExternalResource() {
    private var previous: UiTexts? = null
    override fun before() {
        previous = UiText.source
        UiText.source = ResFileTexts.japanese
    }

    override fun after() {
        UiText.source = previous
    }
}

/** 旧テストからの移行用エイリアス。実体は日本語。 */
typealias ChineseTexts = JapaneseTexts

/** 旧テストからの移行用エイリアス。実体は日本語。 */
typealias EnglishTexts = JapaneseTexts
