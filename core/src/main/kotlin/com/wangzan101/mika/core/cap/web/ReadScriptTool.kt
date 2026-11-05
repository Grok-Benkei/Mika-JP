package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.knowledge.SiteCommandFile
import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.uistate.lastSnapshot

private fun timeoutProp() = int("Time limit, 1000-$READ_SCRIPT_MAX_TIMEOUT_MS ms (default $READ_SCRIPT_DEFAULT_TIMEOUT_MS).", 1000, READ_SCRIPT_MAX_TIMEOUT_MS)

/** 読み取りスクリプト (データ読み取りスクリプト、サイト コマンド) がポート上で実行されます。結果は Web ページから取得され、モデルに送信されると信頼できないとしてマークされ、観察として検証のためにも送信されます。 */
private suspend fun readScript(ctx: ToolContext, code: String, args: JsonObject?, timeoutMs: Int, what: String): ToolExecution {
    val r = try {
        ctx.web!!.evaluate(code, timeoutMs.toLong(), args)
    } catch (e: WebTabCrashed) {
        return failCode("tab_crashed")
    } catch (e: WebTabHandedOver) {
        return failCode("handed_over")
    } catch (e: WebDialogOpen) {
        return failCode(DIALOG_OPEN)
    }
    val url = ctx.lastSnapshot?.web?.url
    val held = com.wangzan101.mika.core.security.EgressPolicy.assess(
        com.wangzan101.mika.core.security.EgressScene(com.wangzan101.mika.core.security.EgressWindow(webUrl = url, isWeb = true)),
    )
    if (r is EvalJs.Ok && !held.allowBody) return ok { put("egressBlocked", true); put("reason", held.reason) }
    return when (r) {
        is EvalJs.Failed -> fail(r.error, r.message)
        is EvalJs.Ok -> ok {
            put("result", Untrusted.wrap(r.json))
            put("truncated", r.truncated)
            if (r.fresh) put("state", "new")
        }.observing(webRead("$what(${url ?: "現在のWebページ"})\n${r.json}", url))
    }
}

/** データ読み取りスクリプト: 現在のページの読み取り環境で読み取り専用で実行します (実際の DOM、同一オリジン GET)。ページを移動するには、アクション ツールを使用します。 */
internal object EvaluateJs : WebTool {
    override val verb = Verb.EvaluateJs
    override val label = "Webページデータを読み取る"
    override val risk = Risk.Read
    override val def = ToolDef("web_evaluate_js",
        Prompts.tool("web_evaluate_js", "result_max" to EVAL_RESULT_MAX_CHARS, "default_seconds" to READ_SCRIPT_DEFAULT_TIMEOUT_MS / 1000),
        mapOf(
            "script" to str("JavaScript, 1-$EVAL_SCRIPT_MAX_CHARS characters: one expression (e.g. `document.title`), or the body of an async function that returns the result."),
            "timeoutMs" to timeoutProp(),
        ),
        listOf("script"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val script = a.str("script", max = EVAL_SCRIPT_MAX_CHARS)!!
        PageScriptProtocol.codeProblem(script)?.let { throw ArgError("script", it) }
        val timeout = a.int("timeoutMs", 1000, READ_SCRIPT_MAX_TIMEOUT_MS) ?: READ_SCRIPT_DEFAULT_TIMEOUT_MS
        return readScript(ctx, script, null, timeout, "Webページデータスクリプトの結果")
    }
}

/**
 * サイト コマンド: 現在のサイト ナレッジで宣言された `scripts/<name>.js` は、読み取りワールドで読み取り専用で実行され、パラメーターは宣言どおりに検証されます。
 * は、組み込みナレッジにサイト コマンドがある場合にのみ提供されます。現在のページが属しているサイトのコマンドのみが認識されます。
 */
internal object RunSiteCommand : WebTool {
    override val verb = Verb.SiteCommand
    override val label = "サイトコマンドを実行"
    override val risk = Risk.Read
    override fun available(ctx: ToolContext) = ctx.web != null && ctx.config.skills?.hasSiteCommands() == true
    override val def = ToolDef("web_run_site_command",
        Prompts.tool("web_run_site_command", "result_max" to EVAL_RESULT_MAX_CHARS),
        mapOf(
            "name" to str("Command name, as listed for the current site."),
            "args" to buildJsonObject {
                put("type", "object"); put("description", "Arguments the command declares, name to value (omit when it takes none).")
            },
            "timeoutMs" to timeoutProp(),
        ),
        listOf("name"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val name = a.str("name", max = 40)!!.trim()
        val args = when (val v = a.o["args"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> v
            else -> throw ArgError("args", "must be an object")
        }
        val timeout = a.int("timeoutMs", 1000, READ_SCRIPT_MAX_TIMEOUT_MS) ?: READ_SCRIPT_DEFAULT_TIMEOUT_MS
        val url = currentUrl(ctx) ?: return failCode("no_tab_open")
        val host = UrlHost.of(url) ?: return fail("unknown_command", "The current page ($url) is not on a site with commands.")
        val commands = ctx.config.skills?.siteCommands(host).orEmpty()
        val command = commands.firstOrNull { it.decl.name == name }
            ?: return fail("unknown_command",
                if (commands.isEmpty()) "$host has no site commands; read the page with web_read_page or web_evaluate_js."
                else "$host has no command '$name'. Its commands: ${commands.joinToString { it.decl.name }}.")
        SiteCommandFile.checkArgs(command.decl, args)?.let { throw ArgError("args", it) }
        return readScript(ctx, command.body, args, timeout, "サイトコマンド ${command.site}/${command.decl.name} の結果")
    }

    /** 現在のタグの URL: タグ リストに従って、読み取れない場合は、最新の画面読み取りが使用されます。 */
    private suspend fun currentUrl(ctx: ToolContext): String? {
        val tabs = try { ctx.web!!.tabs() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        return tabs.firstOrNull { it.current }?.url?.takeIf { it.isNotBlank() } ?: ctx.lastSnapshot?.web?.url
    }
}
