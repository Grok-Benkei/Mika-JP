package com.wangzan101.mika.vision

import com.wangzan101.mika.runAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.vision.VisionService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * phone_find_visually エージェント仮想画面上の読み取り専用参照システム設定 (組み込みのアシスタントおよびアクセシビリティ サービスが必要)。
 * には実際の画像表示モデルがありません。ビジュアル パスはスクリプト モデルを使用し、コントロール ツリーの「WLAN」行の座標に基づいて計算されます。
 */
@RunWith(AndroidJUnit4::class)
class LocateDeviceTest {
    private var agent: DeviceSupport.Agent? = null

    @After
    fun tearDown() {
        agent?.release()
    }

    /** 決まった答えを順番に返すビジュアルモデル。 */
    private class ScriptedVision(vararg answers: String, verified: Boolean = true) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = true, contextTokens = 32_000, reasoning = false, groundingVerified = verified)
        private val answers = ArrayDeque(answers.toList())
        var asked = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            asked++
            return ModelReply(listOf(Part.Text(answers.removeFirst())), StopReason.EndTurn, null)
        }
    }

    private fun DeviceSupport.Agent.snapUntil(test: (ScreenSnapshot) -> Boolean): ScreenSnapshot = runBlocking {
        val end = System.currentTimeMillis() + 5_000
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.elements.map { it.name }}", test(s))
        s
    }

    private fun openSettingsHome(): Pair<DeviceSupport.Agent, ScreenSnapshot> {
        val a = DeviceSupport.newAgent().also { agent = it }
        val r = runBlocking { a.screen.openApp(SETTINGS) }
        assertTrue("openApp: $r", r is ActionResult.Ok)
        val home = a.snapUntil { s -> s.elements.any { it.name.trim() == "WLAN" } }
        // トランジション アニメーションが終了しました。アニメーションを一致させるには 2 つのスクリーンショットが必要です。
        Thread.sleep(1_000)
        return a to home
    }

    private fun ToolDispatcher.run(name: String, args: String): JsonObject =
        runBlocking { runAction(Part.ToolCall("d", name, args)).content }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun tapAndExpectWlanPage(a: DeviceSupport.Agent, d: ToolDispatcher, r: JsonObject) {
        val tap = d.run("phone_tap", """{"ref":"${r.s("ref")}","snapshotId":"${r.s("snapshotId")}"}""")
        assertEquals(tap.toString(), "true", tap.s("ok"))
        a.snapUntil { s -> s.elements.any { it.role == "Switch" } }
    }

    @Test
    fun treePathFindsWlan() {
        val (a, _) = openSettingsHome()
        val model = ScriptedVision()
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        val r = d.run("phone_find_visually", """{"target":"WLAN","text":"WLAN"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals("elements", r.s("source"))
        assertEquals("要素ツリーで見つかった場合は画像用モデルに問い合わせない", 0, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }

    @Test
    fun visionPathTapsWlan() {
        val (a, home) = openSettingsHome()
        val row = home.elements.first { it.name.trim() == "WLAN" }.bounds
        // 画面読み取りの 0 ～ 999 はモデルの 0 ～ 1000 に置き換えられます
        val box = row.map { (it * 1000 / 999.0).roundToInt() }
        val model = ScriptedVision("""{"bbox_2d": [${box.joinToString(", ")}], "seen": "WLAN"}""", "是")
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        // テキストなし: コントロール ツリーなし
        val r = d.run("phone_find_visually", """{"target":"WLAN 那一行"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals("vision", r.s("source"))
        assertEquals(2, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }

    @Test
    fun unverifiedVisionNeedsSecondLook() {
        val (a, home) = openSettingsHome()
        val row = home.elements.first { it.name.trim() == "WLAN" }.bounds
        val box = """{"bbox_2d": [${row.map { (it * 1000 / 999.0).roundToInt() }.joinToString(", ")}], "seen": "WLAN"}"""
        // 測位精度は未検証ですので、再度フレームに入れて2度確認してからお渡し下さい。
        val model = ScriptedVision(box, "是", box, "是", verified = false)
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        val r = d.run("phone_find_visually", """{"target":"WLAN 那一行"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals(4, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }
}
