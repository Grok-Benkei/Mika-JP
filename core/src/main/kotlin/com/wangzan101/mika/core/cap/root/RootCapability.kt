package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

internal const val ROOT = "root"

/** rootコマンドの実行ポート。root済みの端末でユーザーが明示的に許可したときだけアプリが渡し、未許可ならツールも登録しない。 */
interface RootPort {
    /** [command] をrootで実行する。上限は [timeoutMs]。実装側でIOスレッドへ切り替える。 */
    suspend fun run(command: String, timeoutMs: Long): RootRun
}

/** rootコマンド1件の実行結果。 */
sealed interface RootRun {
    /**
     * [stdout] と [stderr] は各1MBまで保持し、超過分は [droppedBytes] に数える。[timedOut] は制限時間による中断を示す。
     */
    data class Done(
        val exitCode: Int, val timedOut: Boolean, val stdout: String, val stderr: String, val droppedBytes: Long, val durationMs: Long,
    ) : RootRun

    /** suが実行を拒否した。root管理アプリで許可が取り消された場合も含む。 */
    data object Denied : RootRun

    /** suから応答がなかった。 */
    data object NoAnswer : RootRun

    /** 端末でsuが見つからなくなった。 */
    data object Unavailable : RootRun
}

/**
 * rootコマンドは [RootRunCommand] だけを提供する。明示許可されたポートがある場合だけ登録し、画面を動かさない。
 * 変更するコマンドは確認モードで本人へ確認する（[RootRunCommand.approvalRisk]）。
 */
internal class RootCapability(val port: RootPort) : Capability {
    override val id = ROOT

    override fun status(): CapabilityStatus = CapabilityStatus.Available

    override fun tools(): List<AgentTool> = RootGroup.tools

    override fun sections(env: PromptEnv): List<String> =
        if (RootGroup.tools.any { it.name in env.offered }) listOf(env.gate(Prompts.capability(ROOT))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    companion object {
        val KEY = CapabilityKey<RootCapability>(ROOT)
    }
}

internal val ToolContext.rootPort: RootPort? get() = this[RootCapability.KEY]?.port
