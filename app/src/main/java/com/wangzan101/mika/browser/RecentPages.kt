package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** 最近開いたページ: URL、タイトル (読めない場合は空白)、開始時刻。 */
data class RecentPage(val url: String, val title: String, val openedAtMs: Long)

/**
 * ブラウザ ページ「最近開いた」: ユーザーがミカのブラウザ ページで開いた Web ページ (最新のものから [limit] まで)。
 * 同じURL(#以降を除く)は1つだけ残ります。再度開くと最前面に移動し、タイトルが新しいものに置き換わります。 最初に近いのは
 * は、アプリのプライベート ディレクトリ内の JSON ファイルに保存されます。ファイルが破損している場合は、空のテーブルからやり直してください。
 */
class RecentPages(dir: File, private val limit: Int = LIMIT, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val _entries = MutableStateFlow(load())

    /** です。 */
    val entries: StateFlow<List<RecentPage>> = _entries.asStateFlow()

    @Synchronized
    fun add(url: String, title: String?) {
        val key = key(url)
        if (key.isEmpty()) return
        val page = RecentPage(url.trim(), title?.trim().orEmpty(), clock())
        val rest = _entries.value.filter { key(it.url) != key }
        save((listOf(page) + rest).take(limit))
    }

    /** 必要に応じて、現在のページの後にタイトルを追加します。既存のページのみを変更し、位置は移動しないでください。 */
    @Synchronized
    fun retitle(url: String, title: String) {
        val key = key(url)
        val t = title.trim()
        if (t.isEmpty() || _entries.value.none { key(it.url) == key && it.title != t }) return
        save(_entries.value.map { if (key(it.url) == key) it.copy(title = t) else it })
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    private fun key(url: String) = url.trim().substringBefore('#')

    private fun load(): List<RecentPage> {
        if (!file.isFile) return emptyList()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                RecentPage(url, (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }.orEmpty().take(limit)
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    private fun save(list: List<RecentPage>) {
        _entries.value = list
        file.parentFile?.mkdirs()
        val json = JsonArray(list.map { buildJsonObject { put("url", it.url); put("title", it.title); put("at", it.openedAtMs) } }).toString()
        // 最初に一時ファイルを書き込んでから、名前を変更します。書き込みの途中で強制終了すると、ファイルの半分も残りません。
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        const val LIMIT = 10
        private const val FILE = "browser_recent.json"
    }
}
