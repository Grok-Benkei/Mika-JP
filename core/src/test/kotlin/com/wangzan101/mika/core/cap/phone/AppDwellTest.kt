package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.uistate.PlacePace
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * アプリのリズムは実行の入り口に接続されています。単一のステップとバッチの各ステップはアクション前の一時停止で構成され、一時停止の継続時間はこの呼び出しの待機時間として記録されます。
 * 「高速」は停止しません。ブラウジング速度を制限するアプリは、上限に達しても画面を変更せず、page_limit を報告し、通常どおりに入力し、ユーザーの応答後に続行します。滞在中は軽くスクロールして走行記録のみ入力してください。
 */
class AppDwellTest {
    /** アプリ: クリックするたびに画面全体 (6 つの異なるコンテンツ) が変化します。クリック数を書き留め、入力し、数回スクロールします。 */
    private class Pages(private val pkg: String = "com.slow") : ScreenPort by FakeScreen() {
        var page = 0
        val calls = mutableListOf<String>()
        private fun shown() = (1..6).map { i ->
            UiElement("@e$i", "text", "第${page}屏的第${i}条内容", 540, 200 * i, listOf(0, 80 * i, 999, 80 * i + 60), null, true, i == 1, false)
        }
        override suspend fun snapshot() = ScreenSnapshot("s$page", pkg, shown(), 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tap"; page++; return ActionResult.Ok(changed = true) }
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type"; return ActionResult.Ok(changed = true) }
        override suspend fun back(): ActionResult { calls += "back"; page++; return ActionResult.Ok(changed = true) }
        override suspend fun idleScroll(fraction: Double): Boolean { calls += "idle"; return true }
    }

    private fun tools(screen: ScreenPort, pacing: AppPacing, idle: IdleScroll? = null): ToolDispatcher =
        toolDispatcher(screen, appPacing = { pacing }, appPace = { if (it == "com.slow") PlacePace.CAREFUL else PlacePace.NORMAL }, appIdleScroll = { idle })

    private fun executor(t: ToolDispatcher) = ActionExecutor(t, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE))

    private suspend fun ActionExecutor.tap(t: ToolDispatcher): ToolExecution {
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        return run(Part.ToolCall("t", "phone_tap", """{"ref":"@e2","snapshotId":"$sid"}"""), Origin.Model(1)).execution()
    }

    private fun ToolExecution.error() = (content["error"] as? JsonPrimitive)?.content

    @Test fun fastAddsNoDwell() = runTest {
        val screen = Pages()
        val t = tools(screen, OperatingPace.FAST.appPacing())
        val ex = executor(t)
        val start = currentTime
        repeat(5) { assertEquals(0L, ex.tap(t).waitMs) }
        // 画面数の上限はありません
        assertEquals(5, screen.page)
        assertEquals(0L, currentTime - start)
    }

    @Test fun eachActionWaitsAfterTheLastOneAndTheWaitIsRecorded() = runTest {
        val t = tools(Pages("com.plain"), OperatingPace.STANDARD.appPacing(Random(3)))
        val ex = executor(t)
        assertEquals(0L, ex.tap(t).waitMs)
        val before = currentTime
        val second = ex.tap(t)
        // 変更された前画面：新画面の範囲（標準）
        assertTrue("${second.waitMs}", second.waitMs in AppPacing.Limits().newScreenMs.first..AppPacing.Limits().maxDwellMs)
        assertTrue(currentTime - before >= second.waitMs)
        // バッチ内の各ステップは実行エントリによっても停止されます
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        val typed = ex.run(Part.ToolCall("b1", "phone_type_text", """{"ref":"@e1","snapshotId":"$sid","text":"你好"}"""), Origin.Batch(2)).execution()
        assertTrue("${typed.waitMs}", typed.waitMs > 0)
    }

    @Test fun carefulAppsStopAtTheScreenBudgetUntilTheUserAnswers() = runTest {
        val screen = Pages()
        val t = tools(screen, AppPacing(Random(1), screenCap = 3))
        val ex = executor(t)
        repeat(3) { assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"]) }
        // は 3 つの画面を変更しました。画面を変更するアクションが機能しません。停止する理由を説明します。
        val stopped = ex.tap(t)
        assertEquals("page_limit", stopped.error())
        assertTrue(stopped.content["message"].toString(), stopped.content["message"].toString().contains("ask_user"))
        assertEquals(3, screen.page)
        // いつものように入力します
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        assertEquals(JsonPrimitive(true), ex.run(Part.ToolCall("y", "phone_type_text", """{"ref":"@e1","snapshotId":"$sid","text":"a"}"""), Origin.Model(1)).execution().content["ok"])
        // ユーザーは次のように答えました: どうぞ、やってください
        t.noteUserAnswer("还要接着看吗?", "继续")
        assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"])
        assertEquals(4, screen.page)
    }

    @Test fun ordinaryAppsHaveNoBudget() = runTest {
        val screen = Pages("com.plain")
        val t = tools(screen, AppPacing(Random(1), screenCap = 2))
        val ex = executor(t)
        repeat(4) { assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"]) }
    }

    @Test fun idleScrollsHappenOnlyBeforeActionsThatDoNotAimAtAPlaceAndAreOnlyRecorded() = runTest {
        val screen = Pages()
        val t = tools(screen, AppPacing.human(Random(5)), IdleScroll(Random(6)))
        val ex = executor(t)
        ex.tap(t)
        // 座標または数値をクリックする前にスクロールしません
        ex.tap(t)
        assertTrue(screen.calls.none { it == "idle" })
        // 押さずに画面上の位置に戻ります: 新しい画面で十分な時間停止し、滞在中に数回スクロールし、走行記録のみを入力します
        val back = ex.run(Part.ToolCall("k", "phone_back", "{}"), Origin.Model(1)).execution()
        val idled = screen.calls.count { it == "idle" }
        assertTrue("$idled", idled > 0)
        assertEquals(JsonPrimitive(idled), back.record["idleScrolls"])
        assertTrue(back.content["idleScrolls"] == null)
    }
}
