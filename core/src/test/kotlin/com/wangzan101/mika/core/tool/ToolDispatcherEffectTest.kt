package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.EffectTuning
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ディストリビュータはアクションの効果を決定します。非同期更新を再度監視し、ポートが待機した後に待機を停止します。追加時間には上限があり、変更は効果に従います。 */
class ToolDispatcherEffectTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.kind() = this["effect"]!!.jsonObject["kind"]!!.jsonPrimitive.content

    private fun page(vararg names: String) = ScreenSnapshot("p", "com.x", names.mapIndexed { i, n ->
        UiElement("@e${i + 1}", "text", n, 500, 100 + i * 100, listOf(0, 40 + i * 40, 999, 70 + i * 40), null, true, false, false)
    }, 1080, 2400)

    /** アクション [afterMs] ミリ秒後、インターフェイスが [later] (非同期更新) に変更されます。 [peekMs] は、確認にかかる時間です。 [result] はポートのアクション結果です。 */
    private class LateScreen(
        val now: () -> Long, val first: ScreenSnapshot, val later: ScreenSnapshot, val afterMs: Long,
        val peekMs: Long = 0, val result: ActionResult = ActionResult.Ok(),
    ) : ScreenPort by FakeScreen() {
        var actedAt = Long.MAX_VALUE
        var settles = 0
        private fun current() = if (now() - actedAt >= afterMs) later else first
        override suspend fun snapshot() = current()
        override suspend fun peek(): ScreenSnapshot { if (peekMs > 0) delay(peekMs); return current() }
        override suspend fun waitStable(timeoutMs: Long): Boolean { settles++; return true }
        override suspend fun tapXY(x: Int, y: Int): ActionResult { actedAt = now(); return result }
        override suspend fun back(): ActionResult { actedAt = now(); return result }
    }

    @Test fun asyncUpdateIsCaughtByTheRecheck() = runTest {
        val s = LateScreen({ currentTime }, page("列表", "一", "二"), page("详情", "价格", "加入购物车", "评价"), afterMs = EffectTuning.RECHECK_MS / 2)
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("done", r.content.kind())
        assertEquals(true, r.changed)
    }

    // アクションの後に新しいプロンプト「上限に達しました」が表示されます。limitReached および stop プロンプトが結果に書き込まれます。アクション前の画面上の説明テキストはカウントされません。
    @Test fun limitNoticeAfterTheActionIsReported() = runTest {
        val s = LateScreen({ currentTime }, page("职位详情", "立即沟通"), page("职位详情", "立即沟通", "今日沟通次数已用完，明天再来吧"), afterMs = 0)
        val c = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("今日沟通次数已用完，明天再来吧", c["limitReached"]!!.jsonPrimitive.content)
        assertTrue(c["hint"]!!.jsonPrimitive.content.contains("ここで止め") && c["hint"]!!.jsonPrimitive.content.contains("今日再試行したりしない"))
        val faq = LateScreen({ currentTime }, page("常见问题", "次数已达上限怎么办"), page("常见问题", "次数已达上限怎么办", "答案"), afterMs = 0)
        assertEquals(null, toolDispatcher(faq).execute(call("phone_tap", """{"x":500,"y":500}""")).content["limitReached"])
    }

    // リマインダーバーが少し遅れて表示されました（初回視聴時は表示されず、再視聴時のみ表示されました）。
    @Test fun lateLimitToastIsCaughtByTheRecheck() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表", "操作过于频繁，请稍后再试"), afterMs = EffectTuning.RECHECK_MS / 2)
        val c = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("操作过于频繁，请稍后再试", c["limitReached"]!!.jsonPrimitive.content)
    }

    @Test fun stillNothingAfterTheRecheckIsNoEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0)
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("no_effect", r.content.kind())
        assertEquals(false, r.changed)
    }

    @Test fun portThatAlreadyWaitedIsNotWaitedForAgain() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("详情", "价格", "加入购物车"), afterMs = 0, result = ActionResult.Ok(changed = true))
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("done", r.content.kind())
        assertEquals(0, s.settles)
    }

    @Test fun portSaysChangedIsNeverReportedAsNoEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0, result = ActionResult.Ok(changed = true))
        val c = toolDispatcher(s).execute(call("phone_back")).content
        assertEquals("small_change", c.kind())
        assertEquals("true", c["changed"]!!.jsonPrimitive.content)
    }

    @Test fun portSaysUnchangedButTheInterfaceChangedFollowsTheEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("详情", "价格", "加入购物车"), afterMs = 0, result = ActionResult.Ok(changed = false))
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("done", r.content.kind())
        assertEquals(true, r.changed)
        assertEquals("true", r.content["changed"]!!.jsonPrimitive.content)
    }

    @Test fun extraTimePerActionIsBounded() = runTest {
        // 2 秒かかる遅いポートを見てください。効果を判断するには、最大で BUDGET_MS の費用がかかります。そのときは、港の事実に基づいて結論を出します。
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0, peekMs = 2_000, result = ActionResult.Ok(changed = true))
        val d = toolDispatcher(s)
        val start = currentTime
        val c = d.execute(call("phone_back")).content
        val spent = currentTime - start
        // 行動前の一目見と行動後の判断には上限があります。
        assertTrue("spent $spent", spent <= EffectTuning.PROBE_MS + EffectTuning.BUDGET_MS)
        assertEquals("small_change", c.kind())
    }
}
