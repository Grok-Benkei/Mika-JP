package com.wangzan101.mika.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * ブラウザ ページのモバイル バージョンとコンピューター バージョンのどちらを使用する必要がありますか?
 * - ユーザーが自分で閲覧するためのデフォルトのモバイル バージョン。 「もっと見る」でパソコン版に変更したWebサイトは[UserSiteModes]として記録され、次回からはパソコン版が使用されます。
 * - 「ミカにログイン」(まだログインしていない頻繁に使用される Web サイトから Web サイトを開く) すると、作業時にタブ全体がミカの ID として使用されます ([agentMobile])。
 *   ログイン時に残された Cookie は、ミカが作業中に見た Web サイトと同じバージョンであり、ミカもそこにログインしています。
 * エージェント自身のラベルはここでは参照されません。サイトによって記憶されているラベルは同じままです (デフォルトのコンピューター バージョン)。
 */
object BrowserIdentity {
    /** は、モバイル バージョンを使用する場合に true を返します。 */
    fun mobile(loginForMika: Boolean, agentMobile: Boolean, userDesktop: Boolean): Boolean =
        if (loginForMika) agentMobile else !userDesktop

    /** よく使用される Web サイトから開きます: まだログインしていない場合は、「Mika にログイン」に進みます。 */
    fun loginFlow(site: FavoriteSite): Boolean = !site.loggedIn
}

/**
 * ユーザーがブラウザ ページを Web サイトのコンピュータ バージョン (デフォルトのモバイル バージョン) に変更します。ホスト名に従って、www を削除します。初めに。上位のサブドメイン名に従います
 * (example.com を思い出してください。m.example.com もコンピューター バージョンを使用しています)。これはエージェントの共有とは別に保存され、相互に影響を与えません。
 * は、アプリのプライベート ディレクトリの JSON ファイルに保存されます。壊れた場合は空のテーブルからスタートします。
 */
class UserSiteModes(dir: File) {
    private val file = File(dir, FILE)
    private val desktop = LinkedHashSet<String>()

    init {
        runCatching {
            if (file.isFile) {
                (Json.parseToJsonElement(file.readText()) as? JsonArray)?.forEach { e ->
                    (e as? JsonPrimitive)?.contentOrNull?.let(::key)?.let { desktop += it }
                }
            }
        }
    }

    @Synchronized
    fun isDesktop(host: String?): Boolean {
        var h = host?.let(::key) ?: return false
        while (true) {
            if (h in desktop) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** この Web サイトのどのバージョンを使用しているかをメモしてください。モバイル版に戻す場合は、上位のドメイン名のレコードも削除してください。そうしないと、引き続き上位をフォローすることになります。 */
    @Synchronized
    fun set(host: String, desktop: Boolean) {
        val k = key(host) ?: return
        if (desktop) this.desktop += k
        else this.desktop.removeAll { k == it || k.endsWith(".$it") }
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonArray(desktop.map(::JsonPrimitive)).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "browser_site_modes.json"

        fun key(host: String): String? =
            host.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() && ' ' !in it && '/' !in it }
    }
}
