package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.uistate.DEFAULT_SWIPE_MS
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.COORD
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.toPx
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.lastOrSnapshot
import com.wangzan101.mika.core.uistate.screen

/** スライディング (横方向のスライディングは「削除するためのスライディング」である可能性があり、元に戻せないかどうかをターゲットが判断する必要があります)。 */
internal class Scroll(f: ScreenFamily) : ScreenTool(f, "scroll") {
    override val verb = Verb.Scroll
    override val batchable = true
    override val routable = f.routable
    override val label = "スクロール"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Swipe
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "direction" to str("Where the content you want to see is: down = further down the page, up = further up.", listOf("up", "down", "left", "right")),
            "amount" to int("How far, 1-20 (default 3).", 1, 20),
            "x" to int(COORD, 0, 999), "y" to int(COORD, 0, 999),
        ), listOf("direction")) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val dir = a.str("direction")!!
        if (dir !in listOf("up", "down", "left", "right")) throw ArgError("direction", "must be up, down, left or right")
        val amount = a.int("amount", 1, 20) ?: 3
        val x = a.coord("x"); val y = a.coord("y")
        if ((x == null) != (y == null)) throw ArgError("x", "give both x and y or neither")
        val cx = x ?: 500; val cy = y ?: 500
        // 方向がコンテンツの移動方向となり、指は逆方向にスライドします。
        val dist = min(650, amount * 100)
        val dx = when (dir) { "left" -> dist; "right" -> -dist; else -> 0 }
        val dy = when (dir) { "up" -> dist; "down" -> -dist; else -> 0 }
        fun bound(v: Double) = max(50, min(949, v.roundToInt()))
        // 携帯電話は、最初にアクセシビリティ コントロールでスクロールを実行できるようにすることで、デフォルトで垂直スクロールになります。ポートがスクロール可能なコントロールを見つけられない場合、ポートはジェスチャに戻ります。
        val r = if (family.id == "phone" && dir in setOf("up", "down") && x == null && amount == 3)
            ctx.action(ctx.screen.scroll(null, null, dir == "down"))
        else swipeNormalized(ctx, bound(cx - dx / 2.0), bound(cy - dy / 2.0), bound(cx + dx / 2.0), bound(cy + dy / 2.0))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        val finger = when (dir) { "up" -> "down"; "down" -> "up"; "left" -> "right"; else -> "left" }
        // は動いていません (おそらく終了しています)。実際の効果をもとに販売店が判断いたします。結果は atEnd としてマークされます。
        val content = r.content + mapOf("direction" to JsonPrimitive(dir), "fingerMoved" to JsonPrimitive(finger))
        return ToolExecution(JsonObject(content), changed = r.changed)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("direction")
}

internal class Swipe(f: ScreenFamily) : ScreenTool(f, "swipe") {
    override val verb = Verb.Swipe
    override val batchable = true
    override val routable = f.routable
    override val label = "スワイプ"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Swipe
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "startX" to int(COORD, 0, 999), "startY" to int(COORD, 0, 999),
            "endX" to int(COORD, 0, 999), "endY" to int(COORD, 0, 999),
            "durationMs" to int("Gesture duration 50-5000 ms (default 400).", 50, 5000),
        ), listOf("startX", "startY", "endX", "endY")) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val x1 = a.coord("startX")!!; val y1 = a.coord("startY")!!
        val x2 = a.coord("endX")!!; val y2 = a.coord("endY")!!
        val ms = a.int("durationMs", 50, 5000)?.toLong() ?: DEFAULT_SWIPE_MS
        return swipeNormalized(ctx, x1, y1, x2, y2, ms)
    }
}

private suspend fun swipeNormalized(ctx: ToolContext, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): ToolExecution {
    val s = ctx.lastOrSnapshot()
    return ctx.action(ctx.screen.swipe(toPx(x1, s.width), toPx(y1, s.height), toPx(x2, s.width), toPx(y2, s.height), durationMs))
}
