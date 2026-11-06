package com.wangzan101.mika.core.vision

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

internal val FRAME_A: ByteArray = JvmTestImageOps.encode(100, 200) { x, _ -> if (x < 50) 30 else 200 }
internal val FRAME_B: ByteArray = JvmTestImageOps.encode(100, 200) { _, y -> if (y < 100) 30 else 200 }
internal val BLACK: ByteArray = JvmTestImageOps.encode(100, 200) { _, _ -> 0 }

internal fun uiEl(ref: String, name: String, bounds: List<Int>, res: String? = null) =
    UiElement(ref, "button", name, 0, 0, bounds, res, true, false, false)

/** のスクリーンショットは [shots] に従って順番に与えられ、最後のスクリーンショットは与えられた後に与えられます。 [peek] は [elements] に戻ります。 */
internal class LocScreen(
    var elements: List<UiElement> = emptyList(),
    vararg shots: ByteArray?,
    var secure: Boolean = false,
    var hasWindow: Boolean = true,
) : ScreenPort {
    var peekFails = false
    var width = 1080
    var height = 2400
    private val queue = ArrayDeque(shots.toList().ifEmpty { listOf(FRAME_A) })
    var peeks = 0
    val taps = mutableListOf<String>()
    fun show(vararg next: ByteArray?) { queue.clear(); queue.addAll(next.toList()) }
    private fun read(id: String) = ScreenSnapshot(id, if (hasWindow) "com.android.settings" else null, elements, width, height, hasWindow = hasWindow)
    override suspend fun snapshot() = read("s")
    override suspend fun peek(): ScreenSnapshot {
        if (peekFails) throw IllegalStateException("unreadable")
        peeks++; return read("p$peeks")
    }
    override suspend fun screenshotPng(): ByteArray? = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
    override suspend fun isProtected() = secure
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { taps += "ref $ref"; return ActionResult.Ok(changed = true) }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { taps += "$x,$y"; return ActionResult.Ok(changed = true) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
    override suspend fun back(): ActionResult { taps += "phone_back"; return ActionResult.Ok(changed = true) }
    override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
    override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = listOf("com.android.settings" to "设置")
}

internal fun says(vararg replies: Any) = FakeModel(*replies.map { if (it is String) ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) else it }.toTypedArray())

class LocatorTest {
    private val wlan = uiEl("@e1", "WLAN", listOf(0, 100, 999, 160))
    private fun locator(screen: ScreenPort, model: FakeModel, registry: LocateRegistry = LocateRegistry()) =
        Locator(screen, VisionService(model, JvmTestImageOps), registry, ScreenFamily("phone", "p"))

    private fun found(r: LocateResult) = r as? LocateResult.Found ?: throw AssertionError("expected Found, got $r")
    private fun notFound(r: LocateResult) = r as? LocateResult.NotFound ?: throw AssertionError("expected NotFound, got $r")

    @Test fun treePathUniqueExactMatch() = runTest {
        // 同じ中心にある同じ名前のネストされた要素は 1 つとしてカウントされます
        val inner = uiEl("@e2", "wlan", listOf(10, 110, 989, 150))
        val m = says()
        val reg = LocateRegistry()
        val r = found(locator(LocScreen(listOf(wlan, inner, uiEl("@e3", "WLAN 设置说明", listOf(0, 300, 999, 360)))), m, reg).locate("WLAN 那一行", " W LAN "))
        assertEquals("elements", r.source)
        assertEquals(500 to 130, r.x to r.y)
        assertArrayEquals(intArrayOf(0, 100, 999, 160), r.box)
        assertEquals("@v1", r.ref); assertTrue(r.snapshotId.startsWith("loc_"))
        assertTrue("視覚モデルは呼び出しません", m.requests.isEmpty())
        val (p, hash) = reg.get(r.ref, r.snapshotId)!!
        assertEquals(Point(500, 130), p); assertEquals(JvmTestImageOps.aHash(FRAME_A), hash)
        // リソース ID も認識されます
        val byId = found(locator(LocScreen(listOf(uiEl("@e9", "", listOf(0, 0, 100, 100), res = "com.x:id/Wifi_Switch"))), says()).locate("开关", "com.x:id/wifi_switch"))
        assertEquals(50 to 50, byId.x to byId.y)
    }

    @Test fun treePathAmbiguousFallsToVision() = runTest {
        val twice = listOf(wlan, uiEl("@e2", "WLAN", listOf(0, 500, 999, 560)))
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = notFound(locator(LocScreen(twice), m).locate("顶上那个 WLAN", "WLAN"))
        assertEquals("egress_blocked", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun treePathSkippedWhenScreenMoved() = runTest {
        val s = LocScreen(listOf(wlan), FRAME_A, FRAME_B)
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "yes")
        val r = notFound(locator(s, m).locate("WLAN", "WLAN"))
        assertEquals("egress_blocked", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun rejectsMalformedBoxes() = runTest {
        for (reply in listOf(
            """{"bbox_2d":[0.1,0.2,0.3,0.4],"seen":"x"}""",
            """{"bbox_2d":[0,0,100,100]} {"bbox_2d":[200,200,300,300]}""",
            """{"bbox_2d":[300,0,100,100]}""",
            """{"bbox_2d":[0,0,1001,100]}""",
            "我在屏幕上没有看到它",
            "null",
            "```json\nnull\n```",
            // には bbox_2d キーが 1 つだけありますが、4 つの数値の 2 つのグループが与えられます。
            """{"bbox_2d":[0,0,100,100],"other":[200,200,300,300]}""",
        )) {
            val m = says(reply, "是")
            val r = notFound(locator(LocScreen(), m).locate("某个图标", null))
            assertEquals(reply, "egress_blocked", r.reason)
            assertEquals(reply, 0, m.requests.size)
        }
    }

    @Test fun scalesTo999() = runTest {
        val m = says("```json\n{\"bbox_2d\": [500, 0, 1000, 1000]}\n```", "Yes.")
        val r = notFound(locator(LocScreen(), m).locate("右半边", null))
        assertEquals("egress_blocked", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun nameMismatchOnSeen() = runTest {
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"蓝牙"}""")
        val r = notFound(locator(LocScreen(), m).locate("WLAN", "WLAN"))
        assertEquals("egress_blocked", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun nameMismatchOnElementAtCenter() = runTest {
        val bt = uiEl("@e1", "蓝牙", listOf(0, 100, 999, 160))
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""")
        val r = notFound(locator(LocScreen(listOf(bt)), m).locate("WLAN", "WLAN"))
        assertEquals("egress_blocked", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun notConfirmedUnlessYes() = runTest {
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        for (answer in listOf("否", "不是", "可能是", "No", "yesterday", "是", "是的", "Yes", " yes, it is")) {
            val m = says(box, answer)
            val r = notFound(locator(LocScreen(), m).locate("WLAN", null))
            assertEquals(answer, "egress_blocked", r.reason)
            assertTrue(m.requests.isEmpty())
        }
    }

    @Test fun protectedContent() = runTest {
        for (s in listOf(LocScreen(secure = true), LocScreen(emptyList(), BLACK, secure = true))) {
            val m = says()
            val r = notFound(locator(s, m).locate("WLAN", "WLAN"))
            assertEquals("protected_content", r.reason)
            assertTrue("保護された画面は視覚モデルへ渡しません", m.requests.isEmpty())
            try {
                locator(s, m).describe("看看")
                fail("expected protected")
            } catch (e: ScreenUnavailable) {
                assertEquals("protected_content", e.reason)
            }
            assertTrue(m.requests.isEmpty())
        }
        // アプリがまだ開かれていません (仮想画面が構築されていません): 写真をキャプチャできません。わかりやすく説明してください。エラーではありません。
        val none = LocScreen(emptyList(), null, hasWindow = false)
        assertEquals("no_app_open", notFound(locator(none, says()).locate("WLAN", null)).reason)
        try {
            locator(none, says()).describe("看看"); fail("expected no_app_open")
        } catch (e: ScreenUnavailable) {
            assertEquals("no_app_open", e.reason)
        }
    }

    @Test fun paymentPageCanBeSentToVision() = runTest {
        val pages = listOf(
            listOf(uiEl("@e1", "确认支付", listOf(0, 800, 999, 900))),
            listOf(uiEl("@e1", "实付 ¥39.90", listOf(0, 600, 600, 660)), uiEl("@e2", "支付方式", listOf(0, 700, 999, 760))),
        )
        for (els in pages) {
            assertEquals("egress_blocked", locator(LocScreen(els), says()).visionBlocker())
        }
        // 商品ページの価格は支払いページに含まれていません
        val buy = says("""{"bbox_2d":[0,900,1000,1000]}""", "是")
        assertEquals("egress_blocked", notFound(locator(LocScreen(listOf(uiEl("@e1", "¥39.90", listOf(0, 600, 600, 660)), uiEl("@e2", "立即购买", listOf(0, 900, 999, 999)))), buy).locate("购买按钮", null)).reason)
        assertTrue(buy.requests.isEmpty())
        // 画面が読み取れない場合は確認・送信する方法がありません。
        val m = says()
        val r = notFound(locator(LocScreen().apply { peekFails = true }, m).locate("图标", null))
        assertEquals("vision_unavailable", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun treeMatchNeedsOnScreenElement() = runTest {
        // 画面外の要素は端に押し出され、面積は 0 になります。ヒットとしてカウントされず、視覚効果が変わります。
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = notFound(locator(LocScreen(listOf(uiEl("@e1", "WLAN", listOf(0, 999, 999, 999)))), m).locate("WLAN", "WLAN"))
        assertEquals("egress_blocked", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun visionErrorIsVisionFailed() = runTest {
        assertEquals("egress_blocked", notFound(locator(LocScreen(), says(ModelError.Network())).locate("WLAN", null)).reason)
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        assertEquals("egress_blocked", notFound(locator(LocScreen(), says(box, ModelError.Server(500))).locate("WLAN", null)).reason)
    }

    @Test fun describeSendsTaskAndScreenshot() = runTest {
        val m = says("[屏幕] 设置首页")
        try {
            locator(LocScreen(), m).describe("打开 WLAN")
            fail("screenshot must not be sent")
        } catch (e: ScreenUnavailable) {
            assertEquals("egress_blocked", e.reason)
        }
        assertTrue(m.requests.isEmpty())
    }

    /** は測位精度未検証の映像閲覧モデルです。 */
    private fun unverified(vararg replies: String) = FakeModel(
        *replies.map { ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }.toTypedArray(), groundingVerified = false)

    /** 便利な画面: 名前付きの 8 つのボタンが、探しているボックスにはありません。 */
    private fun adequate() = LocScreen((1..8).map { uiEl("@e$it", "设置项$it", listOf(0, 300 + it * 70, 999, 360 + it * 70)) })

    @Test fun unverifiedVisionBoxNeedsSecondConsistentLook() = runTest {
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        val m = unverified(box, "是", """{"bbox_2d":[10,105,990,158],"seen":"WLAN"}""", "是")
        val r = notFound(locator(adequate(), m).locate("WLAN 那一行", null))
        assertEquals("egress_blocked", r.reason)
        assertEquals(0, m.requests.size)
    }

    @Test fun unusableTreeLetsAnUnbackedVisionBoxThroughMarkedUnverified() = runTest {
        // ツリーには何もありません (アプリは独自のインターフェイスを描画します): 比較するものがありません。一度箱を追加して、渡す前にもう一度読んでください。裏付けとなる証拠がないものとしてマークします。
        val m = unverified("""{"bbox_2d":[0,880,1000,940],"seen":"输入框"}""", "是")
        val r = notFound(locator(LocScreen(), m).locate("底部的输入框", null))
        assertEquals("egress_blocked", r.reason)
        assertEquals(0, m.requests.size)
    }

    @Test fun partialTreeNeedsBackingOnlyWhenTheTargetIsInIt() = runTest {
        // ほとんどのコントロールには名前がありません: 1 つの部分がありません
        val blank = (1..4).map { UiElement("@b$it", "button", "", 0, 0, listOf(0, 500 + it * 80, 200, 560 + it * 80), null, true, false, false) }
        val partial = blank + uiEl("@e9", "发送消息", listOf(800, 900, 999, 960))
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"发送"}"""
        // 探している単語はツリー内にあります (フレームがツリーに落ちていないだけです): まだもう一度探す必要があります
        val blocked = notFound(locator(LocScreen(partial), unverified(box, "是", "null")).locate("发送按钮", "发送"))
        assertEquals("egress_blocked", blocked.reason)
    }

    @Test fun unverifiedVisionBoxBackedByTreeLabel() = runTest {
        // ボックスの中央にある要素の名前が、探している単語です。再表示は 1 回で十分です (コントロール ツリーには同じ名前が 2 つあるため、完全に一致する必要はありません)。
        val dup = uiEl("@e2", "WLAN", listOf(0, 600, 999, 660))
        val m = unverified("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = notFound(locator(LocScreen(listOf(wlan, dup)), m).locate("WLAN", "WLAN"))
        assertEquals("egress_blocked", r.reason); assertEquals(0, m.requests.size)
        // コントロール ツリーは、モデルに関係なく、通常どおり正確に一致します。
        val tree = unverified()
        assertEquals("elements", found(locator(LocScreen(listOf(wlan)), tree).locate("WLAN", "WLAN")).source)
        assertEquals(0, tree.requests.size)
    }
}
