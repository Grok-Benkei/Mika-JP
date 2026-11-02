package com.wangzan101.mika.browser

import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * ブラウザ ページ上での Web サイトの表示: 名前 ([name]、読み取れない場合は null) とそのオリジン ([rank]、[SiteNames] を参照)、
 * Web サイトのアイコンが保存された時刻 ([iconAtMs]、アイコンがない場合は null。インターフェイスはアイコン ファイルを再読み込みするかどうかを決定するためにそれを使用します)。
 */
data class SiteLook(val name: String?, val rank: Int, val iconAtMs: Long?)

/** には、名前、名前の最初の文字、背景色 (アイコンがない場合に使用)、アイコン ファイルとそのバージョンなど、インターフェイス上に Web サイトを描画するために必要なものがすべて揃っています。 */
data class SiteFace(val host: String, val name: String, val initial: String, val color: Long, val icon: File?, val iconAtMs: Long)

/**
 * Web サイトの名前とアイコン: ユーザーがブラウザーで Web ページを開いたときに、それを書き留めておきます (名前はページから取得され、アイコンは WebView によって受信された Web サイトのアイコンから取得されます)。「頻繁に使用される Web サイト」および「最近開いた Web サイト」に使用されます。
 * はホスト名に従って記録されます。同時に、登録可能なドメイン名に記録され（他に適切なドメイン名がない場合）、同じ Web サイトの他のサブドメイン名のページも認識できます。
 * 名はアプリのプライベート ディレクトリ内の JSON ファイルに保存され、アイコンはその隣の icons ディレクトリ内の PNG です。ファイルが破損している場合は、空のテーブルからやり直してください。
 */
class SiteLooks(dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val iconDir = File(dir, ICONS)
    private val _entries = MutableStateFlow(load())

    /** ホスト名 → のようです。 */
    val entries: StateFlow<Map<String, SiteLook>> = _entries.asStateFlow()

    /** ページに指定された名前を書き留めます。元の名前が既存のものよりも悪くない場合にのみ変更してください。 */
    @Synchronized
    fun named(host: String, name: String, rank: Int) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        var map = _entries.value
        keys(host).forEachIndexed { i, h ->
            val cur = map[h]
            // ホスト名自体も、同様に信頼できる新しい名前に変更されます。登録可能なドメイン名: 信頼性が高い場合にのみ変更します (他のサブドメイン名のページはホームページの名前を競合しません)
            val replace = cur?.name == null || (if (i == 0) rank >= cur.rank else rank > cur.rank)
            if (replace && cur?.name != n) map = map + (h to SiteLook(n, rank, cur?.iconAtMs))
        }
        if (map != _entries.value) save(map)
    }

    /**
     * アイコンを [iconFile] に書き込む前に、どのホスト名に保存する必要があるかを確認してください。ホスト名自体にはアイコンがないか、[ICON_REFRESH_MS] を超えると保存されます。 アイコンがまだない場合は、
     * 登録可能なドメイン名のコピーも保存されます。ページを開くたびにアイコンが表示されるので、毎回書き直す必要がありません。
     */
    @Synchronized
    fun iconTargets(host: String): List<String> = keys(host).filterIndexed { i, h ->
        val at = _entries.value[h]?.iconAtMs
        at == null || (i == 0 && clock() - at >= ICON_REFRESH_MS)
    }

    /** このホスト名のアイコン ファイル (まだ存在しない可能性があります)。 */
    fun iconFile(host: String): File = File(iconDir, "${host.lowercase()}.png")

    /** アイコンが [iconFile] に書き込まれました。 */
    @Synchronized
    fun iconSaved(host: String) {
        val h = host.lowercase()
        val cur = _entries.value[h]
        save(_entries.value + (h to SiteLook(cur?.name, cur?.rank ?: 0, clock())))
    }

    /** この Web サイトがどのように見えるかを描きます。まずホスト名自体を見つけてから、登録可能なドメイン名を見つけます。存在しない場合は、ホスト名に従って名前と背景色をプッシュします。 */
    fun face(host: String): SiteFace = faceOf(host, _entries.value, ::iconFile)

    @Synchronized
    fun clear() {
        iconDir.listFiles()?.forEach { it.delete() }
        save(emptyMap())
    }

    private fun load(): Map<String, SiteLook> {
        if (!file.isFile) return emptyMap()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonObject)?.mapNotNull { (h, e) ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val rank = (o["rank"] as? JsonPrimitive)?.intOrNull ?: 0
                // 他のページのタイトルに基づく名前は間違っている可能性があります (「サイト名」を参照)。それを捨てて、ホームページまたは宣言された Web サイトの名前が変更されるまで待ちます。
                val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { rank >= SiteNames.RANK_HOME_TITLE }
                h to SiteLook(name, if (name == null) 0 else rank, (o["iconAt"] as? JsonPrimitive)?.longOrNull)
            }?.toMap().orEmpty()
        } catch (e: SerializationException) {
            emptyMap()
        }
    }

    private fun save(map: Map<String, SiteLook>) {
        _entries.value = map
        file.parentFile?.mkdirs()
        val json = JsonObject(map.mapValues { (_, l) ->
            buildJsonObject {
                l.name?.let { put("name", it) }
                put("rank", l.rank)
                l.iconAtMs?.let { put("iconAt", it) }
            }
        }).toString()
        // 最初に一時ファイルを書き込んでから、名前を変更します。書き込みの途中で強制終了すると、ファイルの半分も残りません。
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        private const val FILE = "browser_sites.json"
        private const val ICONS = "browser_icons"
        /** 同じホスト名のアイコンは、非常に長い時間が経過した後 (Web サイトのアイコンが変更された場合) にのみ再保存されます。 */
        const val ICON_REFRESH_MS = 24L * 60 * 60 * 1000

        /** ホスト名自体とその登録可能なドメイン名 (同時には不可)。 */
        fun keys(host: String): List<String> {
            val h = host.trim().lowercase().removePrefix("www.")
            if (h.isEmpty()) return emptyList()
            val site = WebUrlPolicy.registrable(h)
            return if (site == null || site == h) listOf(h) else listOf(h, site)
        }

        fun faceOf(host: String, looks: Map<String, SiteLook>, iconFile: (String) -> File?): SiteFace {
            val found = keys(host).mapNotNull { looks[it] }
            val name = found.firstNotNullOfOrNull { it.name } ?: SiteNames.fallback(host)
            val iconKey = keys(host).firstOrNull { looks[it]?.iconAtMs != null }
            return SiteFace(
                host, name, SiteNames.initial(name), SiteTiles.color(host),
                iconKey?.let(iconFile), iconKey?.let { looks[it]?.iconAtMs } ?: 0L,
            )
        }
    }
}

/**
 * Web サイトの名前はどこから来たのか、より信頼性が高くなります。 [rank] 大きいほど、ページ上で宣言されている Web サイト名 (og:site_name、application-name) が最も信頼できます。
 * ホームページのタイトルは 2 番目になります (ほとんどの場合、Web サイト名から始まります)。他のページのタイトルは名前付けに使用されません。どの段落が Web サイト名であるか、ログインやエラーなどのページのタイトルが不明です。
 * の最後の段落は、多くの場合、「統合ログイン」などのページ名であり、Web サイトの名前が間違った名前に変更されます。存在しない場合は、ホスト名を使用して推奨します。
 */
object SiteNames {
    const val RANK_DECLARED = 3
    /** その下に由来する名前 (別のページのタイトルに従って以前に記録された) は使用されなくなり、読み込まれたときに破棄されます ([SiteLooks] を参照)。 */
    const val RANK_HOME_TITLE = 2
    /** 名前が長すぎると、非常に多くの文字が切り取られます (1 つのグリッドに収まりきらない)。 */
    const val MAX_LEN = 12

    /** タイトル内の Web サイト名とページ名の間の区切りとしてよく使用されるのは、両側にスペースを含む水平線、コロンまたは垂直線、下線、スペーサーです。 */
    private val SEPARATORS = Regex("""\s+[-–—:：]\s+|\s*[|｜_·•]\s*""")

    /** ページに表示されている内容から名前とその由来を選択します。宣言された Web サイト名、またはホームページ タイトルの最初の段落。選択されていない場合は null。 */
    fun pick(declared: String?, title: String?, homePage: Boolean): Pair<String, Int>? {
        clean(declared)?.let { return it to RANK_DECLARED }
        if (!homePage) return null
        val first = title?.split(SEPARATORS)?.firstNotNullOfOrNull(::clean) ?: return null
        return first to RANK_HOME_TITLE
    }

    /** は、ページのメイン フレームにある Web サイト名とタイトルを読み取る読み取り専用スクリプトです。結果は、{site, title} の JSON です (evaluateJavascript を通じて文字列のレイヤーでラップされています)。 */
    val SCRIPT = """(function(){try{function m(s){var e=document.querySelector(s);return e&&e.content?String(e.content):'';}
return JSON.stringify({site:m('meta[property="og:site_name"]')||m('meta[name="application-name"]')||m('meta[name="apple-mobile-web-app-title"]'),title:String(document.title||'')});}catch(x){return null;}})()"""

    /** [SCRIPT] の解析結果: (宣言された Web サイト名、タイトル);読み取られない場合は null です。 */
    fun parse(raw: String?): Pair<String?, String?>? {
        val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        return str("site") to str("title")
    }

    /** ホームページは、パスが空または / の場合に計算されます。 */
    fun isHomePage(url: String): Boolean {
        val path = runCatching { java.net.URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return path.isEmpty() || path == "/"
    }

    /** 名前が読み取れない場合: ドメイン名を登録し、サフィックスを削除することができます (shop.example.com → example)。 IPなどは変更されません。 */
    fun fallback(host: String): String {
        val h = host.trim().lowercase().removePrefix("www.")
        if ('.' !in h || h.startsWith("[") || h.all { it.isDigit() || it == '.' }) return h
        WebUrlPolicy.registrable(h)?.let { return it.substringBefore('.') }
        return h.substringBeforeLast('.')
    }

    /** 名前の最初の文字 (スペイン語の大文字)。 */
    fun initial(name: String): String {
        val cp = name.trim().codePointAt0() ?: return "?"
        return String(Character.toChars(cp)).uppercase()
    }

    private fun String.codePointAt0(): Int? = if (isEmpty()) null else codePointAt(0)

    private fun clean(s: String?): String? {
        val t = s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.length > MAX_LEN) t.take(MAX_LEN).trimEnd() else t
    }
}

/** アイコンがない場合の Web サイトボックスの背景色: ホスト名 (www を除く) に応じて選択すると、同じ Web サイトが毎回同じになります。 */
object SiteTiles {
    /** は、濃い色でも薄い色でも数色の白文字(ARGB)を押さえることができます。 */
    val PALETTE = listOf(
        0xFFE5533D, 0xFFF08A24, 0xFFC9A100, 0xFF2E9E5B, 0xFF16A0A0,
        0xFF2F7DE1, 0xFF5B5BD6, 0xFF9A4FD0, 0xFFD6457F, 0xFF6B7280,
    )

    fun color(host: String): Long {
        val key = host.trim().lowercase().removePrefix("www.")
        // FNV-1a: 類似したホスト名を分離できますが、結果は毎回同じになります。
        var h = 0x811C9DC5.toInt()
        for (ch in key) h = (h xor ch.code) * 0x01000193
        return PALETTE[Math.floorMod(h, PALETTE.size)]
    }
}
