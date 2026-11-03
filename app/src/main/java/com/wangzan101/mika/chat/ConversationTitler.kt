package com.wangzan101.mika.chat

import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.agent.JapaneseOutput
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 会話のタイトルは最初の発言から一度だけ生成する。モデルには発言の文字だけを渡し、画面や履歴は渡さない。
 * モデルが日本語のタイトルを返さない場合は日本語の既定タイトルを使う。エラーやタイムアウトでタイトルを取得できなければ、従来どおり最初の発言を表示する。
 */
object ConversationTitler {
    const val TIMEOUT_MS = 8_000L
    private const val MAX_TITLE = 16
    private const val MAX_INPUT = 400
    internal const val FALLBACK_TITLE = "新しいチャット"

    /**
     * ダイアログ [id] タイトルがなく、ラウンドが保存されていない場合は、[first] を押してノートを開始します。 [models] 順番に試してみてください (最初に軽量モデル、最後にメイン モデル)。
     * 画面だけ送られてきて文字が無いのは許せません。決して投げないでください（キャンセルされた場合は通常通り投げます）。
     */
    suspend fun ensure(store: ConversationStore, id: String, first: String, models: () -> List<ChatModel>) {
        if (store.title(id) != null || store.turns(id).isNotEmpty()) return
        val title = generate(models(), first) ?: return
        store.setTitle(id, title)
    }

    suspend fun generate(models: List<ChatModel>, first: String, timeoutMs: Long = TIMEOUT_MS): String? {
        // Web ページの説明は最初の文ではありません。選択した単語のみが含まれます。単語がない場合は、選択した単語を押して開始します。
        val page = AttachedPage.parse(first)
        val said = page.words.ifBlank { page.page?.selection.orEmpty() }
        val words = UserScreenMessage.parse(said).words.take(MAX_INPUT)
        if (words.isBlank()) return null
        val input = "<message>\n" + words.replace(TAG, "") + "\n</message>"
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        var rejectedTitle = false
        for (m in models.distinct()) {
            val title = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = m.complete(Prompts.role("conversation_title"), messages, emptyList())
                    clean(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (title != null) {
                if (JapaneseOutput.isJapaneseProse(title)) return title
                rejectedTitle = true
            }
        }
        return if (rejectedTitle) FALLBACK_TITLE else null
    }

    private val TAG = Regex("</?\\s*message\\s*>", RegexOption.IGNORE_CASE)
    private val WRAP = mapOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’', '「' to '」', '『' to '』', '《' to '》', '`' to '`', '*' to '*')
    private val TAIL = "。.!！?？,，;；:：、 ".toSet()

    /** 返信の最初の行に単語がある場合は、それをタイトルとして使用する必要があります。両端の引用符と末尾の句読点、および「タイトル:」プレフィックスを削除します。長すぎる場合は切り捨てられます。空の場合は null になります。 */
    fun clean(raw: String): String? {
        var t = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = t.replace(Regex("^(タイトル|題名|标题|title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^#{1,6}\\s+"), "").trim()
        while (true) {
            t = t.trimEnd { it in TAIL }.trim()
            if (t.length < 2 || WRAP[t.first()] != t.last()) break
            t = t.substring(1, t.lastIndex).trim()
        }
        return t.take(MAX_TITLE).takeIf(String::isNotBlank)
    }
}
