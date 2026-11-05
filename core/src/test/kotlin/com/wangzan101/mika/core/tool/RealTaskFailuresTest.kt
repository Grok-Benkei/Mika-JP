package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.DownloadInfo
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.cap.web.NavResult
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.gate.classify
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 実機タスクでのいくつかのタイプのエラーを修正するための一般的な方法: ウィンドウを読み取れないことはウィンドウが開いていないことを意味するわけではなく、開けない場合でもページを現状のまま報告し、検索ボックスで Enter キーを押しても送信を意味するわけではありません。 */
class RealTaskFailuresTest {
    private var n = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c${++n}", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    /** アプリを開いたものの、ウィンドウ (セキュリティ ウィンドウ、起動画面) を読み取ることができませんでした。画面にはパッケージ名のみが読み取られましたが、要素はありませんでした。 */
    private class UnreadableScreen(private val inner: FakeScreen = FakeScreen(), var unreadable: String? = "com.bank") : ScreenPort by inner {
        override suspend fun snapshot() = ScreenSnapshot("p${++reads}", null, emptyList(), 1080, 2400, unreadableApp = unreadable)
        override suspend fun peek() = snapshot()
        var reads = 0
    }

    @Test fun openButUnreadableWindowIsNotNothingOpen() = runTest {
        val d = toolDispatcher(UnreadableScreen())
        val o = d.execute(call("phone_observe")).content
        assertEquals("com.bank", o.s("currentApp"))
        assertEquals("false", o.s("readable"))
        val hint = o.s("hint")
        assertTrue(hint, "再度起動しない" in hint && "hand_to_user" in hint)
        assertFalse("戻る操作を1回" in hint)
        val l = d.execute(call("phone_launch_app", """{"package":"com.bank"}""")).content
        assertEquals("true", l.s("ok")); assertEquals("false", l.s("readable"))
        assertTrue(l.s("hint"), "再度起動しない" in l.s("hint"))
        val c = d.execute(call("phone_diagnose")).content
        assertEquals("com.bank", c.s("currentApp"))
        assertTrue(c.s("hint"), "再度起動しない" in c.s("hint"))
    }

    @Test fun nothingLaunchedStillSaysStartWithLaunch() = runTest {
        val empty = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("p1", null, emptyList(), 1080, 2400, hasWindow = false)
            override suspend fun peek() = snapshot()
        }
        val o = toolDispatcher(empty).execute(call("phone_observe")).content
        assertTrue(o.s("hint"), "phone_launch_app" in o.s("hint"))
        assertFalse("readable" in o)
    }

    @Test fun appThatNeverReachesTheForegroundIsAFailure() = runTest {
        val screen = FakeScreen().apply { result = com.wangzan101.mika.core.uistate.ActionResult.Failed("app_not_in_front", hint = "In the foreground now: com.launcher.") }
        val r = toolDispatcher(screen).execute(call("phone_launch_app", """{"package":"com.x"}""")).content
        assertEquals("false", r.s("ok")); assertEquals("app_not_in_front", r.s("error"))
        assertTrue(r.s("message"), "前景に来なかった" in r.s("message") && "com.launcher" in r.s("message"))
    }

    private class NavWeb(val inner: FakeWebPort = FakeWebPort(), var shape: (NavResult) -> NavResult) : WebPort by inner {
        override suspend fun navigate(url: String, newTab: Boolean) = shape(inner.navigate(url, newTab))
    }

    @Test fun navigationErrorsAreNotReportedAsSuccess() = runTest {
        val web = NavWeb { it.copy(loaded = false, httpStatus = null, netError = "net::ERR_NAME_NOT_RESOLVED") }
        val r = toolDispatcher(FakeScreen(), web = web).execute(call("web_navigate", """{"url":"https://nope.example.com/"}""")).content
        assertEquals("false", r.s("ok")); assertEquals("navigation_failed", r.s("error"))
        assertEquals("net::ERR_NAME_NOT_RESOLVED", r.s("netError"))
        assertTrue(r.s("message"), "net::ERR_NAME_NOT_RESOLVED" in r.s("message"))
    }

    @Test fun cleartextUpgradeAndDownloadsAreSaid() = runTest {
        val up = NavWeb { it.copy(upgradedFrom = "http://shop.example.com/") }
        val u = toolDispatcher(FakeScreen(), web = up).execute(call("web_navigate", """{"url":"http://shop.example.com/"}""")).content
        assertEquals("true", u.s("ok")); assertEquals("true", u.s("upgradedToHttps")); assertEquals("http://shop.example.com/", u.s("requestedUrl"))
        val dl = NavWeb { it.copy(loaded = false, download = DownloadInfo("/workspace/downloads/a.pdf", 0, "application/pdf", "running", null)) }
        val d = toolDispatcher(FakeScreen(), web = dl).execute(call("web_navigate", """{"url":"https://shop.example.com/a.pdf"}""")).content
        assertEquals("true", d.s("ok")); assertEquals("false", d.s("loaded"))
        val info = d["download"]!!.jsonObject
        assertEquals("/workspace/downloads/a.pdf", info.s("file")); assertEquals("running", info.s("status"))
        assertTrue(d.s("hint"), "ページとして開かれる代わりにダウンロードされた" in d.s("hint") && "status=done" in d.s("hint"))
    }

    private fun el(name: String, role: String = "EditText", hint: Boolean = false, placeholder: String? = null, search: Boolean = false) =
        UiElement("@e1", role, name, 540, 50, listOf(0, 0, 999, 100), null, true, true, false, hint = hint, focused = true, placeholder = placeholder, searchField = search)
    private fun snap(vararg e: UiElement) = ScreenSnapshot("p1", "com.app", e.toList(), 1080, 2400)
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
    private val enter = args("""{"text":"耳机","pressEnter":true}""")

    @Test fun enterInASearchFieldIsReversibleByElementSemantics() {
        // 入力後の名前は入力したものと同じになります: コントロール独自のプロンプト、役割、またはセマンティクスに依存して検索ボックスを認識します
        assertNull(IrreversibleDetector.classify("phone_type_text", enter, snap(el("耳机", placeholder = "搜索商品"))))
        assertNull(IrreversibleDetector.classify("phone_type_text", enter, snap(el("耳机", role = "SearchView\$SearchAutoComplete"))))
        assertNull(IrreversibleDetector.classify("web_type_text", enter, snap(el("耳机", role = "searchbox"))))
        assertNull(IrreversibleDetector.classify("web_type_text", enter, snap(el("耳机", role = "textbox", search = true))))
        // Enter を押すか、他の入力ボックスで通常どおり送信を押します
        assertEquals(IrrevAction.send, IrreversibleDetector.classify("phone_type_text", enter, snap(el("耳机", placeholder = "说点什么")))?.action)
        assertEquals(IrrevAction.send, IrreversibleDetector.classify("phone_type_text", enter, snap(el("帮我搜索一下")))?.action)
    }

    /** 間違った画面番号が使用され、通話が拒否されても「最近操作した画面」は変更されません。その後、画面に名前を付けずに確認および承諾しても、元の画面が参照されます。 */
    @Test fun misaimedCallsDoNotMoveTheRememberedScreen() = runTest {
        val d = toolDispatcher(FakeScreen(), web = FakeWebPort())
        val ex = com.wangzan101.mika.core.action.ActionExecutor(d, com.wangzan101.mika.core.guard.OperationGuards(), null, gate = {},
            recorder = com.wangzan101.mika.core.action.ActionRecorder(com.wangzan101.mika.core.record.RunSink.NONE))
        d.execute(call("web_navigate", """{"url":"https://shop.example.com/"}"""))
        val webSid = d.execute(call("web_observe")).content.s("snapshotId")
        d.execute(call("phone_observe"))
        assertEquals("phone", d.surface)
        // 別の画面に与えられたツール: 拒否されました、変更なし
        val wrong = ex.run(call("web_tap", """{"ref":"@e1","snapshotId":"${d.execute(call("phone_observe")).content.s("snapshotId")}"}"""), com.wangzan101.mika.core.action.Origin.Model(2))
        assertTrue(wrong.toString(), "wrong_surface" in wrong.toString())
        assertEquals("phone", d.surface)
        assertTrue(webSid.startsWith("w"))
    }
}
