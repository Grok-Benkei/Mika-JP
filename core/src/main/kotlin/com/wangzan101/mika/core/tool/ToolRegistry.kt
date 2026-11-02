package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts

/**
 * このタスクのツール: [all] は、各機能およびコアによって提供されるすべてのツールです (モデルに発行された順序で)。このタスクで呼び出すことができるツールは、構成によってフィルタリングされます。
 * 呼び出されるたびに、名前でツールを見つけて実行します。配布者は具体的なツールを知りません。 [groupOf] は、ツールが属するグループを示します ([ToolGroups] を参照。常に null)。
 */
internal class ToolRegistry(private val ctx: ToolContext, all: List<AgentTool>, private val groupOf: (AgentTool) -> String? = { null }) {
    private val declared: Map<String, AgentTool> = all.associateBy { it.name }
    private val tools: Map<String, AgentTool> = all.filter { it.available(ctx) }.associateBy { it.name }

    init {
        check(declared.size == all.size) { "duplicate tool names" }
    }

    operator fun get(name: String): AgentTool? = tools[name]

    /** ツールのステートメント (リスク、決定目標など) を名前で検索します。このタスクで提供されていないものも見つけることができます。認識されないものは null です。 */
    fun declared(name: String): AgentTool? = declared[name]

    /**
     * は、ディレクトリを初めてフェッチするときに、この実行のツール定義 (フルテキスト、エントリの短い説明、およびエントリが属するグループ) をフリーズします。その後のポート ステータスの変更は実行にのみ影響し、プロンプト ワード プレフィックスは変更できません。
     * エントリの簡単な説明は、prompts/entries/<ツール名>.md にあり、パラメータは全文と同じです。
     */
    private val frozen: List<ToolGroups.Slot> by lazy {
        tools.values.filter { it.offered(ctx) }.map { t ->
            val d = t.def(ctx)
            ToolGroups.Slot(d.spec(), t.entry?.let { ToolDef(d.name, Prompts.entry(d.name), d.props, d.required).spec() }, groupOf(t))
        }
    }

    /** このタスクに提供されるすべてのツール (各グループを展開、全文)。 */
    fun definitions(): List<ToolSpec> = frozen.map { it.full }

    /** 現時点でモデルに送信されるツール: [expanded] のグループのみを展開します ([ToolGroups.layout] を参照)。 */
    fun layered(expanded: Set<String>, extra: Map<String, List<ToolSpec>>): List<ToolSpec> = ToolGroups.layout(frozen, expanded, extra)

    /** グループ [group] には、モデルに発行されたエントリ ツールが含まれています。 */
    fun hasEntry(group: String): Boolean = frozen.any { it.group == group && it.entry != null }

    /** グループ [group] のモデルに割り当てられたツール名 (登録順)。 */
    fun members(group: String): List<String> = frozen.filter { it.group == group }.map { it.full.name }

    /** do_steps に入れることができるツール名 (このタスクで提供されていないツールもリストされています) が登録順に表示されます。 */
    fun batchable(): List<String> = declared.values.filter { it.batchable }.map { it.name }

    /** 「不明なツール」の説明に記載されている名称：随時利用可能なもの（動作環境が整っているか確認するために画面を移動しないグループは記載していません）。 */
    fun listedNames(): List<String> = tools.values.filter { !it.offScreen }.map { it.name }
}
