package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.guard.OffScreenGuard
import com.wangzan101.mika.core.knowledge.ScriptManifest
import com.wangzan101.mika.core.knowledge.ScriptState
import com.wangzan101.mika.core.knowledge.BundledScripts
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.workspace.SandboxPaths

/**
 * Linux サンドボックス: コマンド、スキル スクリプト、ワークスペース ファイル。デバイスがツールをサポートしている場合は、ツールを提供します (実行環境の準備ができていない場合、ツール呼び出しはツールの呼び出しを待機します)。
 * は画面操作ではありませんが、画面レベルをロックし、スクリーンガードに入りません。 [SandboxGuard] は別途扱います。
 */
internal class SandboxCapability(
    private val port: SandboxPort?,
    /** このタスクのワークスペース (会話 ID)。 */
    workspace: () -> String,
    visionEnabled: Boolean,
    onCommand: (String) -> Unit,
    onProgress: (String) -> Unit,
    onPreparing: () -> Unit,
    onScript: ((String) -> Unit)?,
) : Capability {
    override val id = SANDBOX

    val runner = port?.let { SandboxTools(it, workspace, visionEnabled, onCommand = onCommand, onProgress = onProgress, onPreparing = onPreparing, onScript = onScript) }

    /** デバイスはサンドボックスをサポートしています (ツールは提供されており、準備中の可能性があります)。 */
    fun available(): Boolean = runner?.available() == true

    override val folds = true

    override fun status(): CapabilityStatus = when (val s = port?.status()) {
        null -> CapabilityStatus.Unavailable("no sandbox")
        is SandboxStatus.Ready -> CapabilityStatus.Available
        is SandboxStatus.NotInstalled -> CapabilityStatus.Degraded("not installed")
        is SandboxStatus.Installing -> CapabilityStatus.Degraded(s.step)
        is SandboxStatus.Unsupported -> CapabilityStatus.Unavailable(s.reason)
        is SandboxStatus.Broken -> CapabilityStatus.Unavailable(s.reason)
    }

    override fun tools(): List<AgentTool> = SandboxGroup.tools

    /** 今度は、ファイルをワークスペースに移動します (添付ファイルの行には、/workspace/ の下のパスが示されています)。最初にサンドボックス グループを展開します。 サンドボックスグループを収集する場合、 */
    override fun preload(task: String): Boolean = available() && "${SandboxPaths.WORKSPACE}/" in task

    /** は付与されません。 */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || SandboxGroup.tools.none { it.name in env.offered }) return emptyList()
        val p = port?.takeIf { available() }
        return listOf(env.gate(section(p?.status() ?: SandboxStatus.NotInstalled, p?.networkAllowed() == true)))
    }

    override val scripts = object : BundledScripts {
        override fun offered() = available()

        override suspend fun ready(): Boolean {
            val p = port ?: return false
            if (p.status() !is SandboxStatus.Ready) return false
            p.prepare()
            return true
        }

        override fun scripts(skillDir: String): List<ScriptState> {
            val network = (port?.status() as? SandboxStatus.Ready)?.network == true
            return port?.scriptManifest(skillDir).orEmpty().map { e ->
                val (run, reason) = ScriptManifest.effectiveRunnable(e, network)
                ScriptState(e.file, run, reason)
            }
        }
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    override fun guard(): OffScreenGuard = SandboxGuard()

    companion object {
        val KEY = CapabilityKey<SandboxCapability>(SANDBOX)

        /** サンドボックス セクション: 現在インターネットに接続しているかどうかを示します ([network]: ユーザーのネットワーク設定)。実行環境がまだ準備できていない場合、初めてツールを使用するときにそれを待つことになる場合は、一文を追加します。 */
        fun section(status: SandboxStatus, network: Boolean): String {
            val s = Prompts.capability(SANDBOX).replace("{{network}}", if (network) NETWORK_ON else NETWORK_OFF)
            return if (status is SandboxStatus.Ready) s else "$s\n- $PREPARING"
        }

        const val PREPARING = "The environment is still being set up in the background. The first tool call waits for it (up to about two minutes); if it is still not ready, the tool says so."

        const val NETWORK_ON = "on. The user allowed the sandbox to use the network; anything you send out leaves the phone, so only fetch what the task needs."
        const val NETWORK_OFF = "無効です。コマンドからインターネットへ接続できません（pip/npm/apkのインストールやダウンロードを含む）。ネットワークが必要な場合は、Mikaの設定で「スクリプトのネット接続を許可」を有効にできるとユーザーへ説明してください。"
    }
}

/** このタスクのサンドボックス実行。サンドボックスがない場合は null。 */
internal val ToolContext.sandboxRunner: SandboxTools? get() = this[SandboxCapability.KEY]?.runner

internal fun ToolContext.sandboxAvailable(): Boolean = this[SandboxCapability.KEY]?.available() == true
