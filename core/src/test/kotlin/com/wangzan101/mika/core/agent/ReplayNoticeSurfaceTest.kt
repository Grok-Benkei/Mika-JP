package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.replay.MemRoutes
import com.wangzan101.mika.core.replay.Notes
import com.wangzan101.mika.core.replay.PagedScreen
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.replay.ui
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.testing.PromptSurface
import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * は、エクスペリエンス リプレイを返すときに、実際の AgentLoop によって生成され、ゴールド サンプル (prompt-surface/フィードバック-replay.txt) にそのまま書き込まれた、エンディングごとに 1 つの説明をモデルに伝えます。
 * ゴールド サンプルは、リファクタリング前のコードで生成されました。指示は、フィードバック ディレクトリから入力された後、一字一句正確に入力する必要があります。
 */
class ReplayNoticeSurfaceTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall("m${++seq}", name, args)), StopReason.ToolUse, null)
    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)

    private class FailingTap(base: PagedScreen) : ScreenPort by base {
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed("obscured")
    }

    /** 戻るときのダイアログ（「過去を押してください」のテキスト）の説明を追加しました。 */
    private fun notice(screen: ScreenPort, route: com.wangzan101.mika.core.replay.Route = Notes.route()): String {
        val main = FakeModel(text("保存しました"))
        runBlocking {
            AgentLoop(main, toolDispatcher(screen), "BASE", flags = SpeedFlags(replay = true),
                routes = RouteMatcher(FakeModel(text("""{"route": 1, "slots": ["今天天气不错"]}""")), MemRoutes(route), null)).run("在备忘录里写今天天气不错")
        }
        return main.requests.first().flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }.single { "以前に成功した進め方" in it || "以前の進め方" in it }
    }

    @Test fun everyReplayOutcome() {
        val items = mutableListOf<Pair<String, String>>()
        items += "completed" to notice(Notes.screen())
        items += "diverged, not executed" to notice(Notes.screen().apply {
            pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false))
        })
        items += "diverged, executed without effect" to notice(Notes.screen().apply { links.clear() })
        items += "diverged with error code" to notice(FailingTap(Notes.screen()))
        val send = Notes.steps.take(1) + RouteStep("phone_tap", "{}", Selector("button", "提交订单", "n:id/send", listOf(0, 900, 300, 999), 0, true), Notes.homeA, Notes.editorA)
        items += "stopped for confirmation" to notice(Notes.screen().apply {
            pages["home"] = Notes.home + ui("@e5", "button", "提交订单", listOf(0, 900, 300, 999), rid = "n:id/send")
        }, Notes.route(send))
        PromptSurface.check("feedback-replay", PromptSurface.entries(items))
    }
}
