package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ユーザーが途中で停止した後、今回何が行われたのか (何が行われたのか、何が見られたのか、バッチで何回行われたのか、何が行われていないのか) を説明するために数行を書き込みます。
 * は、このラウンドの回答としてユーザーに表示され、会話に記録されます (次の文「継続」は上記で説明しています)。この実行レコードを読み取るだけで、それ以降は何も操作しません。
 * 一度型式をお問い合わせください（[writer]が優先、エラーがある場合は[fallback]に置き換えてください）。工具は提供されません。合計の最大待機時間は [timeoutMs] です。モデル、エラー、またはタイムアウトがない場合は、レコードを押してセクション ([plain]) を直接入力します。
 * エンディングはキャンセルされたままですが、この段落が追加されます。
 */
class StopSummary(
    private val writer: ChatModel?,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * [events] は、この実行で記録されたイベントです (少なくとも、記録がない場合はアクティブです)。 [draft] は、停止時に書き込まれているワードです (空でも構いません)。
     * [label] ツール名をインターフェイス名 [appLabel] に置き換えます。 パッケージ名をアプリ名に置き換えます (「Web サイトのドメイン名」にはドメイン名のみを残します)。
     */
    suspend fun summarize(
        goal: String, events: List<RunEvent>, draft: String?,
        label: (String) -> String = { it }, appLabel: (String) -> String = { it },
    ): String {
        val input = facts(goal, events, draft, label, appLabel)
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        val written = withTimeoutOrNull(timeoutMs) {
            for (candidate in listOfNotNull(writer, fallback?.takeUnless { it === writer })) {
                val text = try {
                    candidate.complete(Prompts.role("stop_summary"), messages, emptyList())
                        .parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // モデルが日本語で書かなければ採用しない(外国語の要約をそのまま渡さない)。記録から組む日本語の要約に切り替える。
                if (!text.isNullOrBlank() && ReplyLanguage.mismatch(text.clip(MAX_REPLY)) == null) return@withTimeoutOrNull text.clip(MAX_REPLY)
            }
            null
        }
        return written ?: plain(goal, events, draft, label, appLabel)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        private const val MAX_REPLY = 1200
        private const val MAX_LINES = 60
        private const val MAX_READS = 2
        private const val READ_CHARS = 800
        private const val NOTE_CHARS = 120
        private const val DRAFT_CHARS = 1200

        /** 何か説明できることはありますか: 少なくとも 1 つのことを行った、または停止したときにすでに書いていた。そうでない場合は、単に「停止しました」と表示されます。 */
        fun worthSaying(events: List<RunEvent>, draft: String?): Boolean =
            events.any { it is RunEvent.Activity } || !draft.isNullOrBlank()

        /**
         * モデルに渡された資料 (上限あり、画像なし): ユーザーの要件、完了した内容 (そのステップでモデルを作成する意図で 1 行ずつ)、最近読んだコンテンツ、
         * 止まったときに半分くらい書いてしまいました。それはラベルにすべて含まれています。中の言葉は重要なものであり、それを書いた人への指示ではありません。
         */
        fun facts(goal: String, events: List<RunEvent>, draft: String?, label: (String) -> String = { it }, appLabel: (String) -> String = { it }): String {
            val notes = notes(events)
            val lines = mutableListOf<String>()
            val said = mutableSetOf<Int>()
            var lastObserve: String? = null
            for (a in events.filterIsInstance<RunEvent.Activity>()) {
                val where = a.target?.let { where(it, appLabel) }
                val what = listOfNotNull(label(a.label), where, a.title?.takeIf(String::isNotBlank)).joinToString(" · ")
                val tags = listOfNotNull("外部送信済み".takeIf { a.outward && !a.failed }, "失敗".takeIf { a.failed })
                val line = "ステップ ${a.step}: $what" + if (tags.isEmpty()) "" else "(${tags.joinToString("、")})"
                // 同じ場所を続けて読むときは 1 行だけ覚えてください
                if (a.kind == "observe" && line == lastObserve) continue
                lastObserve = if (a.kind == "observe") line else null
                val note = notes[a.step]?.takeIf { said.add(a.step) }
                lines += if (note == null) line else "$line — 目的: $note"
            }
            val shown = if (lines.size <= MAX_LINES) lines
                else lines.take(20) + "…(途中の ${lines.size - MAX_LINES} 件を省略)…" + lines.takeLast(MAX_LINES - 20)
            val reads = events.filterIsInstance<RunEvent.Observed>().filter { it.kind == ObsKind.Read.wire && it.text.isNotBlank() }
                .takeLast(MAX_READS).map { it.text.clip(READ_CHARS) }
            return buildString {
                append("<request>\n").append(untag(goal)).append("\n</request>\n\n")
                append("<actions>\n").append(untag(shown.joinToString("\n").ifEmpty { "(まだ操作していません)" })).append("\n</actions>")
                if (reads.isNotEmpty()) append("\n\n<last_read>\n").append(untag(reads.joinToString("\n----\n"))).append("\n</last_read>")
                draft?.trim()?.takeIf(String::isNotEmpty)?.let { append("\n\n<draft>\n").append(untag(it.clip(DRAFT_CHARS))).append("\n</draft>") }
            }
        }

        /**
         * はモデルによって説明する必要はなく、どのアプリや Web サイトが訪問されたか、さまざまな操作が何回実行されたか、外部に送信された回数 (およびそのステップの目的)、などの記録に基づいてのみ説明されます。
         * いくつかの手順が失敗し、停止したときに単語の半分を書きました。最後に未完成部分を続けて仕上げることはしませんでした。
         */
        fun plain(goal: String, events: List<RunEvent>, draft: String?, label: (String) -> String = { it }, appLabel: (String) -> String = { it }): String {
            val acts = events.filterIsInstance<RunEvent.Activity>()
            val notes = notes(events)
            val lines = mutableListOf<String>()
            val places = acts.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
            val seen = acts.filter { it.kind == "observe" && !it.failed && !it.title.isNullOrBlank() }
                .map { listOfNotNull(it.target?.let { t -> where(t, appLabel) }, it.title?.let { title -> "「$title」" }).joinToString("の") }.distinct()
            val done = acts.filter { it.kind != "observe" && !it.failed && !it.outward }
                .groupingBy { label(it.label) }.eachCount()
            val outward = acts.filter { it.outward && !it.failed }
            val failed = acts.count { it.failed }
            if (places.isNotEmpty()) lines += "開いた場所: " + list(places, 4)
            if (seen.isNotEmpty()) lines += "確認した内容: " + list(seen, 4)
            if (done.isNotEmpty()) lines += "行った操作: " + done.entries.joinToString("、") { (what, n) -> if (n > 1) "$what $n 回" else what }
            if (outward.isNotEmpty()) {
                val where = outward.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
                val which = outward.map { it.step }.distinct().mapNotNull(notes::get).distinct().map { "「$it」" }
                lines += "外部への送信・提出は ${outward.size} 回" + (if (where.isEmpty()) "" else "(${where.joinToString("、")})") +
                    (if (which.isEmpty()) "" else ":" + list(which, 5))
            }
            if (failed > 0) lines += "$failed ステップが失敗しました"
            draft?.trim()?.takeIf(String::isNotEmpty)?.let { lines += "停止時の書きかけ（原文）: 「${it.clip(200)}」" }
            val head = if (acts.isEmpty()) "停止しました。操作はまだ始めていません。" else "停止しました。停止前の状況:"
            val body = lines.joinToString("\n") { "- $it" }
            val tail = "「${goal.trim().clip(40)}」の未完了部分は、その後実行していません。"
            return listOf(head, body, tail).filter(String::isNotEmpty).joinToString("\n")
        }

        /** ユーザーに表示されるアクティブ オブジェクトの説明: 「Web サイト ドメイン名」はドメイン名のみを残し、パッケージ名はアプリ名に置き換えられ、その他 (Web サイト アドレス、クエリ ワード) はそのまま残ります。空のものはnullです。 */
        private fun where(target: String, appLabel: (String) -> String): String? = when {
            target.isBlank() -> null
            SITE_PREFIXES.any(target::startsWith) -> target.substringAfter(' ').trim()
            PACKAGE_NAME_RE.matches(target) -> appLabel(target)
            else -> target
        }.takeUnless { it.isNullOrBlank() }

        /** アクティブなオブジェクトがアプリまたは Web サイトの場合、その名前。それ以外の場合 (Web アドレス、クエリワード、ファイル) は null です。 */
        private fun placeOf(target: String, appLabel: (String) -> String): String? =
            if (SITE_PREFIXES.any(target::startsWith) || PACKAGE_NAME_RE.matches(target)) where(target, appLabel) else null

        /** 実行レコードの Web ページ上のオブジェクトは、「Web サイト <ドメイン名>」として書き込まれます。 */
        private val SITE_PREFIXES = listOf("ウェブサイト ", "网站 ")

        private fun list(items: List<String>, max: Int) =
            items.take(max).joinToString("、") + if (items.size > max) " ほか ${items.size} 件" else ""

        /** モデルの各ステップを記述する場合: まず、呼び出しパラメーターの理由ではなく、応答で進行状況の説明を使用します。 */
        private fun notes(events: List<RunEvent>): Map<Int, String> {
            val out = mutableMapOf<Int, String>()
            for (r in events.filterIsInstance<RunEvent.ModelReply>()) {
                if (!r.acted || r.tool == null) continue
                val said = r.text.trim().ifEmpty { null } ?: reason(r.argsJson)
                said?.takeIf(String::isNotBlank)?.let { out[r.step] = it.replace('\n', ' ').clip(NOTE_CHARS) }
            }
            return out
        }

        private fun reason(argsJson: String?): String? = argsJson?.let {
            runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.get("reason") as? JsonPrimitive }.getOrNull()?.contentOrNull?.trim()
        }

        private val TAG = Regex("</?\\s*(request|actions|last_read|draft)\\s*>", RegexOption.IGNORE_CASE)

        /** 素材に含まれるタグを事前に閉じて、以下の文言を書いた人の入力であるかのように装うことを防ぐために、素材に含まれるタグを削除します。 */
        private fun untag(s: String) = s.replace(TAG, "")

        private fun String.clip(max: Int) = if (length <= max) this else take(max) + "…"
    }
}
