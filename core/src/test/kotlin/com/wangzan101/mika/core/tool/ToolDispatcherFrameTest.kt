package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.vision.JvmTestImageOps
import com.wangzan101.mika.core.vision.ModelImage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import com.wangzan101.mika.core.assembly.toolDispatcher

/** には 1 つの画面に複数のボタンがあります。 [names] は画面上の単語を判別し、スクリーンショットを 1 つずつカウントします。 */
private class FrameScreen(var names: List<String> = listOf("搜索")) : ScreenPort by FakeScreen() {
    var shots = 0
    var secure = false
    var empty = false
    var result: ActionResult = ActionResult.Ok(changed = true)
    val frame: ByteArray = JvmTestImageOps.encode(1224, 2700) { x, _ -> if (x < 600) 40 else 220 }
    private fun read() = ScreenSnapshot("p", if (empty) null else "com.x",
        if (empty) emptyList() else names.mapIndexed { i, n -> UiElement("@e${i + 1}", "button", n, 500, 100, listOf(0, 0, 10, 10),
            if (n.isBlank()) "com.x:id/control_$i" else null, true, false, false) },
        1224, 2700, protected = secure, hasWindow = !empty)
    override suspend fun snapshot() = read()
    override suspend fun peek() = read()
    override suspend fun screenshotPng(): ByteArray { shots++; return frame }
    override suspend fun isProtected() = secure
    override suspend fun tapXY(x: Int, y: Int) = result
    override suspend fun back() = result
    override suspend fun openApp(packageName: String, cold: Boolean) = result
}

class ToolDispatcherFrameTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun visionMainGetsFrameAfterSuccessfulAction() = runTest {
        val s = FrameScreen()
        val d = toolDispatcher(s, visionEnabled = true, imageOps = JvmTestImageOps)
        val r = d.execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("true", r.content.s("ok"))
        assertTrue(r.images.isEmpty())
        assertEquals(0, s.shots)
        assertFalse(r.content.toString().contains("搜索"))
        assertEquals("text_withheld", (r.content["screen"] as JsonObject).s("reason"))
        // エンコード契約は、送られなかった画面バイトではなく試験用の固定画像で確認する。
        val part = d.modelImage(s.frame, 1)
        assertEquals("image/jpeg", part.mime)
        assertEquals(1280, maxOf(part.width, part.height))
        assertEquals(1, part.screenshotId)
        assertEquals(part.width to part.height, JvmTestImageOps.size(Base64.getDecoder().decode(part.pngBase64)))
    }

    // アクションに添付された画像には、同時に操作可能な要素のリストが常に伴います。内部の ref/snapshotId をクリックします。
    @Test fun frameComesWithTheActionableElementList() = runTest {
        val s = FrameScreen()
        val d = toolDispatcher(s, visionEnabled = true)
        val r = d.execute(call("phone_back"))
        val screen = r.content["screen"] as JsonObject
        assertEquals(r.content.s("screenshotId"), screen.s("screenshotId"))
        val el = screen["elements"]!!.jsonArray.single().jsonObject
        assertFalse("name" in el)
        assertFalse(screen.toString().contains("搜索"))
        assertEquals("@e1", el.s("ref"))
        val tap = d.execute(call("phone_tap", """{"ref":"${el.s("ref")}","snapshotId":"${screen.s("snapshotId")}"}"""))
        assertEquals(tap.content.toString(), "true", tap.content.s("ok"))
        // 支払いページ画面と要素テーブルが利用可能
        val pay = FrameScreen(listOf("确认支付", "¥89.00"))
        val pr = toolDispatcher(pay, visionEnabled = true).execute(call("phone_back"))
        assertEquals(0, pr.images.size); assertEquals(0, pay.shots)
        val payScreen = pr.content["screen"] as JsonObject
        assertEquals("text_withheld", payScreen.s("reason"))
        assertFalse("确认支付" in payScreen.toString())
    }

    @Test fun screenshotIdUsedAsSnapshotIdGetsAPreciseError() = runTest {
        val d = toolDispatcher(FrameScreen(), visionEnabled = true)
        val shot = d.execute(call("phone_back")).content.s("screenshotId")
        for (sid in listOf("\"$shot\"", shot)) {
            val r = d.execute(call("phone_tap", """{"ref":"@e1","snapshotId":$sid}""")).content
            assertEquals("ref_expired", r.s("error"))
            assertTrue(r.s("message"), "screenshotId（view_past_screens 用の画像番号）であり、snapshotId ではない" in r.s("message") && "screen フィールド" in r.s("message"))
        }
    }

    @Test fun unknownArgumentSaysWhatIsAllowed() = runTest {
        val r = toolDispatcher(FrameScreen()).execute(call("phone_tap", """{"text":"搜索"}""")).content
        assertEquals("invalid_arguments", r.s("error"))
        assertTrue(r.s("message"), "許可されるもの: ref, snapshotId, x, y" in r.s("message") && "要素の ref と snapshotId を使う" in r.s("message"))
    }

    @Test fun everyScreenChangingToolAttaches() = runTest {
        val s = FrameScreen()
        val d = toolDispatcher(s, visionEnabled = true)
        for ((name, args) in listOf("phone_back" to "{}", "phone_launch_app" to """{"package":"com.x"}""", "phone_swipe" to """{"startX":1,"startY":1,"endX":9,"endY":9}""")) {
            val r = d.execute(call(name, args))
            assertEquals(name, 0, r.images.size)
            assertFalse(r.content.toString().contains("搜索"))
            assertEquals(name, "text_withheld", (r.content["screen"] as JsonObject).s("reason"))
        }
        // 読み取り専用ツールは付属しません
        assertTrue(d.execute(call("phone_list_apps")).images.isEmpty())
    }

    @Test fun textOnlyMainUnchanged() = runTest {
        val s = FrameScreen()
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertTrue(r.images.isEmpty()); assertNull(r.content["screenshotId"]); assertEquals(0, s.shots)
    }

    @Test fun failedActionNoFrame() = runTest {
        val s = FrameScreen().apply { result = ActionResult.Failed("no_app_open") }
        val r = toolDispatcher(s, visionEnabled = true).execute(call("phone_back"))
        assertTrue(r.images.isEmpty()); assertEquals(0, s.shots)
    }

    @Test fun protectedAndEmptyScreensNotAttachedButPaymentScreenIs() = runTest {
        val secure = FrameScreen().apply { this.secure = true }
        assertTrue(toolDispatcher(secure, visionEnabled = true).execute(call("phone_back")).images.isEmpty())
        assertEquals(0, secure.shots)
        val pay = FrameScreen(listOf("确认支付", "¥89.00"))
        val d = toolDispatcher(pay, visionEnabled = true)
        val r = d.execute(call("phone_back"))
        assertEquals(0, r.images.size); assertEquals(0, pay.shots); assertFalse(d.keepsRecallImages())
        assertEquals("text_withheld", (r.content["screen"] as JsonObject).s("reason"))
        // 画面にはまだアプリがありません: キャプチャするものは何もありません。これはエラーではありません
        val empty = FrameScreen().apply { this.empty = true }
        assertTrue(toolDispatcher(empty, visionEnabled = true).execute(call("phone_back")).images.isEmpty())
    }

    @Test fun inlineScreenAttachesItsOwnShotOnce() = runTest {
        val s = FrameScreen()
        val d = toolDispatcher(s, visionEnabled = true, inlineScreen = true)
        d.execute(call("phone_observe"))
        val before = s.shots
        val r = d.execute(call("phone_back"))
        // 画面を使用する場合は、画面を読み取るものを使用し、別のスクリーンショットを撮らないでください
        assertEquals(before, s.shots)
        assertTrue(r.images.isEmpty())
        val screen = r.content["screen"] as JsonObject
        assertEquals("text_withheld", screen.s("reason"))
        assertFalse(screen.toString().contains("搜索"))
        assertTrue(r.imageIds.isEmpty())
    }

    @Test fun adaptiveVisionUsesElementsForReadableScreenAndKeepsRecordFrame() = runTest {
        val s = FrameScreen()
        val d = toolDispatcher(s, visionEnabled = true, adaptiveVision = true, recordFrames = true, inlineScreen = true)
        val observed = d.execute(call("phone_observe"))
        assertTrue(observed.images.isEmpty())
        assertEquals(0, s.shots)
        val action = d.execute(call("phone_back"))
        assertTrue(action.images.isEmpty())
        assertTrue("snapshotId" in action.content["screen"]!!.jsonObject)
        assertEquals(0, s.shots)
        val shot = d.execute(call("phone_screenshot"))
        assertTrue(shot.images.isEmpty())
        assertEquals("egress_blocked", shot.content.s("error"))
    }

    @Test fun adaptiveVisionEscalatesUnreadableControlsButExplicitElementReadStaysTextOnly() = runTest {
        val s = FrameScreen(listOf("", "", "", ""))
        val d = toolDispatcher(s, visionEnabled = true, adaptiveVision = true, inlineScreen = true)
        val elements = d.execute(call("phone_list_elements"))
        assertTrue(elements.images.isEmpty())
        assertEquals(0, s.shots)
        val observed = d.execute(call("phone_observe"))
        assertTrue(observed.images.isEmpty())
        assertEquals("text_withheld", observed.content.s("reason"))
        assertFalse(observed.content.toString().contains("搜索"))
        val action = d.execute(call("phone_back"))
        assertTrue(action.images.isEmpty())
        assertEquals("text_withheld", action.content["screen"]!!.jsonObject.s("reason"))
    }

    @Test fun screenshotToolIsRecallable() = runTest {
        val d = toolDispatcher(FrameScreen(), visionEnabled = true)
        d.execute(call("phone_observe"))
        val r = d.execute(call("phone_screenshot"))
        assertEquals("egress_blocked", r.content.s("error"))
        assertTrue(r.images.isEmpty())
        assertFalse(r.content.toString().contains("搜索"))
    }

    @Test fun noOpsSendsOriginalWithSniffedType() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13,
            'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 0, 0, 4, 200.toByte(), 0, 0, 10, 140.toByte())
        val p = ModelImage.part(png, null, 3)
        assertEquals("image/png", p.mime)
        assertEquals(1224 to 2700, p.width to p.height)
        assertEquals(3, p.screenshotId)
    }
}
