package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.prompt.Feedback
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.fail
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.uistate.lastSnapshot

/**
 * ページ スクリプト: ページを移動しないでください。スクリプト内の各アクションは実行入口 (通常どおり、ガード、レベル、不可逆決定、およびレコード) を通じて返されます。
 * ただし、ロック画面レベルを読み取り専用として呼び出すことはできません。
 */
internal object WebScriptTool : WebTool {
    override val verb = Verb.Script
    override val risk = Risk.Write
    override val composite = true
    override val def = ToolDef("web_script",
        Prompts.tool("web_script", "max_actions" to SCRIPT_MAX_ACTIONS, "max_reads" to SCRIPT_MAX_READS, "max_seconds" to SCRIPT_MAX_TIMEOUT_MS / 1000),
        mapOf(
            "code" to buildJsonObject {
                put("type", "string"); put("minLength", 1); put("maxLength", SCRIPT_CODE_MAX_CHARS)
                put("description", "Body of an async function, e.g. `const s = await mika.snapshot(); return s.elements.filter(e => e.role === 'link').map(e => e.name);`")
            },
            "timeoutMs" to buildJsonObject {
                put("type", "integer"); put("minimum", 1000); put("maximum", SCRIPT_MAX_TIMEOUT_MS)
                put("description", "Time limit for the whole script, 1000-$SCRIPT_MAX_TIMEOUT_MS ms (default $SCRIPT_DEFAULT_TIMEOUT_MS).")
            },
        ), listOf("code"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val code = a.str("code", max = SCRIPT_CODE_MAX_CHARS)!!
        PageScriptProtocol.codeProblem(code)?.let { throw ArgError("code", it) }
        val timeoutMs = (a.int("timeoutMs", 1000, SCRIPT_MAX_TIMEOUT_MS) ?: SCRIPT_DEFAULT_TIMEOUT_MS).toLong()
        val calls = ScriptCalls(ctx.callId, ctx.sub, ctx.tools)
        val run = try {
            // 時間制限はポートによって管理されます。スクリプト自体の時間のみがカウントされ、アクション (ロック画面レベルの待機を含む) が実行中に時間外に実行されたり、途中で捕らえられたりすることはありません。
            ctx.web!!.runScript(code, timeoutMs, calls)
        } catch (e: WebTabCrashed) {
            ScriptRun.Failed("tab_crashed", FAILURE_MESSAGES.getValue("tab_crashed"))
        } catch (e: WebTabHandedOver) {
            ScriptRun.Failed("handed_over", FAILURE_MESSAGES.getValue("handed_over"))
        } catch (e: WebDialogOpen) {
            ScriptRun.Failed(DIALOG_OPEN, FAILURE_MESSAGES.getValue(DIALOG_OPEN))
        } catch (e: ScriptAbort) {
            ScriptRun.Failed(e.error, e.message.orEmpty())
        }
        val content = calls.content(run)
        val result = (content["result"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
        val seen = result?.let { webRead("ページスクリプトの結果（${ctx.lastSnapshot?.web?.url ?: "現在のWebページ"}）\n$it", ctx.lastSnapshot?.web?.url) }
        // スクリプトの結果は Web ページから取得されます。モデルに渡されると、信頼できないマークが適用されます。
        val shown = if (result == null) content else JsonObject(content +
            ("result" to JsonPrimitive(Untrusted.wrapWithin(result, EVAL_RESULT_MAX_CHARS))) +
            ("truncated" to JsonPrimitive(content["truncated"] == JsonPrimitive(true) || result.length > EVAL_RESULT_MAX_CHARS - Untrusted.OVERHEAD)))
        return ToolExecution(shown).observing(seen)
    }
}
