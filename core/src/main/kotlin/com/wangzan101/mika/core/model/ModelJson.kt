package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * は、必要に応じてモデルから返された JSON を読み取ります (検証の結論、ルートの一致、メモリの変更など)。モデルが異なれば、提出のスタイルも異なります。コード ブロック、前後のいくつかの文、
 * 段落を与える前に考えてください。オブジェクトの後には中括弧で囲まれた説明が続きます。キー名の大文字と小文字が区別されているか、表記が異なります。ここではこれらは一律に許容されます。
 * どうしても読めない場合は、もう一度質問してください。 JSON ([completeParsed]) のみが必要であることは明らかです。
 */
object ModelJson {
    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

    /** バランスのとれた括弧を含む JSON オブジェクトとして読み取ることができる応答内の最初のフラグメント (文字列内の括弧はカウントされません)。そうでない場合は null です。 */
    fun firstObject(text: String): JsonObject? {
        var from = text.indexOf('{')
        while (from >= 0) {
            val end = balancedEnd(text, from)
            if (end > from) {
                val obj = try { lenient.parseToJsonElement(text.substring(from, end + 1)) as? JsonObject } catch (e: Exception) { null }
                if (obj != null) return obj
            }
            from = text.indexOf('{', from + 1)
        }
        return null
    }

    /** は、[start] の { , トリミングされた } 位置から始まります。アンバランスは-1です。 */
    private fun balancedEnd(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    /** キー名の比較の前に大文字と小文字、アンダースコア、ハイフン、スペースを削除します（verdict、Verdict、final_verdictの書き方の違い）。 */
    private fun key(k: String) = k.lowercase().filter { it.isLetterOrDigit() }

    /** は、[names] に従って順番に検索されます (異なる書き込み方法が受け入れられます)。誰もいない場合は null です。 */
    fun JsonObject.field(vararg names: String): JsonElement? {
        for (n in names) {
            this[n]?.let { return it }
            val want = key(n)
            entries.firstOrNull { key(it.key) == want }?.let { return it.value }
        }
        return null
    }

    /** 最初の返信から読み取れなかった場合に追加する一文。 */
    const val JSON_ONLY = "前の返信からJSONを読み取れませんでした。JSONオブジェクトだけを出力してください。コードブロック記号や他の文字は付けず、形式はシステム指示に従ってください。"

    /**
     * はモデルを 1 回調整し、[parse] を使用して応答を読み取ります。読み上げられない場合は、その返信に従って [JSON_ONLY] を入力し、もう一度質問してください。どちらの時間も null ではありません。
     * モデル エラー (例外) が通常どおり呼び出し元にスローされます。
     */
    suspend fun <T : Any> completeParsed(model: ChatModel, system: String, messages: List<Message>, parse: (String) -> T?): T? {
        val first = model.complete(system, messages, emptyList())
        val firstText = first.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        parse(firstText)?.let { return it }
        val retry = messages + Message(Role.Assistant, listOf(Part.Text(firstText.ifBlank { "(空)" })), Author.Mika) +
            Message(Role.User, listOf(Part.Text(JSON_ONLY)), Author.Mika)
        val second = model.complete(system, retry, emptyList())
        return parse(second.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
    }
}
