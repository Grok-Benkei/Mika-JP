package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.strArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val SANDBOX = "sandbox"

private const val TIMEOUT = "Time limit in seconds, 1-600 (default 60). The command is stopped when it runs out."

/**
 * サンドボックスのセット: デバイスがサポートする場合に提供されます (実行環境の準備ができていない場合、ツール呼び出しはサンドボックスを待機します)。パスはすべてサンドボックス内のパスです。
 * は画面操作ではありませんが、画面レベルをロックし、スクリーンガードに入りません（サンドボックスガードは別問題です）。 [com.wangzan101.mika.core.cap.sandbox.SandboxTools] で実行されます。
 */
internal interface SandboxTool : AgentTool {
    override val capability: String get() = SANDBOX
    override val offScreen: Boolean get() = true
    override fun available(ctx: ToolContext) = ctx.sandboxRunner != null
    override fun offered(ctx: ToolContext) = ctx.sandboxAvailable()
}

/** コマンドとスクリプトの出力 (失敗した出力も事実なので、そのように覚えておいてください)、読み取られたファイル、書き込みおよびエクスポート用のパスはすべて、モデルによって参照される可能性のある事実です。 */
private fun res(r: ToolExecution, k: String) = (r.content[k] as? JsonPrimitive)?.contentOrNull
private fun ok(r: ToolExecution) = r.content["ok"] == JsonPrimitive(true)

internal object RunCommand : SandboxTool {
    override val verb = Verb.RunCommand
    override val label = "ファイルを処理"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val composite = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("run_command",
        Prompts.tool("run_command"),
        mapOf(
            "command" to buildJsonObject { put("type", "string"); put("description", "The shell command, 1-20000 characters."); put("minLength", 1); put("maxLength", 20000) },
            "timeoutSec" to int(TIMEOUT, 1, 600),
            "cwd" to str("Optional working directory, a relative path under /workspace."),
        ), listOf("command"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> runCommand(a, ws, ctx.callId, ctx.sub, ctx.tools) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("command")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        // コマンドはアシスタント自身によって書かれました。それをどこに置いたかは証拠とみなされません。コマンド内の単語 (echo echo) を含む出力内の行もカウントされません。
        listOfNotNull(res(r, "output")?.let { output("終了コード ${res(r, "exitCode")}:\n$it", Place.command("run_command「${o.argStr("command")?.take(200)}」"), o.argStr("command")) })
}

internal object RunBundledScript : SandboxTool {
    override val verb = Verb.RunBundledScript
    override val label = "組み込みスクリプトを実行"
    override val risk = Risk.Write
    override val composite = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("run_bundled_script",
        Prompts.tool("run_bundled_script"),
        mapOf(
            "source" to str("Directory the script ships in, under /opt/mika/skills: the scriptSource from knowledge_read or skill_read, e.g. skills/documents."),
            "script" to str("Path of the script inside that directory, e.g. scripts/extract_pdf.py."),
            "args" to strArray("Arguments, at most 50, each passed unchanged.", 50),
            "timeoutSec" to int(TIMEOUT, 1, 600),
        ), listOf("source", "script"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, ready -> runScript(a, ws, ready.network, ctx.callId, ctx.sub, ctx.tools) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = listOfNotNull(o.argStr("source"), o.argStr("script")).joinToString("/")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        listOfNotNull(res(r, "output")?.let { output("終了コード ${res(r, "exitCode")}:\n$it", Place.command("run_bundled_script ${o.argStr("source")}/${o.argStr("script")}"), o["args"]?.toString()) })
}

internal object ReadFile : SandboxTool {
    override val verb = Verb.ReadFile
    override val label = "ファイルを読む"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("read_file",
        Prompts.tool("read_file"),
        mapOf(
            "path" to str("Sandbox path, absolute or relative to /workspace."),
            "offset" to int("Byte offset to start at (default 0).", 0),
            "limit" to int("Bytes to read, 1-262144 (default 65536).", 1, MAX_READ_BYTES),
        ), listOf("path"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> withContext(Dispatchers.IO) { readFile(a, ws) } }
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        // write_file を使用してアシスタントによって書き込まれたファイルを読み取ります。読み取られるのはそれ自体が書いた言葉であり、証拠とはみなされません。
        listOfNotNull(if (ok(r) && res(r, "path") !in log.writtenPaths) res(r, "content")?.let { output(it, Place.file("read_file ${res(r, "path")}")) } else null)
}

internal object WriteFile : SandboxTool {
    override val verb = Verb.WriteFile
    override val label = "ファイルを書く"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("write_file",
        Prompts.tool("write_file"),
        mapOf(
            "path" to str("Sandbox path under /workspace, absolute or relative."),
            "content" to str("The text to write, at most 1 MB."),
            "append" to bool("Append instead of replacing (default false)."),
        ), listOf("path", "content"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> tree.shared { withContext(Dispatchers.IO) { writeFile(a, ws) } } }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (!ok(r)) return emptyList()
        res(r, "path")?.let { log.writtenPaths += it }
        return listOf(output("write_file で ${res(r, "path")} に書き込みました（${res(r, "bytesWritten")} バイト、現在のファイルサイズは ${res(r, "size")} バイト）"))
    }
}

internal object SearchFiles : SandboxTool {
    override val verb = Verb.SearchFiles
    override val label = "ファイルを検索"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("search_files",
        Prompts.tool("search_files"),
        mapOf(
            "pattern" to str("Regular expression to search for."),
            "path" to str("Directory or file to search (default /workspace)."),
            "glob" to str("Optional file name filter, e.g. *.csv or **/*.py."),
        ), listOf("pattern"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> withContext(Dispatchers.IO) { searchFiles(a, ws) } }
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (!ok(r)) return emptyList()
        return listOfNotNull((r.content["matches"] as? JsonArray)?.joinToString("\n") { m ->
            val mo = m as? JsonObject
            "${(mo?.get("file") as? JsonPrimitive)?.contentOrNull}:${(mo?.get("line") as? JsonPrimitive)?.contentOrNull}: ${(mo?.get("text") as? JsonPrimitive)?.contentOrNull}"
        }?.let { output(it, Place.command("search_files「${o.argStr("pattern")?.take(100)}」")) })
    }
}

internal object ShareFile : SandboxTool {
    override val verb = Verb.ShareFile
    override val label = "ファイルを書き出す"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("share_file",
        Prompts.tool("share_file"),
        mapOf(
            "path" to str("Sandbox path of the file under /workspace."),
            "name" to str("Optional file name to save as (default: the file's own name)."),
        ), listOf("path"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> shareFile(a, ws) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        listOfNotNull(if (ok(r)) output("share_file で ${res(r, "savedAs")} に保存しました") else null)
}

/** サンドボックスによって得られる観察: コマンド出力、ファイルの内容、パスの書き込みとエクスポート。 [authored] は、アシスタント自身が作成したコマンドです (出力内のエコーは記憶されません)。 */
private fun output(text: String, place: Place? = null, authored: String? = null) =
    Observation(ObsKind.Output, text, source = SANDBOX, place = place, authored = authored)

internal object SandboxGroup {
    val tools: List<AgentTool> = listOf(RunCommand, RunBundledScript, ReadFile, WriteFile, SearchFiles, ShareFile)
}
