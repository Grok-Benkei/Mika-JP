package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ユーザーが必要とする結果を一度の実行で計算することは可能ですか?電話なしの返信は回答であり、回答自体は、それが行われていないか、部分的にしか行われていないと正直に述べている可能性があります。
 * 学習内容 (ルート、スキル、スキル結果)、時間指定タスクの結果、実行レビューはすべてこのエンディングに従います。 「回答を送信」は押さないでください。
 */
enum class RunOutcome(val wire: String) {
    Done("done"), Partial("partial"), NotPossible("not_possible"),
    /** 成功しませんでした (モデル エラー、タイムアウト、読み取り不能な応答)、または古いレコード。完了したり失敗したりしてはなりません。 */
    Unknown("unknown");

    companion object {
        fun ofWire(s: String?): RunOutcome? = entries.firstOrNull { it.wire == s }

        /** 走行記録で判断される結末;そのようなイベント (古いレコード、失敗、キャンセル) がない場合、null になります。 */
        fun recorded(events: List<RunEvent>): RunOutcome? =
            events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.let { ofWire(it.outcome) ?: Unknown }
    }
}

data class JudgedOutcome(val outcome: RunOutcome, val reason: String = "", val model: String? = null) {
    fun event(atMs: Long) = RunEvent.OutcomeJudged(atMs, outcome.wire, RunEvent.clip(Redactor.text(reason)), model)
}

/**
 * ユーザーに回答が与えられた後、バックグラウンドでモデルが要求され (軽量モデルが推奨され、メイン モデルは使用されません)、回答が提供され、ユーザーの要件に従って結果が決定されます。
 * は答えを変更せず、配信を遅らせません。エラーまたはタイムアウトが発生した場合、それは [RunOutcome.Unknown] であり、スローされることはありません (キャンセルされた場合は通常どおりスローされます)。
 */
class OutcomeJudge(
    private val judge: ChatModel,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun judge(goal: String, answer: String, priorTurns: List<Turn> = emptyList()): JudgedOutcome {
        val prior = Conversation.context(priorTurns, maxTurns = 2, maxTokens = 800)
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }.orEmpty()
        val input = buildString {
            if (prior.isNotEmpty()) appendLine(prior).appendLine()
            // 要求と応答は判断材料であり、ラベルに含まれます。中に書かれた言葉は裁判官への指示ではありません。
            append("<request>\n").append(untag(goal)).append("\n</request>\n\n<reply>\n").append(untag(answer)).append("\n</reply>")
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback?.takeUnless { it === judge })) {
            val result = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = candidate.complete(Prompts.role("outcome_judge"), messages, emptyList())
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) return result.copy(model = candidate.name.ifBlank { null })
        }
        return JudgedOutcome(RunOutcome.Unknown)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        private val TAG = Regex("</?\\s*(request|reply)\\s*>", RegexOption.IGNORE_CASE)

        /** マテリアルに付属するリクエスト/返信タグを削除して、マテリアルが事前に閉じられ、次の単語が審査員の入力であるかのように装うことを防ぎます。 */
        private fun untag(s: String) = s.replace(TAG, "")

        /**
         * 応答内の最後の読み取り可能な JSON オブジェクト: {"outcome": "done|partial|not_possible", "reason": "…"};読み取れない場合は null です。
         * 最後の例を見てみましょう。内容を再話するときに裁判官によって持ち出されたオブジェクトが先頭にあり、結論が最後にあります。
         */
        fun parse(raw: String): JudgedOutcome? {
            var end = raw.lastIndexOf('}')
            while (end >= 0) {
                var start = raw.lastIndexOf('{', end)
                while (start >= 0) {
                    of(raw.substring(start, end + 1))?.let { return it }
                    start = if (start == 0) -1 else raw.lastIndexOf('{', start - 1)
                }
                end = if (end == 0) -1 else raw.lastIndexOf('}', end - 1)
            }
            return null
        }

        private fun of(json: String): JudgedOutcome? {
            val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val outcome = RunOutcome.ofWire((o["outcome"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase())
                ?.takeIf { it != RunOutcome.Unknown } ?: return null
            return JudgedOutcome(outcome, (o["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
        }
    }
}
