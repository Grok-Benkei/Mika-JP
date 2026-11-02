package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * サイト登録フォームの項目: ホスト名、最近のアクセス時刻、Mika にログインしているかどうか (ログインが確認されているか、ページ上でログイン時にマークされているか、
 * (終了したように見える場合は削除されます)。 [challengedAtMs] / [challenge]: この Web サイトで最後に発生した人間と機械による検証またはアクセス傍受の時間と種類
 * (キャプチャ / アクセスブロック)、この Web サイトのセッションが Web サイトのターゲットになっている可能性があります。 [SiteRegistry.CHALLENGE_TTL_MS] またはサイトデータがクリアされた後はカウントされません。
 */
data class SiteEntry(
    val host: String,
    val lastVisitMs: Long,
    val loggedInByUser: Boolean,
    val challengedAtMs: Long? = null,
    val challenge: String? = null,
) {
    /** 現時点（[now]）では「傍受」されています。 */
    fun challenged(now: Long): Boolean = challengedAtMs?.let { now - it < SiteRegistry.CHALLENGE_TTL_MS } == true
}

/**
 * サイト登録フォーム: エージェントとユーザーが内蔵ブラウザで訪問した Web サイトを記録します。システム インターフェイスは、Cookie を保存しているすべてのサイトをリストすることはできません。
 * したがって、これは「Web サイト ログイン」設定ページに表示できる唯一のソースです。アプリのプライベート ディレクトリ内の JSON ファイルに保存されます。
 * ファイルが破損している場合は、そのファイルを脇に移動し (新しいコンテンツで上書きしないでください)、空のテーブルからやり直します。
 */
class SiteRegistry(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val sites = LinkedHashMap<String, SiteEntry>()
    private var corrupt = false
    private val _entries = MutableStateFlow<List<SiteEntry>>(emptyList())

    /** と[list]は同じ内容です。これらは変更されると更新され、インターフェイスに使用されます。 */
    val entries: StateFlow<List<SiteEntry>> = _entries.asStateFlow()

    init {
        load()
        publish()
    }

    @Synchronized
    fun visited(host: String) {
        val h = normalize(host) ?: return
        sites[h] = sites[h]?.copy(lastVisitMs = clock()) ?: SiteEntry(h, clock(), false)
        save()
    }

    /** ページに表示されるログイン ステータス: ログイン、ログアウト、削除 (セッション期限切れ) で、動きは見られません。レコードが変更されたかどうかを返します。 */
    @Synchronized
    fun loginObserved(host: String, state: LoginState): Boolean {
        val h = normalize(host) ?: return false
        val want = when (state) { LoginState.SignedIn -> true; LoginState.SignedOut -> false; LoginState.Unknown -> return false }
        val cur = sites[h]
        if ((cur?.loggedInByUser ?: false) == want) return false
        sites[h] = (cur ?: SiteEntry(h, clock(), false)).copy(loggedInByUser = want)
        save()
        return true
    }

    /**
     * 人間とマシンの検証またはアクセス傍受によりタスクがブロックされました ([kind]: captcha / access_blocked): 時間とタイプをメモしてください。 同じ種類の
     * が[CHALLENGE_REFRESH_MS]に記録されている場合は書き換えられません。
     */
    @Synchronized
    fun challenged(host: String, kind: String) {
        val h = normalize(host) ?: return
        val cur = sites[h]
        if (cur?.challenge == kind && cur.challengedAtMs?.let { clock() - it < CHALLENGE_REFRESH_MS } == true) return
        sites[h] = (cur ?: SiteEntry(h, clock(), false)).copy(challengedAtMs = clock(), challenge = kind)
        save()
    }

    /** これまでにブロックされた Web サイトに遭遇しました。 */
    @Synchronized
    fun challengedSites(): Set<String> {
        val now = clock()
        return sites.values.filter { it.challenged(now) }.map { it.host }.toSet()
    }

    @Synchronized
    fun markLoggedIn(host: String) {
        val h = normalize(host) ?: return
        sites[h] = (sites[h] ?: SiteEntry(h, clock(), false)).copy(loggedInByUser = true)
        save()
    }

    /** 最初にログインした人は、次に最近のアクセスをクリックします。 */
    @Synchronized
    fun list(): List<SiteEntry> = sorted()

    @Synchronized
    fun remove(host: String) {
        val h = normalize(host) ?: return
        if (sites.remove(h) != null) save()
    }

    @Synchronized
    fun clear() {
        sites.clear()
        save()
    }

    private fun sorted() = sites.values.sortedWith(compareByDescending<SiteEntry> { it.loggedInByUser }.thenByDescending { it.lastVisitMs })

    private fun publish() {
        _entries.value = sorted()
    }

    private fun load() {
        if (!file.isFile) return
        val parsed = try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val host = (o["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::normalize) ?: return@mapNotNull null
                SiteEntry(
                    host, (o["lastVisitMs"] as? JsonPrimitive)?.longOrNull ?: 0L, (o["loggedIn"] as? JsonPrimitive)?.booleanOrNull == true,
                    (o["challengedAt"] as? JsonPrimitive)?.longOrNull, (o["challenge"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                )
            }
        } catch (e: SerializationException) {
            null
        }
        if (parsed == null) {
            corrupt = true
            return
        }
        parsed.forEach { sites[it.host] = it }
    }

    private fun save() {
        dir.mkdirs()
        if (corrupt) {
            // 破損したファイルを脇に移動し、新しいコンテンツで上書きしないでください。
            file.renameTo(File(dir, "$FILE.corrupt-${clock()}"))
            corrupt = false
        }
        val json = JsonArray(sites.values.map {
            buildJsonObject {
                put("host", it.host); put("lastVisitMs", it.lastVisitMs); put("loggedIn", it.loggedInByUser)
                it.challengedAtMs?.let { t -> put("challengedAt", t) }
                it.challenge?.let { k -> put("challenge", k) }
            }
        }).toString()
        // 最初に一時ファイルを書き込んでから、名前を変更します。書き込みの途中で強制終了すると、ファイルの半分も残りません。
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
        publish()
    }

    companion object {
        /** 傍受されたマークは長期間保持されます (その後、ほとんどの Web サイトはこのセッションに焦点を当てなくなります)。 */
        const val CHALLENGE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** 非常に長い時間が経った後に同じインターセプトが再び発生した場合、レコードは書き換えられません。 */
        const val CHALLENGE_REFRESH_MS = 10L * 60 * 1000
        /** は、「Web サイトがこのセッションをターゲットにしている」という乗っ取り理由としてカウントされます。 */
        val CHALLENGE_KINDS = setOf("captcha", "access_blocked")
        private const val FILE = "sites.json"

        /** ホスト名は小文字とwwwに統一されます。削除されます。渡された URL も認識されます。登録されていないホスト名とは異なります。 */
        private fun normalize(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            return WebUrlPolicy.host(if (s.contains("://")) s else "https://$s")?.takeIf { h -> h.none { it.isWhitespace() } }
        }
    }
}
