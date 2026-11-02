package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.uistate.focus

private const val SURFACE = "surface"

/**
 * は、どの画面 (確認、引き継ぎ、失敗のレポート) 用の一般的なツールについて話しています。このタスクに 3 つ以上の画面がある場合、追加のサーフェス パラメーターがあります。
 * 指定がない場合は、最新の画面ツールの部分です。ディスペンサーを呼び出す前に、ディスペンサーを押して操作中の画面を記録します。ツールはその時点でもその画面を使用します。 surfaceに
 */
internal interface SurfaceAware : AgentTool {
    override fun def(ctx: ToolContext): ToolDef {
        val ids = ctx.focus.surfaces.map { it.id }
        if (ids.size < 2) return def
        return ToolDef(def.name, def.description, def.props + (SURFACE to str(SURFACE_NOTE, ids)), def.required)
    }

    override fun aimAt(o: JsonObject): String? = o.argStr(SURFACE)

    /** を与えた場合、このタスクの画面であることを確認してください。 */
    fun checkSurface(a: Args, ctx: ToolContext) {
        val id = a.str(SURFACE) ?: return
        if (ctx.focus[id] == null) throw ArgError(SURFACE, "must be one of ${ctx.focus.surfaces.joinToString("|") { it.id }}")
    }

    companion object {
        const val SURFACE_NOTE = "Which screen this is about. Default: the screen your latest screen tool acted on."
    }
}
