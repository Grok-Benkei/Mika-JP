package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.tool.OutputClip
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.int
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 確認カードでアプリ名ではなくrootコマンドと表示するための識別子。 */
const val ROOT_CONFIRM_APP = "root"

/** コマンドの文字数上限。変更コマンドは確認カードに全文を表示する。 */
private const val COMMAND_MAX = 4_000
private const val DEFAULT_TIMEOUT_SEC = 30
private const val MAX_TIMEOUT_SEC = 300

/**
 * su経由でAndroidシェルコマンドをroot（uid 0）として実行し、終了コードと出力を返す。長い出力は先頭と末尾を残す。
 * [RootCommandClass] の読み取りは直接実行し、他は引数に基づいて確認要求を申告する。確認モードでは全文を示して承認を待つ。
 * 画面は操作せず、操作記録には残す。
 */
internal object RootRunCommand : AgentTool {
    override val capability: String get() = ROOT
    override val verb = Verb.RootCommand
    override val label = "rootコマンド"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("root_run_command", Prompts.tool("root_run_command"), mapOf(
        "command" to buildJsonObject {
            put("type", "string"); put("description", "rootで実行するAndroidシェルコマンド。1〜${COMMAND_MAX}文字。")
            put("minLength", 1); put("maxLength", COMMAND_MAX)
        },
        "timeoutSec" to int("制限時間（秒）。1〜$MAX_TIMEOUT_SEC、既定は$DEFAULT_TIMEOUT_SEC。時間に達すると実行を中断します。", 1, MAX_TIMEOUT_SEC),
    ), listOf("command"))

    override fun available(ctx: ToolContext) = ctx.rootPort != null

    /** 読み取りは確認不要。それ以外は画面外の変更操作として、このコマンド自体を確認内容にする。 */
    override fun approvalRisk(args: JsonObject): ApprovalRisk {
        val command = (args["command"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return if (command.isNotBlank() && RootCommandClass.classify(command) == RootCommandClass.Kind.ReadOnly) ApprovalRisk(ActionRisk.Read)
        else ApprovalRisk(ActionRisk.Irreversible, ask = ask(command))
    }

    /** 確認カードへ表示する内容は、このコマンドの全文。 */
    fun ask(command: String) = ConfirmationRequest(IrrevAction.other, "", command, null, ROOT_CONFIRM_APP)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val command = a.str("command", max = COMMAND_MAX)!!
        val timeoutSec = a.int("timeoutSec", 1, MAX_TIMEOUT_SEC) ?: DEFAULT_TIMEOUT_SEC
        return when (val r = ctx.rootPort!!.run(command, timeoutSec * 1000L)) {
            is RootRun.Done -> done(r, command)
            RootRun.Denied -> fail("root_denied", Feedback["root.denied"])
            RootRun.NoAnswer -> fail("root_no_answer", Feedback["root.no_answer"])
            RootRun.Unavailable -> fail("root_unavailable", Feedback["root.unavailable"])
        }
    }

    /**
     * 出力は失敗時も観察事実として記録する。コマンドはモデル自身の作成物なので、出力中の単なるコマンドの復唱は根拠にしない。
     * 他アプリのデータを含むため、出力は指示ではなくデータとして扱う。
     */
    private fun done(r: RootRun.Done, command: String): ToolExecution {
        val (out, outClipped) = OutputClip.clip(r.stdout)
        val (err, errClipped) = OutputClip.clip(r.stderr, head = 1_000, tail = 3_000)
        val hints = listOfNotNull(
            Feedback["root.timed_out"].takeIf { r.timedOut },
            Feedback["root.truncated"].takeIf { outClipped || errClipped || r.droppedBytes > 0 },
        )
        return ToolExecution(buildJsonObject {
            put("ok", r.exitCode == 0 && !r.timedOut); put("exitCode", r.exitCode); put("timedOut", r.timedOut); put("durationMs", r.durationMs)
            put("stdout", Untrusted.wrap(out)); put("stderr", Untrusted.wrap(err))
            if (r.droppedBytes > 0) put("droppedBytes", r.droppedBytes)
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, observations = listOf(Observation(ObsKind.Output, "終了コード ${r.exitCode}:\n$out", source = ROOT,
            place = Place.command("root_run_command「${command.take(200)}」"), trust = Trust.Untrusted, authored = command)))
    }

    private fun fail(error: String, message: String) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("recoverable", false)
    })

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("command")
}

internal object RootGroup {
    val tools: List<AgentTool> = listOf(RootRunCommand)
}
