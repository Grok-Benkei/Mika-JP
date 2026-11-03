package com.wangzan101.mika.core.userscreen

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class UserScreenTest {
    private val zone = ZoneOffset.UTC
    private val at = 1_761_624_120_000L

    @Test fun composeAndParse() {
        val text = UserScreenMessage.compose("s1", " 第三条有没有坑? ")
        assertEquals("[[screen:s1]]\n第三条有没有坑?", text)
        val p = UserScreenMessage.parse(text)
        assertEquals(listOf("s1"), p.screens)
        assertEquals("第三条有没有坑?", p.words)
        assertEquals("[[screen:s1]]", UserScreenMessage.compose("s1", ""))
        assertEquals("", UserScreenMessage.parse("[[screen:s1]]").words)
    }

    @Test fun ordinaryTextAndAttachmentsAreNotScreens() {
        val text = "看看这张图\n附件:/workspace/inbox/a.jpg(12 KB)"
        assertFalse(UserScreenMessage.has(text))
        assertEquals(text, UserScreenMessage.parse(text).words)
        // 文の途中に書かれたこれらの単語はカウントされません
        assertFalse(UserScreenMessage.has("我打了 [[screen:s1]] 这几个字"))
    }

    @Test fun saidSentences() {
        val s = UserScreen("s1", at, "com.example.pay", "支付")
        assertEquals("ユーザーが今見ている状態を撮影したばかり（支付、Oct 28 04:02）。言葉は添えられていない。", UserScreenPrompt.said("s1", s, "", zone))
        assertEquals("ユーザーが今見ている状態を撮影したばかり（支付、Oct 28 04:02）。次の言葉を添えている: 记一下", UserScreenPrompt.said("s1", s, "记一下", zone))
        assertTrue(UserScreenPrompt.said("s1", s.copy(blocked = true), "", zone).contains("画像の取得を許可していないため、画像もその文面も取得されなかった"))
        assertTrue(UserScreenPrompt.said("s9", null, "", zone).contains("その画像はもう利用できない"))
    }

    @Test fun earlierLine() {
        val s = UserScreen("s1", at, "com.example.pay", "支付", lines = listOf(ScreenLine("支付成功", 500, 200), ScreenLine("¥38.50", 500, 260)))
        assertEquals("（ユーザーが以前に送った状態 s1: 支付、Oct 28 04:02。その状態は「支付成功 ¥38.50」で始まる）", UserScreenPrompt.earlier("s1", s, zone))
        assertTrue(UserScreenPrompt.earlier("s1", s.copy(lines = emptyList()), zone).contains("そこでは文面が読まれなかった"))
        assertTrue(UserScreenPrompt.earlier("s1", s.copy(blocked = true), zone).contains("画像の取得を許可していないため、何も取得されなかった"))
        val long = s.copy(lines = listOf(ScreenLine("字".repeat(100), 0, 0)))
        assertEquals(UserScreenPrompt.GIST_CHARS + 1, UserScreenPrompt.gist(long)!!.length)
    }

    @Test fun appFallsBackToPackage() {
        assertEquals("com.example.pay", UserScreen("s", 0, "com.example.pay", null).app)
        assertEquals(UserScreen.UNKNOWN_APP, UserScreen("s", 0, null, null).app)
    }

    @Test fun jsonRoundTrip() {
        val s = UserScreen("s1", at, "com.example.pay", "支付", blocked = false, lines = listOf(ScreenLine("支付成功", 500, 200)), fromOcr = true)
        assertEquals(s, UserScreenJson.decode(UserScreenJson.encode(s)))
        assertNull(UserScreenJson.decode("not json"))
    }

    /** は、画面読み取りポートによって与えられるものと同じです。中心はピクセル (1080×2400 による) で、境界は 0 ～ 999 に正規化されています。 [x]/[y]/[w]/[h] はすべて 0 ～ 999 で与えられます。 */
    private fun el(name: String, x: Int, y: Int, secret: Boolean = false, w: Int = 100, h: Int = 20) =
        UiElement("r", "TextView", name, Math.round(x * 1079 / 999.0).toInt(), Math.round(y * 2399 / 999.0).toInt(),
            listOf(x - w / 2, y - h / 2, x + w / 2, y + h / 2), null, false, false, false, secret = secret)

    @Test fun linesFromTreeAndOcrTopToBottomLeftToRight() {
        val snap = ScreenSnapshot("u", "com.example.chat", listOf(el("右边的话", 800, 300), el("左边的话", 200, 302), el("密码", 500, 500, secret = true)), 1080, 2400)
        val ocr = listOf(
            VisibleTextLine("图片里的字", listOf(100, 600, 400, 640)),
            VisibleTextLine("12:30", listOf(20, 2, 120, 20)),
        )
        val (lines, fromOcr) = UserScreenText.lines(snap, ocr, top = 30, bottom = 960)
        assertEquals(listOf("左边的话", "右边的话", "图片里的字"), lines.map { it.text })
        // モデルに与えられる中心点はピクセルではなく 0 ～ 999 です
        assertEquals(200, lines[0].x)
        assertEquals(302, lines[0].y)
        assertTrue(fromOcr)
        val (treeOnly, noOcr) = UserScreenText.lines(snap, null)
        assertEquals(2, treeOnly.size)
        assertFalse(noOcr)
    }

    @Test fun rowThatOnlyJoinsItsChildrenIsLeftOut() {
        // 1 行の設定項目: 行全体の名前は、内側の 2 つの段落を結合し、内側の 2 つの段落のみを残します (それぞれ独自の位置を持ちます)。行全体の名前に独自の文字が含まれている場合でも、その名前は保持されます。
        val snap = ScreenSnapshot("u", "com.example.settings", listOf(
            el("WLAN 家里的网络", 500, 400, w = 900, h = 60), el("WLAN", 250, 400), el("家里的网络", 800, 400),
            el("移动网络", 500, 500, w = 900, h = 60), el("移动网络", 280, 500),
            el("蓝牙,已开启", 500, 600, w = 900, h = 60), el("蓝牙", 250, 600),
        ), 1080, 2400)
        val (lines, _) = UserScreenText.lines(snap, null)
        assertEquals(listOf("WLAN", "家里的网络", "移动网络", "蓝牙", "蓝牙,已开启"), lines.map { it.text })
    }

    @Test fun poorTreeNeedsOcr() {
        assertTrue(UserScreenText.needsOcr(ScreenSnapshot("u", "com.example.chat", emptyList(), 1080, 2400)))
        val rich = (0 until 10).map { el("第 $it 行", 500, 50 + it * 80) }
        assertFalse(UserScreenText.needsOcr(ScreenSnapshot("u", "com.example.chat", rich, 1080, 2400)))
    }
}
