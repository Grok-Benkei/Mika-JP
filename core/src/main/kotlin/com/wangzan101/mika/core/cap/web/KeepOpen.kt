package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ページが閉じたままになっているのはなぜですか。 [wire] が会話に記録されます。時間が経過するとメモリが不足し、システムがメモリを再利用し、レンダリング プロセスがクラッシュし、ユーザーがメモリを閉じて別のページを離れ、ミカは終了します。
 */
enum class KeptPageClose(val wire: String) {
    Expired("expired"), Memory("memory"), Crashed("crashed"), User("user"), Replaced("replaced"), AppExit("app_exit");

    companion object {
        fun of(wire: String?): KeptPageClose? = entries.firstOrNull { it.wire == wire }
    }
}

/** タスク完了後に残されたページは最長でも数時間保持されます（その時点で自動的に閉じられます）。 */
const val KEEP_OPEN_HOURS = 2

/**
 * タスクが終了すると、このタスクのすべての Web ページがデフォルトで閉じられます。応答後にページを実行し続けたい場合 (その後、再生、停止し、自動的に終了するのを待ちます)、これを使用して現在のページを終了します。
 * 「完了したら保持する」と書き留めるだけで、現時点では何も移動されません。グローバルに残るのは 1 ページだけであり、新しいページを保持すると古いページが閉じられます。元に戻すには keep=false を使用します。
 */
internal object KeepOpen : WebTool {
    override val verb = Verb.KeepOpen
    override val label = "Webページを開いたままにする"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("web_keep_open",
        Prompts.tool("web_keep_open", "hours" to KEEP_OPEN_HOURS),
        mapOf(
            "reason" to str("Why this page must keep running after the task, in a few words (e.g. the user wants the video to keep playing)."),
            "keep" to buildJsonObject { put("type", "boolean"); put("description", "false takes back an earlier keep in this task (default true).") },
        ), listOf("reason"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val reason = a.str("reason", max = 200)!!.trim()
        val keep = a.bool("keep", true)
        val r = try { ctx.web!!.keepOpen(keep, reason) } catch (e: WebTabCrashed) { return failCode("tab_crashed") }
            catch (e: WebTabIsUsers) { return failCode("users_page") }
            catch (e: UnsupportedOperationException) { return failCode("keep_open_unavailable") }
            ?: return failCode("no_tab_open")
        return ok {
            put("keep", r.keep)
            r.tabId?.let { put("tabId", it) }
            r.host?.let { put("site", it) }
            r.title?.let { put("title", it) }
            if (r.keep) {
                put("hours", r.hours)
                r.replaces?.let { put("replaces", it) }
                put("hint", if (r.replaces != null) Feedback.format("keep_open.replaces", "site" to r.replaces, "hours" to r.hours.toString())
                    else Feedback.format("keep_open.kept", "hours" to r.hours.toString()))
            } else put("hint", Feedback["keep_open.taken_back"])
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("reason")
}
