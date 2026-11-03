package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.script.JsSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** は site コマンドのパラメータです。 [type] は文字列/整数/数値/ブール値です。 */
@Serializable
data class CommandArg(val name: String, val type: String, val required: Boolean = false, val description: String = "")

/** はサイト コマンド (スクリプト ヘッダー) の宣言です。 [file] は、サイト ディレクトリ内の相対パス `scripts/<name>.js` です。 */
@Serializable
data class SiteCommandDecl(val name: String, val description: String, val args: List<CommandArg> = emptyList(), val returns: String, val file: String)

/** 実行可能なサイト コマンド: 属するサイト、ステートメント、スクリプト本体 (ヘッダーの後)。 */
data class SiteCommand(val site: String, val decl: SiteCommandDecl, val body: String)

/**
 * サイト コマンド: サイト ナレッジ ディレクトリ (scripts/manifest.json にないディレクトリ) 内の `scripts/<name>.js` は、現在のページの読み取りワールドで読み取り専用で実行されます。
 * ファイルはブロック コメントで始まります。最初の行は [MARK] で、その後はコメントの終わりまで 1 行につき 1 行ずつ続きます。
 * - `name: list_results`
 * - `description: 列出当前结果页上的条目`
 * - `arg: limit integer - 最多几条`
 * - `arg: query string required - 只要标题里有这段字的`
 * - `returns: [{title, url}]`
 *
 * 名はファイル名と同じです。 arg には、「name type [required] [- description]」のように複数行を含めることができます。ヘッダーの後には非同期関数の本体があり、パラメーターは `args` にあります。
 */
object SiteCommandFile {
    const val MARK = "/* mika-command"
    const val DESCRIPTION_LIMIT = 120
    const val BODY_LIMIT = 8_000
    val NAME = Regex("[a-z][a-z0-9_]{0,39}")
    val TYPES = listOf("string", "integer", "number", "boolean")
    private val ARG = Regex("""^([a-z][A-Za-z0-9_]{0,39})\s+(string|integer|number|boolean)(\s+required)?(?:\s+-\s+(.+))?$""")

    fun isCommand(text: String): Boolean = clean(text).startsWith(MARK)

    /** [decl] は、エラーがない場合にのみ使用できます。 [body] はヘッダーの後のスクリプトです。 */
    class Parsed(val decl: SiteCommandDecl?, val body: String, val errors: List<String>)

    private fun clean(text: String) = text.removePrefix("﻿").replace("\r\n", "\n").trimStart()

    /** [file] は、サイト ディレクトリ (`scripts/<name>.js`) の下の相対パスです。 */
    fun parse(file: String, text: String): Parsed {
        val t = clean(text)
        if (!t.startsWith(MARK)) return Parsed(null, "", listOf("ヘッダーは`$MARK`で始めてください"))
        val end = t.indexOf("*/")
        if (end < 0) return Parsed(null, "", listOf("ヘッダーの終端 */ がありません"))
        val body = t.substring(end + 2).trim()
        val errors = mutableListOf<String>()
        var name: String? = null
        var description: String? = null
        var returns: String? = null
        val args = mutableListOf<CommandArg>()
        for (raw in t.substring(MARK.length, end).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val i = line.indexOf(':')
            if (i <= 0) { errors += "ヘッダーの形式を認識できません:$line"; continue }
            val value = line.substring(i + 1).trim()
            when (val key = line.substring(0, i).trim()) {
                "name" -> name = value
                "description" -> description = value
                "returns" -> returns = value
                "arg" -> ARG.matchEntire(value)?.let { m ->
                    args += CommandArg(m.groupValues[1], m.groupValues[2], m.groupValues[3].isNotEmpty(), m.groupValues[4].trim())
                } ?: run { errors += "argは「名前 型 [required] [- 説明]」の形式で指定してください。対応する型: ${TYPES.joinToString("/")}:$value" }
                else -> errors += "ヘッダーに未知のキーがあります: $key"
            }
        }
        val base = file.substringAfterLast('/').removeSuffix(".js")
        when {
            name == null -> errors += "nameがありません"
            !NAME.matches(name) -> errors += "nameは英小文字で始まる40文字以内の英小文字・数字・アンダースコアにしてください"
            name != base -> errors += "name($name)はファイル名($base.js)と一致させてください"
        }
        if (description.isNullOrEmpty()) errors += "descriptionがありません"
        else if (description.codePointCount(0, description.length) > DESCRIPTION_LIMIT) errors += "descriptionは$DESCRIPTION_LIMIT 文字以内にしてください"
        if (returns.isNullOrEmpty()) errors += "returnsがありません"
        args.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { errors += "arg $it が重複しています" }
        if (body.isEmpty()) errors += "スクリプトの本文が空です"
        if (body.length > BODY_LIMIT) errors += "スクリプト本文は${body.length}文字で、上限$BODY_LIMIT 文字を超えています"
        // 動的インポートは、読み取り専用パッケージをバイパスして、読み取りワールドのグローバル スコープでモジュールを実行します。
        if (JsSource.usesImport(body)) errors += "スクリプト本文ではimportを使用できません（文字列・コメント内を除く）"
        val decl = if (errors.isEmpty()) SiteCommandDecl(name!!, description!!, args, returns!!, file) else null
        return Parsed(decl, body, errors)
    }

    /** モデルによって指定されたパラメーターは、宣言に従ってチェックされます。宣言されていない場合、必須フィールドが欠落している場合、または型が間違っている場合は、問題の説明が返されます。問題がなければnullになります。 */
    fun checkArgs(decl: SiteCommandDecl, args: JsonObject): String? {
        val declared = decl.args.associateBy { it.name }
        args.keys.firstOrNull { it !in declared }?.let {
            return "'$it' is not an argument of ${decl.name}; it takes " + (decl.args.joinToString { a -> a.name }.ifEmpty { "no arguments" })
        }
        for (a in decl.args) {
            val v = args[a.name]
            if (v == null || v is JsonNull) {
                if (a.required) return "${decl.name} needs '${a.name}' (${a.type})"
                continue
            }
            val p = v as? JsonPrimitive ?: return "'${a.name}' must be a ${a.type}"
            val fits = when (a.type) {
                "string" -> p.isString
                "boolean" -> !p.isString && p.booleanOrNull != null
                "integer" -> !p.isString && p.longOrNull != null
                else -> !p.isString && p.doubleOrNull != null
            }
            if (!fits) return "'${a.name}' must be a ${a.type}"
        }
        return null
    }

    /** サイトのナレッジ ブロックにリストされている行。 */
    fun line(d: SiteCommandDecl): String {
        val sig = d.args.joinToString(", ") { it.name + (if (it.required) "" else "?") + ": " + it.type }
        val notes = d.args.filter { it.description.isNotEmpty() }.joinToString("; ") { "${it.name} ${it.description}" }
        return "- ${d.name}($sig): ${d.description}. Returns ${d.returns}" + (if (notes.isEmpty()) "" else ". Arguments: $notes")
    }
}
