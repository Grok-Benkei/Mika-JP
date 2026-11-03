package com.wangzan101.mika.core.record

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.tool.Verb

/**
 * の実行記録の感度を解除: パスワード ボックスに入力された単語と API キーの形の文字列は、`***` として記録されます。
 * 呼び出し元がターゲットがパスワード ボックスであるかどうかわからない場合は、「はい」として処理する必要があります (覚えておくことは少ない方が良いです)。
 */
object Redactor {
    const val MASK = "***"

    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")
    private val BEARER = Regex("""\bBearer\s+[A-Za-z0-9._~+/=\-]{8,}""")
    private val GOOGLE_KEY = Regex("""\bAIza[0-9A-Za-z_\-]{30,}""")
    /** Google の新しい形式の認証キーは `AQ.` で始まり、その後にbase64url 文字の長い文字列が続きます。 */
    private val GOOGLE_AUTH_KEY = Regex("""\bAQ\.[0-9A-Za-z_\-]{20,}""")
    private val GITHUB_TOKEN = Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""")

    /** [secretField]: ポートは、この入力のターゲットがパスワード ボックスであることを報告します。 */
    /** [verb]: 呼び出されたツールによって実行されたアクション (不明なツールの場合は null)。 */
    fun args(verb: Verb?, argsJson: String, secretField: Boolean): String {
        if (verb == Verb.MemorySave) return maskFields(argsJson, MEMORY_ARGS)
        if (verb == Verb.Script) return script(argsJson)
        if (verb == Verb.PhoneCommand) return maskFields(argsJson, setOf("command"))
        if (verb == Verb.PhoneRun) return phoneRun(argsJson)
        if (verb != Verb.TypeText || !secretField) return text(argsJson)
        val o = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return MASK // 解析できない場合は、段落全体が記憶されません。むしろ情報をなくしたほうが良いでしょう。
        val masked = if ("text" in o) JsonObject(o + ("text" to JsonPrimitive(MASK))) else o
        return text(masked.toString())
    }

    /** 条件モバイルコマンドの入力内容はパスワードの場合があり、セクション全体がフィールドごとにコード化されています。 */
    private fun phoneRun(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return MASK
        val commands = o["commands"] as? JsonArray ?: return text(argsJson)
        val hidden = JsonArray(commands.map { command ->
            val item = command as? JsonObject ?: return@map command
            if ("text" !in item || item["op"] != JsonPrimitive("type")) item
            else JsonObject(item + ("text" to JsonPrimitive(MASK)))
        })
        return text(JsonObject(o + ("commands" to hidden)).toString())
    }

    /**
     * web_script のソース コード: スクリプトに入力する単語は、任意の形式で記述できます (mika.type に指定、最初に変数に格納、結合)。 type_text と同様、
     * がパスワード ボックスであるかどうかは不明なので、「はい」として扱います。ソース コード内のすべての文字列リテラルはコーディングされ、引用符の種類のみが残ります (テンプレート文字列は `${…}` の位置を残します)。
     */
    private fun script(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return MASK
        val code = (o["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return text(argsJson)
        val masked = STRING_LITERAL.replace(code) { m ->
            val q = m.value.first()
            val body = m.value.substring(1, m.value.length - 1)
            if (body.isEmpty()) m.value
            else if (q == '`') "`" + TEMPLATE_HOLE.findAll(body).joinToString("") { h -> MASK + STRING_LITERAL.replace(h.value) { "'$MASK'" } } + MASK + "`"
            else "$q$MASK$q"
        }
        return text(JsonObject(o + ("code" to JsonPrimitive(masked))).toString())
    }

    /** ツールの結果が実行記録に入力される前: 記憶された内容とユーザーが戻ったときに書いたコメントは記録されません (番号と種類は記憶されます)。 */
    fun result(verb: Verb?, content: JsonObject): JsonObject = when (verb) {
        Verb.MemorySearch -> (content["memories"] as? JsonArray)?.let { arr ->
            JsonObject(content + ("memories" to JsonArray(arr.map { m ->
                (m as? JsonObject)?.let { if ("content" in it) JsonObject(it + ("content" to JsonPrimitive(MASK))) else it } ?: JsonPrimitive(MASK)
            })))
        } ?: content
        Verb.HandToUser -> if ("userNote" in content) JsonObject(content + ("userNote" to JsonPrimitive(MASK))) else content
        Verb.PhoneCommand -> if ("output" in content) JsonObject(content + ("output" to JsonPrimitive(MASK))) else content
        else -> content
    }

    private fun maskFields(argsJson: String, fields: Set<String>): String {
        val o = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return MASK
        return text(JsonObject(o.mapValues { (k, v) -> if (k in fields) JsonPrimitive(MASK) else v }).toString())
    }

    private val STRING_LITERAL = Regex("""'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`""")
    /** テンプレート文字列の ${…}: 式はリテラルではなく、位置は予約されており、前後の文字はコード化されています。 */
    private val TEMPLATE_HOLE = Regex("""\$\{[^}]*\}""")
    private val MEMORY_ARGS = setOf("content", "quote", "keywords")

    /** はワンタイムコードのような単語です。 */
    private val CODE_WORD = Regex("""验证|校验码|动态码|动态密码|确认码|安全码|短信|口令|(?i:\b(?:code|otp|passcode|pin|2fa)\b)""")
    /** 単一のスペースまたはハイフンで区切られた 4 ～ 8 桁 (「4 8 2 9 1 3」「482-913」)。 */
    private val CODE_DIGITS = Regex(
        """(?<![\d.¥$€£])(?<!\d,)(?<![¥$€£] )\d(?:[ \-]?\d){3,7}(?!\d)(?!\.\d)(?!,\d{3}(?!\d))""" +
            // 単位、年、通貨の後に続くのはコードではなく数量です。
            """(?!\s*(?:年|元|块|角|分钟|小时|秒|天|月|日|号|次|个|人|件|条|米|公里|千米|克|公斤|千克|斤|吨|度|℃|°|%|‰|[¥$€£]|(?i:km|kg|mb|gb|kb|ms|cm|mm|mah|rmb|usd|cny)\b))""",
    )
    /** コードとそのような単語の間には、最大でも数文字が存在します (どちら側がカウントされるかは関係ありません)。 */
    private const val CODE_WINDOW = 24

    /**
     * 証拠台帳の資料 (画面読み取り、Web ページ、コマンド出力) は、アカウントを入力する前に台帳を通過します: [text] 認識されたキーに加えて、ワンタイム コードもカバーされます—
     * に続く [CODE_WINDOW] には、文字内に「検証」、「SMS メッセージ」、「コード」などの 4 ～ 8 桁の単語が含まれます。パスワードボックス内の単語は、画面読み上げのテキストには含まれません。
     */
    fun evidence(s: String): String {
        val t = text(s)
        val words = CODE_WORD.findAll(t).map { it.range }.toList()
        if (words.isEmpty()) return t
        return CODE_DIGITS.replace(t) { m ->
            val near = words.any { w -> m.range.first - w.last in 1..CODE_WINDOW + 1 || w.first - m.range.last in 1..CODE_WINDOW + 1 }
            if (near) MASK else m.value
        }
    }

    fun text(s: String): String {
        var out = s
        for (re in listOf(SK_KEY, GOOGLE_KEY, GOOGLE_AUTH_KEY, GITHUB_TOKEN)) out = re.replace(out, MASK)
        return BEARER.replace(out, "Bearer $MASK")
    }

    // ---------- Web ページのネットワーク データ ----------

    data class Network(val url: String?, val body: String?, val headers: Map<String, String>?)

    /**
     * Web ページのネットワーク データ (アドレス、応答テキスト、ヘッダー) は、ネットワーク レコードを残す前に通過する必要があります。モデルへの引き渡し、検証証拠の入力、操作記録の入力はすべて同じです。
     * クエリ パラメーター、JSON/フォーム フィールド、資格情報などの名前を持つヘッダー値は、`***` として記録されます (単語によって判断されます。作成者とキーワードはカウントされません)。
     * OAuth バウンスのコードと状態についても説明します。さらに、JWT、長くて複雑な (大文字と小文字と数字) 文字列、および [text] によって認識されるキーもカバーされています。
     */
    fun network(url: String? = null, body: String? = null, headers: Map<String, String>? = null): Network = Network(
        url = url?.let { values(maskUrl(it), slash = false) },
        body = body?.let { values(maskBody(it), slash = true) },
        headers = headers?.mapValues { (k, v) -> if (secretName(k)) MASK else values(v, slash = true) },
    )

    /**
     * スクリプトおよびサイト コマンドによってページから読み取られたデータ (JSON) を読み取ります。ネットワーク応答本文と同じルール セット (資格情報名、JWT、長くて複雑な文字列、キーに基づくフィールド)、
     * は、スラッシュをまたいで認識されない単なる長い文字列です (結果には URL が含まれることが多く、パスの各セグメントは個別に表示されます)。
     */
    fun pageData(s: String): String = values(maskBody(s), slash = false)

    private val SECRET_WORDS = setOf(
        "token", "tokens", "secret", "password", "passwd", "pwd", "passcode", "session", "sessionid", "sessid", "sid",
        "auth", "authorization", "csrf", "xsrf", "signature", "sig", "otp", "cookie", "credential", "credentials",
        "apikey", "jwt", "bearer", "pin", "privatekey",
    )
    private val SECRET_COMPOUNDS = setOf("apikey", "accesskey", "secretkey", "privatekey", "clientsecret", "sessionkey", "authkey")
    private val CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val WORD_SPLIT = Regex("[^A-Za-z0-9]+")

    /** 名前は単語 (ザトウクジラ、アンダースコア、ハイフン) に分割され、その後に資格情報などの単語が続きます。 クエリ文字列またはフラグメント内の */
    internal fun secretName(name: String): Boolean {
        val words = name.split(WORD_SPLIT).flatMap { it.split(CASE_BOUNDARY) }.map { it.lowercase() }.filter { it.isNotEmpty() }
        return words.any { it in SECRET_WORDS } || words.joinToString("") in SECRET_COMPOUNDS
    }

    private val OAUTH_MARKERS = setOf("state", "redirect_uri", "client_id", "code_verifier")
    private val OAUTH_RESULTS = setOf("code", "state")

    /** a=b&c=d: 資格情報名の値がカバーされます。 OAuth バウンス (状態など、およびコードまたは資格情報付き) と同様に、コードと状態もカバーされます。 [bareKey]: 単一のキーも資格情報とみなされます。 JWT、長く複雑な文字列、および */
    private fun maskParams(q: String, bareKey: Boolean): String {
        val parts = q.split('&')
        val names = parts.map { it.substringBefore('=').lowercase() }
        val oauth = names.any { it in OAUTH_MARKERS } && (names.contains("code") || names.any { secretName(it) })
        return parts.joinToString("&") { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) return@joinToString p
            val name = p.substring(0, eq)
            val n = name.lowercase()
            val hide = secretName(name) || (bareKey && n == "key") || (oauth && n in OAUTH_RESULTS)
            if (hide) "$name=$MASK" else p
        }
    }

    private fun maskUrl(u: String): String {
        val hash = u.indexOf('#')
        val beforeHash = if (hash >= 0) u.substring(0, hash) else u
        val fragment = if (hash >= 0) u.substring(hash + 1) else null
        val q = beforeHash.indexOf('?')
        val base = if (q >= 0) beforeHash.substring(0, q + 1) + maskParams(beforeHash.substring(q + 1), bareKey = true) else beforeHash
        return if (fragment == null) base else base + "#" + if ('=' in fragment) maskParams(fragment, bareKey = true) else fragment
    }

    private val JSON_PAIR = Regex(""""((?:[^"\\]|\\.){1,100})"(\s*:\s*)("(?:[^"\\]|\\.)*"|-?\d[\d.eE+\-]*|true|false|null)""")
    private val FORM = Regex("""^[^\s{}\[\]"<>]+=[^\s]*$""")

    private fun maskBody(b: String): String {
        if (FORM.matches(b)) return maskParams(b, bareKey = false)
        return JSON_PAIR.replace(b) { m ->
            if (secretName(m.groupValues[1])) "\"${m.groupValues[1]}\"${m.groupValues[2]}\"$MASK\"" else m.value
        }
    }

    private val JWT = Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""")
    private val LONG_WITH_SLASH = Regex("""[A-Za-z0-9_\-+/=]{32,}""")
    private val LONG = Regex("""[A-Za-z0-9_\-+=]{32,}""")

    /** 値の既知の形式のキー。アドレスではスラッシュは認識されません (パスの各セグメントは個別に表示されます)。 */
    private fun values(s: String, slash: Boolean): String {
        var out = JWT.replace(s, MASK)
        out = (if (slash) LONG_WITH_SLASH else LONG).replace(out) { m ->
            val v = m.value
            if (v.any(Char::isUpperCase) && v.any(Char::isLowerCase) && v.any(Char::isDigit)) MASK else v
        }
        return text(out)
    }
}
