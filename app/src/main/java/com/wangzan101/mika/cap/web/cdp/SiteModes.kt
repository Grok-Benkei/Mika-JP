package com.wangzan101.mika.cap.web.cdp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Web ページのモバイル バージョンを使用する Web サイト (デフォルトはデスクトップ バージョン)。ホスト名に従って、www を削除します。初めに。上位のサブドメイン名に従います
 * (example.com、m.example.com もモバイル バージョンを使用していることに注意してください)。これは、アプリのプライベート ディレクトリ内の JSON ファイルに保存されます。壊れた場合は空のテーブルからスタートします。
 */
class SiteModes(dir: File) {
    private val file = File(dir, FILE)
    private val mobile = LinkedHashSet<String>()

    init {
        runCatching {
            if (file.isFile) {
                (Json.parseToJsonElement(file.readText()) as? JsonArray)?.forEach { e ->
                    (e as? JsonPrimitive)?.contentOrNull?.let(::key)?.let { mobile += it }
                }
            }
        }
    }

    @Synchronized
    fun isMobile(host: String?): Boolean {
        var h = host?.let(::key) ?: return false
        while (true) {
            if (h in mobile) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** この Web サイトのどのバージョンが使用されているかをメモしてください。デスクトップ版に変更する場合は、上位ドメイン名のレコードも一緒に削除しないと、上位ドメイン名に準じます。 */
    @Synchronized
    fun set(host: String, mobile: Boolean) {
        val k = key(host) ?: return
        if (mobile) this.mobile += k
        else this.mobile.removeAll { k == it || k.endsWith(".$it") }
        save()
    }

    @Synchronized
    fun mobileSites(): List<String> = mobile.toList()

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonArray(mobile.map(::JsonPrimitive)).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "site_modes.json"

        fun key(host: String): String? =
            host.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() && ' ' !in it && '/' !in it }
    }
}
