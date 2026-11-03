package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.root.RootRun
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryPersistence
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.testing.PromptSurface
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.vision.JvmTestImageOps
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.vision.says
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.knowledge.KnowledgeStore

/**
 * モデルに表示されるすべてのプロンプト ワード: 各機能の組み合わせの完全なシステム プロンプト ワード、各ツールの定義 (名前、説明、パラメーター スキーマ)、
 * 内部ロールのプロンプトワード。ゴールデン サンプルは core/src/test/resources/prompt-surface/ にあります。再構築の前後で逐語的に記録する必要があります。
 * 再生成: ./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots
 */
class PromptSurfaceTest {
    private class Mem : MemoryPersistence {
        override fun load(): List<MemoryItem> = emptyList()
        override fun save(items: List<MemoryItem>) {}
    }

    private class Combo(val tools: () -> ToolDispatcher, val flags: SpeedFlags = SpeedFlags.OFF, val memory: MemoryStore? = null)

    private val bundled = KnowledgeStore(com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.entries())
    private val LAYERS = linkedMapOf("chat" to emptyList(), "phone" to listOf("phone"), "web" to listOf("web"), "all" to listOf("phone", "web", "sandbox"))
    private val combos: Map<String, Combo> = linkedMapOf(
        "phone" to Combo({ toolDispatcher(FakeScreen()) }),
        "phone-inline-batch" to Combo({ toolDispatcher(FakeScreen(), inlineScreen = true) }, SpeedFlags(inlineScreen = true, batchSteps = true)),
        "phone-web" to Combo({ toolDispatcher(FakeScreen(), web = FakeWebPort()) }),
        "phone-sandbox-offline" to Combo({ toolDispatcher(FakeScreen(), sandbox = FakeSandbox(), workspace = { "w" }) }),
        "phone-web-sandbox-online" to Combo({
            toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(SandboxStatus.Ready(network = true)), workspace = { "w" })
        }),
        "phone-sandbox-preparing" to Combo({ toolDispatcher(FakeScreen(), sandbox = FakeSandbox(SandboxStatus.NotInstalled), workspace = { "w" }) }),
        // rootコマンドをユーザーが明示的に許可した組み合わせ。
        "phone-root" to Combo({ toolDispatcher(FakeScreen(), root = object : RootPort {
            override suspend fun run(command: String, timeoutMs: Long): RootRun = RootRun.Unavailable
        }) }),
        "everything" to MemoryStore(Mem(), clock = { 1L }, ids = { "m" }).let { mem ->
            Combo({
                toolDispatcher(
                    FakeScreen(), visionEnabled = true, skills = com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.catalog(), vision = VisionService(says(), JvmTestImageOps),
                    web = FakeWebPort(), inlineScreen = true, deepLinks = true,
                    memory = mem, utterances = UserUtterances(), sandbox = FakeSandbox(SandboxStatus.Ready(network = false)), workspace = { "w" },
                    personalSkills = com.wangzan101.mika.core.skills.InMemoryPersonalSkills(),
                    // サブタスクと以前のラウンドのレコード:history_read は、以前の実行がある場合にのみ使用できます。
                    runSpec = com.wangzan101.mika.core.run.RunSpec(com.wangzan101.mika.core.run.RunId("t"), "t", "thread",
                        priorTurns = listOf(Turn("之前", "好", "succeeded", runId = "r1"))),
                    childHost = com.wangzan101.mika.core.child.ChildRunHost { spec, _ ->
                        com.wangzan101.mika.core.child.ChildResult(spec.id, com.wangzan101.mika.core.child.ChildPhase.Completed, "") },
                    runRecords = NoRecords,
                )
            }, SpeedFlags(inlineScreen = true, batchSteps = true, deepLinks = true), mem)
        },
    )

    /** [open] 前のラウンドでは 3 つのグループが使用されました。これらはすべて最初に展開されました (このタスクによって提供されました)。それ以外の場合は、入り口だけがあり、すべて閉鎖されていました。 */
    private fun offered(c: Combo, open: Boolean = true): FakeRequestCapture = runBlocking {
        val m = FakeRequestCapture()
        AgentLoop(m, c.tools(), SystemPrompt.identity(), flags = c.flags, memory = c.memory)
            .run("t", if (open) listOf(Turn("之前", "好", "succeeded", groups = listOf("phone", "web", "sandbox"))) else emptyList())
        m
    }

    /** 各組み合わせおよび各グループが展開されるときのシステム プロンプト ワード (折りたたまれた外観については、layeredSurfaces を参照)。 */
    @Test fun systemPromptPerCombination() {
        for ((name, c) in combos) {
            val m = offered(c)
            PromptSurface.check("system/$name", PromptSurface.system(m.system!!, m.tools))
        }
    }

    @Test fun toolDefinitions() {
        // 同じ名前のツールには複数の画面があり、このタスクに従って複数の定義 (もう 1 つのサーフェス パラメーター) を持つ場合があります。エントリーツールを収集する際には、各タイプがリストされている簡単な説明があります。
        val all = combos.values.flatMap { offered(it).tools + offered(it, open = false).tools }
            .distinctBy { it.name + it.description + it.parametersJsonSchema }.sortedBy { it.name }
        PromptSurface.check("tools", PromptSurface.tools(all))
    }

    /**
     * 階層化後の各状況での最初のリクエストの全体像 (システム プロンプト ワード + 各ツールの定義の全文)、すべてを組み合わせた、製品パスによる確認ツールなし:
     * チャットのみチャット (すべて保存)、前のラウンドで使用した電話/Web グループ (開始時に展開)、3 つのグループすべてが展開されます。サイズはレイヤー/サイズに分けて記載されています。
     */
    @Test fun layeredSurfaces() {
        val c = combos.getValue("everything")
        val sizes = StringBuilder()
        for ((name, groups) in LAYERS) {
            val m = FakeRequestCapture()
            val prior = if (groups.isEmpty()) emptyList() else listOf(Turn("之前", "好", "succeeded", groups = groups))
            runBlocking { AgentLoop(m, c.tools(), SystemPrompt.identity(), flags = c.flags, memory = c.memory, confirmations = null).run("t", prior) }
            PromptSurface.check("layers/$name", PromptSurface.layer(m.system!!, m.tools))
            val system = m.system!!.toByteArray().size
            val tools = m.tools.sumOf { (it.name + it.description + it.parametersJsonSchema).toByteArray().size }
            val tokens = TokenEstimate.of(listOf(com.wangzan101.mika.core.model.Message(com.wangzan101.mika.core.model.Role.User,
                listOf(Part.Text(m.system!! + m.tools.joinToString("") { it.name + it.description + it.parametersJsonSchema })), com.wangzan101.mika.core.model.Author.Mika)))
            sizes.appendLine("$name: system $system bytes, ${m.tools.size} tools $tools bytes, total ${system + tools} bytes, ~$tokens tokens")
        }
        print(sizes)
        PromptSurface.check("layers/sizes", sizes.toString())
    }

    @Test fun rolePrompts() {
        PromptSurface.check("roles", PromptSurface.roles())
    }

    @Test fun feedbackFromTools() {
        PromptSurface.check("feedback-tools", runBlocking { PromptSurfaceFeedback.tools() })
    }

    @Test fun feedbackFromLoop() {
        PromptSurface.check("feedback-loop", PromptSurfaceFeedback.loop())
    }

    private object NoRecords : com.wangzan101.mika.core.record.RunRecords {
        override suspend fun summary(runId: String): com.wangzan101.mika.core.record.RunSummary? = null
        override suspend fun events(runId: String) = emptyList<com.wangzan101.mika.core.record.RunEvent>()
    }

    /** は、作業開始要求のシステム プロンプトの単語とツールを書き留めるという 1 つの終了のみで応答します。 */
    private class FakeRequestCapture : com.wangzan101.mika.core.model.ChatModel {
        override val capabilities = com.wangzan101.mika.core.model.Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
        var system: String? = null
        var tools: List<com.wangzan101.mika.core.model.ToolSpec> = emptyList()
        override suspend fun complete(system: String, messages: List<com.wangzan101.mika.core.model.Message>, tools: List<com.wangzan101.mika.core.model.ToolSpec>): ModelReply {
            if (this.system == null) { this.system = system; this.tools = tools }
            return com.wangzan101.mika.core.testing.answerReply("ok")
        }
    }
}
