package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.uistate.PressKeys
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.screen

internal class Back(f: ScreenFamily) : ScreenTool(f, "back") {
    override val verb = Verb.Back
    override val batchable = true
    override val routable = f.routable
    override val label = "戻る"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.action(ctx.screen.back())
}

/** キー (Enter キーと確認キーを送信できますが、元に戻せないかどうかを判断するには押す必要があります)。 */
internal class PressKey(f: ScreenFamily) : ScreenTool(f, "press_key") {
    override val verb = Verb.PressKey
    override val batchable = true
    override val routable = f.routable
    override val label = "キーを押す"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Key
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name, describe(),
        mapOf("key" to str("One of the allowed KEYCODE_* names, or its numeric keycode string.")), listOf("key")) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val k = a.str("key")!!.trim()
        val code = PressKeys.resolve(k)
            ?: throw ArgError("key", "key '$k' is not allowed; only navigation/editing keys: ${keyNames(family)}")
        family.keys?.let { if (code !in it) throw ArgError("key", family.keysHint ?: "key '$k' is not allowed here") }
        return ctx.action(ctx.screen.pressKey(code))
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("key")
}
