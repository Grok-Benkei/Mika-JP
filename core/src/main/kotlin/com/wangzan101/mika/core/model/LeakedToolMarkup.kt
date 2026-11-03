package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一部のモデルは、`tool_calls` チャネルを介してテキストに直接送信する必要があるツール呼び出しマークを吐き出します。
 * DeepSeek V3.2 / V4 の DSML (`<｜DSML｜invoke name="…">…`)、および Qwen のクラス `<tool_call>{…}</tool_call>`。
 * そのまま UI へ渡すと `<｜｜DSML｜｜ calls>` の文字化けの山になり、しかも本文として履歴に記録されてしまう。
 *
 * ここでは2つのことを、この順序で行う。まず復元し、次に取り除く:
 * 1. ツール呼び出しに復元できるものは復元し（パラメータは `string` フラグで文字列か JSON 値に変換する）、ツールエンジンへ渡して通常どおり実行する;
 * 2. 復元できない DSML はマーカーだけを取り除き、マーカーの外の本文を残す; 復元できない `<tool_call>` ブロックはそのまま残す、
 *    は、AgentLoop の不在着信チェック (LeakedToolCall) によって一度返されるだけで、応答として渡されません。
 *
 * は、マークが実際にテキスト内に表示される場合にのみ移動され、通常の回答では 1 文字も変更されません ([repair] の変更された判定を参照)。
 * ペアで書き込まれ、正当な JSON 呼び出しを含む `<tool_call>` ブロックは呼び出しとして扱われます。これは意図的なトレードオフです。
 * 文字化けした文字をユーザーに表示するコストはさらに大きくなります。存在しないツールが調整された場合でも、ツール エンジンはエラーを報告します。
 */
object LeakedToolMarkup {
    data class Invocation(val name: String, val argsJson: String)

    /** [text] は、マークを剥がした後に残るテキストです。 [calls] は、マークから復元されたツール呼び出しです (空の場合があります)。 */
    data class Scan(val text: String, val calls: List<Invocation>)

    /** の縦線の書き方には、全角 `｜` と半角 `|` の 2 通りがあります。漏れた場合は1本、2本とも表記されます。 */
    private const val B = "[｜|]"

    private fun bars(prefix: String) = prefix + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}"

    private val INVOKE = Regex(
        bars("<") + "\\s*invoke\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>(.*?)</" + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}\\s*invoke\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PARAM = Regex(
        bars("<") + "\\s*parameter\\s+name\\s*=\\s*\"([^\"]*)\"(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)</" +
            B + "{1,2}\\s*DSML\\s*" + B + "{1,2}\\s*parameter\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 残りのペア/シングル マーク (外側の `calls`、`function_calls` など) が削除されます。 */
    private val TAG = Regex("</?" + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}[^>]*>")

    /** クウェン カテゴリ 1: `<tool_call>{"name":…,"arguments":…}</tool_call>`。 */
    private val TOOL_CALL_BLOCK = Regex(
        "<tool_call>\\s*(.*?)\\s*</tool_call>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PIPE_TOOL_CALL_BLOCK = Regex(
        "<" + B + "{1,2}tool_call" + B + "{1,2}\\s*>(.*?)</" + B + "{1,2}tool_call" + B + "{1,2}\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * はストリーミングで一般的です。マークは開始されたばかりです (`<｜`、`<｜｜DSML`、`<｜｜DSML｜｜ invoke name="pho`)。
     * は、垂直バーで始まり、DSML の最初の数文字が続くハーフマークのみを認識し、通常のテキストとは一致しません (`a<b この文はDSMLの説明` はカウントされません)。
     */
    private val PARTIAL_TAIL = Regex("^/?" + B + "{1,2}\\s*(?:D(?:S(?:M(?:L.*)?)?)?)?$", RegexOption.DOT_MATCHES_ALL)

    /** ストリーミングプレビューの場合: 文字化けしたコードをユーザーに見させないように、フレームが来るたびにマークの受信部分を剥がします。 */
    fun stripMarkup(text: String): String = scan(text).text

    /** テキストの段落をスキャンします。マークを剥がして通話を復元します。マークがない場合はそのまま返却され空呼び出しとなります。 */
    fun scan(text: String): Scan {
        if (text.isEmpty()) return Scan(text, emptyList())
        var rest = text
        val calls = mutableListOf<Invocation>()

        // 1. DSML: まず invoke を押してブロック全体を抽出し、parameter を押してパラメータを抽出します。
        if (rest.contains("DSML", ignoreCase = true)) {
            for (m in INVOKE.findAll(rest)) {
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) continue
                calls += Invocation(name, paramsJson(m.groupValues[2]))
            }
            if (calls.isNotEmpty()) rest = rest.replace(INVOKE, " ")
            rest = TAG.replace(rest, " ")
        }

        // 2. <tool_call>{…}</tool_call> (JSON であり、オブジェクトまたはグループの場合があります)
        rest = extractJsonBlocks(rest, TOOL_CALL_BLOCK, calls)
        rest = extractJsonBlocks(rest, PIPE_TOOL_CALL_BLOCK, calls)

        // ストリーミング プレビュー: 最後に完全に没収されていないハーフマーク (<｜｜DSML｜, <｜…) を表示しません。
        val lt = rest.lastIndexOf('<')
        if (lt >= 0) {
            val tail = rest.substring(lt + 1)
            if (!tail.contains('>') && PARTIAL_TAIL.matches(tail)) {
                rest = rest.substring(0, lt)
            }
        }
        if (calls.isEmpty() && rest == text) return Scan(text, emptyList())
        // マークを剥がした後に残った空行をクリーンアップします。テキスト内の改行は移動しません
        rest = rest.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        return Scan(rest, calls)
    }

    /** は返信内のテキストを削除します。通話を復元するときに [Part.ToolCall] を追加し、終了時に ToolUse としてマークします。 */
    fun repair(reply: ModelReply): ModelReply {
        if (reply.parts.none { it is Part.Text || it is Part.Thinking }) return reply
        val out = mutableListOf<Part>()
        val calls = mutableListOf<Part.ToolCall>()
        var changed = false
        for (p in reply.parts) {
            if (p is Part.Thinking) {
                // 思考内容から漏れたマークは剥がれただけで通話とはみなされず、テキストからのみ通話が認識できます。
                val cleaned = stripMarkup(p.text)
                if (cleaned != p.text) changed = true
                if (cleaned.isNotEmpty()) out += Part.Thinking(cleaned)
                continue
            }
            if (p !is Part.Text) {
                out += p
                continue
            }
            val scan = scan(p.text)
            if (scan.text != p.text || scan.calls.isNotEmpty()) changed = true
            if (scan.text.isNotEmpty()) out += Part.Text(scan.text)
            scan.calls.forEach { c -> calls += Part.ToolCall(callId(c, calls.size), c.name, c.argsJson) }
        }
        if (!changed) return reply
        out += calls
        return ModelReply(out, if (calls.isEmpty()) reply.stopReason else StopReason.ToolUse, reply.usage)
    }

    /** パラメーター ブロック → ツール パラメーター JSON。 `string="false"` は JSON 値として収集され、残りは文字列として収集されます。 */
    private fun paramsJson(block: String): String {
        if (PARAM.find(block) == null) return "{}"
        return buildJsonObject {
            for (m in PARAM.findAll(block)) {
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) continue
                val raw = m.groupValues[3]
                val isString = m.groupValues[2] != "false"
                put(name, if (isString) JsonPrimitive(raw) else jsonValue(raw))
            }
        }.toString()
    }

    private fun jsonValue(raw: String) = try {
        json.parseToJsonElement(raw)
    } catch (e: Exception) {
        JsonPrimitive(raw)
    }

    /** 名前 + パラメーターは、乱数を引用せずに、安定した呼び出し番号 (同じメッセージ内のシリアル番号によって区別されます) を作成するため、単一のテストに便利です。 */
    private fun callId(c: Invocation, index: Int): String =
        "leaked_" + Integer.toHexString((c.name + "\u0000" + c.argsJson).hashCode()) + "_" + index

    private fun extractJsonBlocks(text: String, block: Regex, calls: MutableList<Invocation>): String {
        // 通話に復元できるブロックのみをピールします。復元できないブロックはそのままにして、AgentLoop のリーク呼び出しチェック (LeakedToolCall) に任せて戻ってやり直します。
        // 剥がした後は前の文「確認します」だけが残り、それが最終回答として渡されます。
        return block.replace(text) { m ->
            val found = invocationsOf(m.groupValues[1])
            if (found.isEmpty()) m.value else " ".also { calls += found }
        }
    }

    private fun invocationsOf(body: String): List<Invocation> {
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val items = when (element) {
            is JsonArray -> element.toList()
            is JsonObject -> listOf(element)
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val args = obj["arguments"] ?: obj["parameters"]
            Invocation(name, when (args) {
                null -> "{}"
                is JsonPrimitive -> (args as JsonPrimitive).contentOrNull ?: "{}"
                else -> args.toString()
            })
        }
    }
}
