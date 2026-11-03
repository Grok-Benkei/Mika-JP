package com.wangzan101.mika.browser

import com.wangzan101.mika.cap.web.SiteEntry
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 「よく使う Web サイト」のボックス: 開いたときのホスト名と、Mika にログインしているかどうか (この Web サイトのどのホスト名がログインしているかは関係ありません)。 */
data class FavoriteSite(val host: String, val loggedIn: Boolean)

/** 「よく使用する Web サイト」に対するユーザーの変更: 自分で追加したもの ([pinned]、追加順) とそこから削除したもの ([hidden])。 */
data class FavoriteEdits(val pinned: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/**
 * ブラウザ ページ「頻繁に使用される Web サイト」: ユーザーが追加した Web サイトが最初で、次にサイト登録テーブル内の Web サイト (エージェントとユーザーが訪問した Web サイト、最初にログインした Web サイト) が続きます。
 * 同じ Web サイト (同じ登録可能なドメイン名) は 1 つのスペースのみを占有します。開くときは、その中で最も短いホスト名に移動します。
 *
 * はユーザーの変更のみを記憶し、サイト登録フォームは変更しません。お気に入りからは削除されますが、表示されません。ログインステータス、Cookie、エージェントの登録は変わりません。
 * (設定ページの「Web サイトログイン」でログアウトします);再度追加すると再び表示されます。
 * は、アプリのプライベート ディレクトリ内の JSON ファイルに保存されます。ファイルが破損している場合は、空のテーブルからやり直してください。
 */
class BrowserFavorites(dir: File) {
    private val file = File(dir, FILE)
    private val _edits = MutableStateFlow(load())

    val edits: StateFlow<FavoriteEdits> = _edits.asStateFlow()

    /** 一般的に使用される追加: 独自の追加の最後に置きます。以前に削除された Web サイトが再表示されます。 */
    @Synchronized
    fun add(host: String) {
        val h = norm(host) ?: return
        val site = siteKey(h)
        val e = _edits.value
        save(FavoriteEdits(e.pinned.filter { siteKey(it) != site } + h, e.hidden - site))
    }

    /** 共用から削除：自分で追加したものを削除すると、登録リストから削除されます。 */
    @Synchronized
    fun remove(host: String) {
        val h = norm(host) ?: return
        val site = siteKey(h)
        val e = _edits.value
        save(FavoriteEdits(e.pinned.filter { siteKey(it) != site }, e.hidden + site))
    }

    private fun load(): FavoriteEdits {
        if (!file.isFile) return FavoriteEdits()
        return try {
            val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return FavoriteEdits()
            fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::norm) }
            FavoriteEdits(list("pinned").distinct(), list("hidden").toSet())
        } catch (e: SerializationException) {
            FavoriteEdits()
        }
    }

    private fun save(e: FavoriteEdits) {
        _edits.value = e
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("pinned", JsonArray(e.pinned.map(::JsonPrimitive)))
            put("hidden", JsonArray(e.hidden.sorted().map(::JsonPrimitive)))
        }.toString()
        // 最初に一時ファイルを書き込んでから、名前を変更します。書き込みの途中で強制終了すると、ファイルの半分も残りません。
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        /** グリッドには、最大でこの数のグリッド サイトがあります (さらにもう 1 つのグリッド「+」があるため、4 つの行はちょうど 3 行になります)。 */
        const val LIMIT = 11
        private const val FILE = "browser_favorites.json"

        private fun norm(host: String): String? {
            val s = host.trim()
            if (s.isEmpty()) return null
            return WebUrlPolicy.host(if ("://" in s) s else "https://$s")
        }

        /** 同じ Web サイトのマーク: ホスト名そのものではなく、ドメイン名を登録できます。 */
        fun siteKey(host: String): String = WebUrlPolicy.registrable(host) ?: host

        /** 合成グリッド: 最初に追加したものを (結合の順序で) 追加し、その後、登録フォームの順序に従います。同じ Web サイトにはグリッドが 1 つあり、削除されたグリッドは [limit] グリッドまで表示されません。 */
        fun combine(edits: FavoriteEdits, registry: List<SiteEntry>, limit: Int = LIMIT): List<FavoriteSite> {
            val bySite = registry.groupBy { siteKey(it.host) }
            fun loggedIn(site: String) = bySite[site].orEmpty().any { it.loggedInByUser }
            val out = LinkedHashMap<String, FavoriteSite>()
            for (h in edits.pinned) {
                val site = siteKey(h)
                if (site !in out) out[site] = FavoriteSite(h, loggedIn(site))
            }
            for (e in registry) {
                val site = siteKey(e.host)
                if (site in out || site in edits.hidden) continue
                val host = bySite[site].orEmpty().minBy { it.host.length }.host
                out[site] = FavoriteSite(host, loggedIn(site))
            }
            return out.values.take(limit)
        }

        /** [host] がミカでログインしている Web サイトです (登録テーブル内のこの Web サイトの任意のホスト名がログインしています)。 */
        fun loggedIn(registry: List<SiteEntry>, host: String): Boolean {
            val site = siteKey(host)
            return registry.any { it.loggedInByUser && siteKey(it.host) == site }
        }

        /** [host] がある Web サイトは [sites] にありますか? */
        fun contains(sites: List<FavoriteSite>, host: String): Boolean {
            val site = siteKey(host)
            return sites.any { siteKey(it.host) == site }
        }
    }
}
