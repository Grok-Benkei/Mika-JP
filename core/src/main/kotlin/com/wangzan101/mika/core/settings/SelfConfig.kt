package com.wangzan101.mika.core.settings

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * モデルは、Mika 自身が設定した唯一の入り口です (アプリの実装、コアはこれらのキーのみを認識します)。 What can be changed in
 *
 * is determined by the App: **Models and Keys, and deleted data will not be given** - the former is equivalent to letting the model replace its own brain and cost the user's money.
 * 後者は元に戻せません。与えられたものはすべてリバーシブルの優先アイテムです。
 *
 * ユーザーが「ミカに自分の設定の変更を許可する」をオンにしない場合、このツールはまったく利用できないため、モデルはそれに触れることができません。
 */
interface SelfConfig {
    /** 現在変更できる項目とその値。 */
    fun options(): List<Option>

    /** は 1 つの項目を変更します。 [value] は [Option.choices] になければなりません。成功した場合は null を返し、それ以外の場合は理由を返します。 */
    fun set(key: String, value: String): String?

    /** は設定です。 [label] はユーザーに表示される名前、[value] は現在の値、[choices] はいくつかのオプションの値です (空はフリーテキストを意味します)。 */
    data class Option(val key: String, val label: String, val value: String, val choices: List<String> = emptyList())
}

const val SETTINGS_LIST = "settings_list"
const val SETTINGS_SET = "settings_set"

private fun port(ctx: ToolContext): SelfConfig =
    ctx.config.selfConfig ?: error("settings tools are only offered when the user allows it")

/** には、Mika が変更できる設定 (キー、表示名、現在の値、およびオプションの値) がリストされています。読み取り専用。 */
internal object SettingsList : AgentTool {
    override val verb = Verb.SettingsList
    override val label = "設定を表示"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(SETTINGS_LIST, Prompts.tool(SETTINGS_LIST), emptyMap(), emptyList())

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ok {
        putJsonArray("settings") {
            port(ctx).options().forEach { o ->
                addJsonObject {
                    put("key", o.key); put("label", o.label); put("value", o.value)
                    if (o.choices.isNotEmpty()) putJsonArray("choices") { o.choices.forEach { add(it) } }
                }
            }
        }
    }
}

/** は、Mika 自身の設定の 1 つを変更します。キーと値は [SettingsList] から取得する必要があります。 */
internal object SettingsSet : AgentTool {
    override val verb = Verb.SettingsSet
    override val label = "設定を変更"
    override val risk = Risk.Write
    /** は画面を移動しません。変更はミカ自身の設定であり、現在のタスクの画面とは関係ありません。 */
    override val offScreen = true

    override fun approvalRisk(args: JsonObject): ApprovalRisk {
        val key = (args["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val value = (args["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ApprovalRisk(ActionRisk.Irreversible, ask = ConfirmationRequest(IrrevAction.other, key, value, null, "settings"))
    }

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(SETTINGS_SET, Prompts.tool(SETTINGS_SET), mapOf(
        "key" to str("The setting key from settings_list."),
        "value" to str("The new value, one of that setting's choices."),
    ), listOf("key", "value"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val key = a.str("key") ?: return fail("missing_key", "Give the setting key from settings_list.")
        val value = a.str("value") ?: return fail("missing_value", "Give the new value.")
        val problem = port(ctx).set(key, value)
        if (problem != null) return fail("bad_value", problem)
        return ok {
            put("key", key)
            put("value", port(ctx).options().firstOrNull { it.key == key }?.value ?: value)
        }
    }
}
