package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.BatchRunner
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.EvalJs
import com.wangzan101.mika.core.cap.web.WaitOutcome
import com.wangzan101.mika.core.cap.web.ExternalLink
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.uistate.PlacePace
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.testing.verb
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.testing.WEB_TOOLS

class ToolDispatcherWebTest {
    private var n = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c${++n}", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun d(phone: FakeScreen = FakeScreen(), web: FakeWebPort = FakeWebPort(), ws: ((String) -> WorkspacePathCheck)? = { WorkspacePathCheck.Ok }) =
        toolDispatcher(phone, web = web, workspacePath = ws)
    private suspend fun ToolDispatcher.go(url: String = "https://shop.example.com/cart") = execute(call("web_navigate", """{"url":"$url"}""")).content

    @Test fun noWebPortNoBrowserTools() = runTest {
        val plain = toolDispatcher(FakeScreen())
        assertTrue(plain.definitions().none { it.name.startsWith("web_") })
        assertEquals("unknown_tool", plain.execute(call("web_navigate", """{"url":"https://example.com"}""")).content.s("error"))
        assertEquals("unknown_tool", plain.execute(call("web_observe")).content.s("error"))
        val o = plain.execute(call("phone_observe")).content
        assertFalse("surface" in o); assertEquals("com.x", o.s("currentApp"))
        // Web ページのツール セット全体が利用可能です (画像を表示する人は画像表示モデルを持っている必要があり、サイト コマンドにはコマンドを宣言するサイトの知識が必要です)。
        val vision = setOf(Verb.Screenshot, Verb.Describe, Verb.FindVisually, Verb.SiteCommand)
        assertEquals(WEB_TOOLS.filter { verb(it) !in vision }.toSet(), d().definitions().map { it.name }.filter { it.startsWith("web_") }.toSet())
    }

    @Test fun siteModeSwitchesAndDegrades() = runTest {
        val web = FakeWebPort()
        val t = d(web = web)
        assertEquals("site_mode_unavailable", t.execute(call("web_site_mode", """{"mode":"mobile"}""")).content.s("error"))
        web.siteModeHost = "shop.example.com"
        val r = t.execute(call("web_site_mode", """{"mode":"mobile"}""")).content
        assertEquals("true", r.s("ok")); assertEquals("shop.example.com", r.s("site")); assertEquals("mobile", r.s("mode")); assertEquals("true", r.s("reloaded"))
        assertEquals("invalid_arguments", t.execute(call("web_site_mode", """{"mode":"tablet"}""")).content.s("error"))
        assertEquals(listOf("siteMode mobile"), web.calls)
    }

    @Test fun keepOpenMarksThePageAndSaysSo() = runTest {
        val web = FakeWebPort()
        val t = d(web = web)
        // 左ページのないページ: レポートは利用できません。レポートから離れるふりをしないでください。
        assertEquals("keep_open_unavailable", t.execute(call("web_keep_open", """{"reason":"接着播放"}""")).content.s("error"))
        web.keepAvailable = true
        val r = t.execute(call("web_keep_open", """{"reason":"接着播放"}""")).content
        assertEquals("true", r.s("ok")); assertEquals("true", r.s("keep")); assertEquals("shop.example.com", r.s("site")); assertEquals("2", r.s("hours"))
        assertFalse("replaces" in r)
        // が別の Web サイトからページを離れました: そのページは閉じられることを意味します
        web.keptHost = "course.example.org"
        val replaced = t.execute(call("web_keep_open", """{"reason":"接着播放"}""")).content
        assertEquals("course.example.org", replaced.s("replaces"))
        assertTrue(replaced.s("hint")!!.contains("course.example.org"))
        // キャンセル
        val back = t.execute(call("web_keep_open", """{"reason":"不用了","keep":false}""")).content
        assertEquals("false", back.s("keep")); assertFalse("hours" in back)
        assertEquals(listOf("keepOpen true 接着播放", "keepOpen true 接着播放", "keepOpen false 不用了"), web.calls)
        // ユーザー自身のページを保存する必要はありません。クラッシュしたレポート tab_crashed;見つからない理由はパラメータエラーです
        web.usersPage = true
        assertEquals("users_page", t.execute(call("web_keep_open", """{"reason":"x"}""")).content.s("error"))
        web.usersPage = false; web.crash = true
        assertEquals("tab_crashed", t.execute(call("web_keep_open", """{"reason":"x"}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_keep_open", "{}")).content.s("error"))
    }

    @Test fun eachScreenHasItsOwnTools() = runTest {
        val phone = FakeScreen(); val web = FakeWebPort(); val t = d(phone, web)
        assertEquals(PHONE, t.surface)
        assertFalse("surface" in t.execute(call("phone_observe")).content)
        val r = t.go()
        assertEquals("true", r.s("ok")); assertEquals("t1", r.s("tabId")); assertEquals("200", r.s("httpStatus")); assertEquals("true", r.s("loaded"))
        assertFalse("surface" in r)
        assertEquals(WEB, t.surface)
        val o = t.execute(call("web_observe")).content
        assertFalse("surface" in o); assertTrue(o.s("snapshotId").startsWith("w"))
        t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"${o.s("snapshotId")}"}"""))
        t.execute(call("web_back"))
        assertEquals(listOf("navigate https://shop.example.com/cart false", "snapshot", "tapRef @e1 wt1-1", "historyBack"), web.calls)
        // Web ページを開いた後も、同じ携帯電話設定セットが携帯電話に適用されます。
        t.execute(call("phone_observe"))
        assertEquals(PHONE, t.surface)
        assertEquals(2, phone.snapshots); assertTrue(phone.calls.isEmpty())
        assertEquals(4, web.calls.size)
    }

    @Test fun webActionFailuresCarryCodesAndPortHint() = runTest {
        val web = FakeWebPort(); val t = d(web = web)
        t.go()
        val sid = t.execute(call("web_observe")).content.s("snapshotId")
        web.result = ActionResult.Failed("obscured", hint = "Covered by 「接受 Cookie」.")
        val r = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).content
        assertEquals("obscured", r.s("error"))
        assertTrue(r.s("message"), r.s("message").contains("Covered by 「接受 Cookie」."))
        assertTrue(r.s("message"), "別のものがその要素を覆っている" in r.s("message") && "まずそれを閉じるか受け入れてから" in r.s("message"))
        for (code in listOf("offscreen", "key_not_allowed", "no_tab_open", "focus_moved")) {
            web.result = ActionResult.Failed(code)
            val f = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).content
            assertEquals(code, f.s("error"))
            assertFalse(f.s("message"), f.s("message").startsWith("その操作が失敗した"))
        }
    }

    @Test fun stableRefsKeepEachScreensNumbersApart() = runTest {
        val phone = FakeScreen(); val web = FakeWebPort()
        val t = toolDispatcher(phone, web = web, stableRefs = true)
        val p = t.execute(call("phone_observe")).content
        val phoneSid = p.s("snapshotId")
        assertEquals("p1", phoneSid)
        t.go()
        val w = t.execute(call("web_observe")).content
        val webSid = w.s("snapshotId")
        assertEquals("w1", webSid)
        // 携帯電話の画面の番号は、Web ページ上のツールに与えられます。ポートに与えられていない場合は、それが携帯電話に属し、どのツールを使用する必要があるかを意味します。
        val r = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$phoneSid"}""")).content
        assertEquals("wrong_surface", r.s("error"))
        assertTrue(r.s("message"), "phone_tap" in r.s("message") && "web_observe" in r.s("message"))
        assertTrue(web.calls.none { it.startsWith("tapRef") })
        // およびその逆
        val back = t.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"$webSid"}""")).content
        assertEquals("wrong_surface", back.s("error"))
        assertTrue(back.s("message"), "web_tap" in back.s("message") && "phone_observe" in back.s("message"))
        assertTrue(phone.calls.none { it.startsWith("tap") })
        // のそれぞれの番号は、通常どおりポート番号に変換されます (番号付けスペースは独自のものに属し、@e1 は 2 つの画面に同時に存在できます)。 画面を数回読み取った後も、
        assertEquals("true", w.s("egressBlocked"))
        assertEquals("browser_or_webview", w.s("reason"))
        assertFalse("搜索" in w.toString())
        assertTrue(web.calls.none { it.startsWith("tapRef") })
    }

    @Test fun pageReadsAndScriptResultsReachTheVerifier() = runTest {
        val web = FakeWebPort(); val t = d(web = web)
        t.go()
        web.page = web.page.copy(text = "第二段正文:车次 G7311")
        t.execute(call("web_read_page", """{"offset":5000}"""))
        web.eval = EvalJs.Ok("\"G7501\"", false)
        t.execute(call("web_evaluate_js", """{"script":"page.title"}"""))
        // がまだ存在します: 画面の読み取りによって絞り出されません
        repeat(5) { t.execute(call("web_observe")) }
        val seen = t.evidence().read(ObsKind.Read, ObsKind.Reply).joinToString("\n")
        assertFalse(seen, seen.contains("第二段正文:车次 G7311"))
        assertFalse(seen, seen.contains("G7501"))
        assertTrue(seen, seen.contains("egress_blocked"))
    }

    @Test fun handedOverTabRefusesWebToolsAndDownloadsSayUnavailable() = runTest {
        val web = FakeWebPort(); val t = d(web = web)
        t.go()
        web.handedOver = true
        assertEquals("handed_over", t.execute(call("web_read_page")).content.s("error"))
        assertEquals("handed_over", t.execute(call("web_evaluate_js", """{"script":"1"}""")).content.s("error"))
        assertEquals("handed_over", t.execute(call("web_navigate", """{"url":"https://shop.example.com/"}""")).content.s("error"))
        assertEquals("handed_over", t.execute(call("web_tabs", """{"action":"switch","tabId":"t2"}""")).content.s("error"))
        web.handedOver = false
        web.downloadsAvailable = false
        assertEquals("downloads_unavailable", t.execute(call("web_downloads")).content.s("error"))
    }

    /** ルール: Web データ スクリプトは読み取り専用でのみ実行でき、軽量モデルも使用できます。 */
    @Test fun evaluateJsStaysAllowedForTheLightModel() {
        assertFalse(com.wangzan101.mika.core.agent.ModelRouter.mainOnly("web_evaluate_js", verb("web_evaluate_js")))
    }

    @Test fun browserToolsAreNotBatchable() {
        val (steps, problem) = BatchRunner.parse(
            """{"steps":[{"tool":"web_navigate","args":{"url":"https://example.com"}},{"tool":"web_tap","args":{"x":1,"y":1}}]}""",
            setOf("web_navigate", "web_tap"), BATCHABLE)
        assertNull(steps)
        assertTrue(problem!!, problem.contains("cannot be batched"))
    }

    @Test fun launchAppActsOnThePhone() = runTest {
        val phone = FakeScreen(); val t = d(phone)
        t.go()
        assertEquals("true", t.execute(call("phone_launch_app", """{"package":"com.x"}""")).content.s("ok"))
        assertEquals(PHONE, t.surface)
        assertEquals("com.x", t.execute(call("phone_observe")).content.s("currentApp"))
        assertEquals(listOf("open com.x false"), phone.calls)
    }

    @Test fun otherScreensSnapshotIsWrongSurfaceWithTheRightTool() = runTest {
        val phone = FakeScreen(); val web = FakeWebPort(); val t = d(phone, web)
        val phoneSid = t.execute(call("phone_observe")).content.s("snapshotId")
        t.go()
        val r = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$phoneSid"}""")).content
        assertEquals("wrong_surface", r.s("error"))
        assertTrue(r.s("message"), "そのスナップショットで phone_tap を使う" in r.s("message") && "web_observe を呼び" in r.s("message"))
        val webSid = t.execute(call("web_observe")).content.s("snapshotId")
        t.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val r2 = t.execute(call("phone_type_text", """{"text":"a","ref":"@e1","snapshotId":"$webSid"}""")).content
        assertEquals("wrong_surface", r2.s("error"))
        assertTrue(r2.s("message"), "そのスナップショットで web_type_text を使う" in r2.s("message"))
        assertTrue(web.calls.none { it.startsWith("tapRef") || it.startsWith("type") })
        assertTrue(phone.calls.none { it.startsWith("tapRef") || it.startsWith("type") })
    }

    @Test fun webObserveHasSiteUrlTitleNoCurrentApp() = runTest {
        val t = d(); t.go()
        val o = t.execute(call("web_list_elements")).content
        assertFalse("currentApp" in o)
        assertEquals("shop.example.com", o.s("currentSite"))
        assertEquals("true", o.s("egressBlocked"))
        assertEquals("browser_or_webview", o.s("reason"))
        assertFalse("url" in o); assertFalse("title" in o); assertFalse("elements" in o)
        assertFalse("搜索" in o.toString()); assertFalse("购物车" in o.toString())
    }

    @Test fun rejectsNonHttpUrls() = runTest {
        val web = FakeWebPort(); val t = d(web = web)
        for (u in listOf("file:///sdcard/a.html", "content://x/y", "javascript:alert(1)", "data:text/html,hi", "example.com")) {
            assertEquals(u, "invalid_arguments", t.go(u).s("error"))
        }
        for (u in listOf("intent://x#Intent;package=com.example.app;end", "weixin://dl/scan")) {
            assertEquals(u, "external_app_link", t.go(u).s("error"))
        }
        // 呼び出しが失敗しても、最新の操作の画面が変更されない
        assertTrue(web.calls.isEmpty()); assertEquals(PHONE, t.surface)
    }

    @Test fun externalLinkSurfaced() = runTest {
        val web = FakeWebPort().apply { externalLink = ExternalLink("intent://pay#Intent;package=com.example.pay;end", "com.example.pay") }
        val t = d(web = web)
        val r = t.go("https://shop.example.com/pay")
        assertEquals("false", r.s("ok")); assertEquals("external_app_link", r.s("error"))
        assertEquals("com.example.pay", r.s("package")); assertEquals("true", r.s("recoverable"))
        assertEquals("このリンクはアプリを開こうとしている。作業にそのアプリが必要な場合は、このパッケージでスマートフォンで開く。", r.s("message"))
        assertEquals(PHONE, t.surface)
        web.externalLink = null; web.crash = true
        assertEquals("tab_crashed", t.go().s("error"))
    }

    @Test fun readPageDefaultsAndCaps() = runTest {
        val web = FakeWebPort(); val t = d(web = web); t.go()
        val r = t.execute(call("web_read_page")).content
        assertEquals("true", r.s("egressBlocked"))
        assertEquals("browser_or_webview", r.s("reason"))
        assertFalse("正文" in r.toString()); assertFalse("links" in r)
        t.execute(call("web_read_page", """{"maxChars":30000,"offset":12000}"""))
        assertEquals(listOf("readPage 12000 0", "readPage 30000 12000"), web.calls.filter { it.startsWith("readPage") })
        assertEquals("invalid_arguments", t.execute(call("web_read_page", """{"maxChars":30001}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_read_page", """{"offset":-1}""")).content.s("error"))
    }

    @Test fun readPageReportsQualityAndReadsUrlsInTheBackground() = runTest {
        val web = FakeWebPort(); val t = d(web = web); t.go()
        web.quality = com.wangzan101.mika.core.cap.web.ReadQuality(
            1234, 0.82, com.wangzan101.mika.core.cap.web.ReadVerdict.NEEDS_SIGN_IN, "password field", published = "2026-01-02", folded = 2, dialogs = 1,
        )
        val r = t.execute(call("web_read_page", """{"url":"https://news.example.com/a","unfold":true}""")).content
        assertEquals("true", r.s("egressBlocked"))
        assertFalse("password field" in r.toString())
        assertFalse("verdict" in r)
        assertEquals(listOf("readUrl https://news.example.com/a unfold"), web.calls.filter { it.startsWith("readUrl") })
        // 現在のラベルはタッチされていません: 新しいナビゲーションはありません
        assertEquals(1, web.calls.count { it.startsWith("navigate") })
        web.quality = web.quality!!.copy(verdict = com.wangzan101.mika.core.cap.web.ReadVerdict.THIN)
        val thin = t.execute(call("web_read_page", """{"url":"https://news.example.com/b"}""")).content
        assertEquals("browser_or_webview", thin.s("reason"))
        assertFalse(t.execute(call("web_read_page")).content.containsKey("text"))
        web.readUrlError = "net::ERR_NAME_NOT_RESOLVED"
        assertEquals("page_not_loaded", t.execute(call("web_read_page", """{"url":"https://nowhere.example"}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_read_page", """{"url":"ftp://x.example"}""")).content.s("error"))
    }

    @Test fun evalScriptCappedAt4000() = runTest {
        val web = FakeWebPort(); val t = d(web = web); t.go()
        val ok = t.execute(call("web_evaluate_js", """{"script":"${"x".repeat(4000)}"}""")).content
        assertEquals("true", ok.s("egressBlocked"))
        assertFalse("42" in ok.toString())
        assertEquals("invalid_arguments", t.execute(call("web_evaluate_js", """{"script":"${"x".repeat(4001)}"}""")).content.s("error"))
        web.eval = EvalJs.Failed("devtools_unavailable", "no devtools")
        assertEquals("devtools_unavailable", t.execute(call("web_evaluate_js", """{"script":"document.title"}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_evaluate_js", """{"script":"await import('x')"}""")).content.s("error"))
        web.eval = EvalJs.Ok("1", false, fresh = true)
        assertEquals("browser_or_webview", t.execute(call("web_evaluate_js", """{"script":"1","timeoutMs":3000}""")).content.s("reason"))
        assertEquals(1, web.calls.count { it.startsWith("eval 4000") })
    }

    @Test fun siteCommandsAreOfferedOnlyForDeclaredSitesAndRunWithCheckedArgs() = runTest {
        val catalog = com.wangzan101.mika.core.knowledge.testCatalog(*com.wangzan101.mika.core.knowledge.SiteCommandsTest.files)
        val web = FakeWebPort().apply { tabList = listOf(com.wangzan101.mika.core.cap.web.TabInfo("t1", "https://www.shop.test/list", "list", true)) }
        val t = toolDispatcher(FakeScreen(), web = web, skills = catalog)
        assertTrue(t.definitions().any { it.name == "web_run_site_command" })
        web.eval = EvalJs.Ok("""[{"title":"a"}]""", false)
        val r = t.execute(call("web_run_site_command", """{"name":"list_items","args":{"query":"a","limit":2}}""")).content
        assertEquals(r.toString(), "true", r.s("ok"))
        assertEquals("""{"query":"a","limit":2}""", web.evalArgs.toString())
        assertTrue(web.calls.last().startsWith("eval "))
        assertEquals("unknown_command", t.execute(call("web_run_site_command", """{"name":"nope"}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_run_site_command", """{"name":"list_items","args":{"limit":2}}""")).content.s("error"))
        web.tabList = listOf(com.wangzan101.mika.core.cap.web.TabInfo("t1", "https://other.test/", "x", true))
        assertEquals("unknown_command", t.execute(call("web_run_site_command", """{"name":"list_items","args":{"query":"a"}}""")).content.s("error"))
    }

    @Test fun tabsActionValidation() = runTest {
        val web = FakeWebPort(); val t = d(web = web)
        assertEquals("invalid_arguments", t.execute(call("web_tabs", """{"action":"open"}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_tabs", """{"action":"switch"}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_tabs", """{"action":"close"}""")).content.s("error"))
        assertEquals(2, t.execute(call("web_tabs", """{"action":"list"}""")).content["tabs"]!!.jsonArray.size)
        assertEquals("tab_not_found", t.execute(call("web_tabs", """{"action":"switch","tabId":"t9"}""")).content.s("error"))
        assertEquals("true", t.execute(call("web_tabs", """{"action":"switch","tabId":"t2"}""")).content.s("ok"))
        assertEquals(WEB, t.surface)
        assertEquals("t2", t.execute(call("web_tabs", """{"action":"close","tabId":"t2"}""")).content.s("closed"))
        assertEquals("tab_not_found", t.execute(call("web_tabs", """{"action":"close","tabId":"t9"}""")).content.s("error"))
    }

    @Test fun uploadPathOutsideWorkspaceRejected() = runTest {
        val web = FakeWebPort()
        val t = d(web = web, ws = { if (".." in it) WorkspacePathCheck.Outside else WorkspacePathCheck.Ok })
        t.go(); val sid = t.execute(call("web_observe")).content.s("snapshotId")
        for (p in listOf("/sdcard/a.jpg", "/workspace/../etc/passwd", "workspace/a.jpg")) {
            val r = t.execute(call("web_upload", """{"ref":"@e2","snapshotId":"$sid","paths":["/workspace/ok.jpg","$p"]}""")).content
            assertEquals(p, "path_outside_workspace", r.s("error"))
        }
        assertEquals("invalid_arguments", t.execute(call("web_upload", """{"ref":"@e2","snapshotId":"$sid","paths":[]}""")).content.s("error"))
        val eleven = (1..11).joinToString(",") { "\"/workspace/$it\"" }
        assertEquals("invalid_arguments", t.execute(call("web_upload", """{"ref":"@e2","snapshotId":"$sid","paths":[$eleven]}""")).content.s("error"))
        assertTrue(web.calls.none { it.startsWith("upload") })
        assertEquals("true", t.execute(call("web_upload", """{"ref":"@e2","snapshotId":"$sid","paths":["/workspace/ok.jpg"]}""")).content.s("ok"))
        assertEquals("upload @e2 /workspace/ok.jpg", web.calls.last())
    }

    @Test fun uploadMissingFileRejected() = runTest {
        val web = FakeWebPort()
        val t = d(web = web, ws = { if (it.endsWith("gone.pdf")) WorkspacePathCheck.NotFound else WorkspacePathCheck.Ok })
        t.go(); val sid = t.execute(call("web_observe")).content.s("snapshotId")
        val r = t.execute(call("web_upload", """{"ref":"@e2","snapshotId":"$sid","paths":["/workspace/gone.pdf"]}""")).content
        assertEquals("file_not_found", r.s("error"))
        assertTrue(web.calls.none { it.startsWith("upload") })
    }

    @Test fun webPressKeyLimitedAndDownloadsListed() = runTest {
        val web = FakeWebPort(); val t = d(web = web); t.go()
        assertEquals("true", t.execute(call("web_press_key", """{"key":"KEYCODE_TAB"}""")).content.s("ok"))
        assertEquals("invalid_arguments", t.execute(call("web_press_key", """{"key":"KEYCODE_MENU"}""")).content.s("error"))
        val dl = t.execute(call("web_downloads")).content["downloads"]!!.jsonArray.single().jsonObject
        assertEquals("/workspace/downloads/a.pdf", dl.s("file")); assertEquals("done", dl.s("status"))
        assertNull(dl["error"])
    }

    private fun reply(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private fun FakeModel.result(i: Int): JsonObject =
        Json.parseToJsonElement(requests[i].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content).jsonObject

    @Test fun irreversibleDetectorSeesWebElements() = runTest {
        val web = FakeWebPort().apply {
            elements = listOf(UiElement("@e1", "button", "提交订单", 900, 2300, listOf(700, 940, 990, 990), null, true, false, false))
        }
        val m = FakeModel(
            reply(call("web_navigate", """{"url":"https://shop.example.com/cart"}""")),
            reply(call("web_observe")),
            reply(call("web_tap", """{"ref":"@e1","snapshotId":"wt1-1"}""")),
            reply(call("end_not_done", """{"message":"没下单"}""")),
        )
        AgentLoop(m, d(web = web), "BASE", confirmations = ConfirmationGate()).run("下单")
        val r = m.result(3)
        assertEquals("confirmation_required", r.s("error")); assertEquals("order", r.s("action"))
        assertTrue(web.calls.none { it.startsWith("tapRef") })
    }

    @Test fun browserWritingToolsPassGate() = runTest {
        val gated = mutableListOf<Int>()
        var step = 0
        val sid = "wt1-1"
        val calls = listOf(
            call("web_navigate", """{"url":"https://shop.example.com/cart"}"""),
            call("web_observe"),
            call("web_read_page"),
            call("web_evaluate_js", """{"script":"1"}"""),
            call("web_downloads"),
            call("web_tabs", """{"action":"list"}"""),
            call("web_upload", """{"ref":"@e2","snapshotId":"$sid","paths":["/workspace/a.jpg"]}"""),
        )
        val m = FakeModel(*(calls.map { reply(it) } + reply(call("end_not_done", """{"message":"停"}"""))).toTypedArray())
        val loop = AgentLoop(m, d(), "BASE", gate = { gated += step }, confirmations = ConfirmationGate(),
            sink = object : com.wangzan101.mika.core.record.RunSink {
                override fun event(e: com.wangzan101.mika.core.record.RunEvent) {
                    if (e is com.wangzan101.mika.core.record.RunEvent.ToolResult) step++
                }
            })
        loop.run("t")
        // ステップ 0 でナビゲート、ステップ 5 でタブ、ステップ 6 でアップロードしてレベルに合格します。ページの読み取り、スクリプトの実行、リストのダウンロードはできません。
        assertEquals(listOf(0, 5, 6), gated)
        for (name in listOf("web_navigate", "web_tabs", "web_upload")) assertTrue(name, isWritingTool(name, JsonObject(emptyMap())))
        for (name in listOf("web_read_page", "web_evaluate_js", "web_downloads")) assertFalse(name, isWritingTool(name, JsonObject(emptyMap())))
    }

    private suspend fun ToolDispatcher.webSid(): String { go(); return execute(call("web_observe")).content.s("snapshotId") }
    private fun upload(sid: String, ref: String, vararg paths: String) =
        call("web_upload", """{"ref":"$ref","snapshotId":"$sid","paths":[${paths.joinToString(",") { "\"$it\"" }}]}""")

    @Test fun uploadNeedsFileControl() = runTest {
        val web = FakeWebPort(); val t = d(web = web); val sid = t.webSid()
        val r = t.execute(upload(sid, "@e1", "/workspace/a.jpg")).content
        assertEquals("invalid_arguments", r.s("error")); assertTrue(r.s("message"), "file control" in r.s("message"))
        assertEquals("true", t.execute(upload(sid, "@e2", "/workspace/a.jpg")).content.s("ok"))
    }

    @Test fun workspaceCheckFailsClosedAndRejectsDotSegments() = runTest {
        val web = FakeWebPort()
        val closed = d(web = web, ws = null); val sid = closed.webSid()
        assertEquals("path_outside_workspace", closed.execute(upload(sid, "@e2", "/workspace/a.jpg")).content.s("error"))
        val t = d(web = web); val sid2 = t.webSid()
        for (p in listOf("/workspace/./a.jpg", "/workspace//a.jpg", "/workspace/inbox/../a.jpg", "/workspace/a/..", "/workspace/")) {
            assertEquals(p, "path_outside_workspace", t.execute(upload(sid2, "@e2", p)).content.s("error"))
        }
        assertTrue(web.calls.none { it.startsWith("upload") })
    }

    @Test fun confirmationAppIsSiteHostOnWeb() = runTest {
        val t = d(); t.go()
        fun ask(app: String) = call("request_confirmation", """{"action":"submit","target":"表单","content":"提交","amount":"¥25","app":"$app"}""")
        val ok = t.execute(ask("shop.example.com"))
        val o = ok.outcome as Outcome.Confirm
        assertEquals("shop.example.com", o.request.app); assertEquals("shop.example.com", o.foregroundApp)
        assertEquals("shop.example.com", (t.execute(ask("www.Shop.Example.com")).outcome as Outcome.Confirm).request.app)
        assertEquals("xn--fsqu00a.xn--fiqs8s", (t.execute(ask("例子.中国")).outcome as Outcome.Confirm).request.app)
        for (bad in listOf("https://shop.example.com", "shop.example.com/x", "localhost", "a b.com")) {
            assertEquals(bad, "invalid_arguments", t.execute(ask(bad)).content.s("error"))
        }
        // では、携帯電話では依然としてパッケージ名が必要です
        val phone = d()
        assertEquals("invalid_arguments", phone.execute(ask("1bad.pkg")).content.s("error"))
        assertEquals("com.x", (phone.execute(ask("com.x")).outcome as Outcome.Confirm).request.app)
    }

    @Test fun checkScreenOnWebHasCurrentSite() = runTest {
        val t = d(); t.go()
        val r = t.execute(call("web_diagnose")).content
        assertEquals("shop.example.com", r.s("currentSite")); assertFalse("currentApp" in r)
        assertEquals("com.x", d().execute(call("phone_diagnose")).content.s("currentApp"))
    }

    @Test fun tabSwitchAndCloseDropCoordinateSnapshot() = runTest {
        val web = FakeWebPort(); val t = d(web = web); t.webSid()
        t.execute(call("web_tabs", """{"action":"switch","tabId":"t2"}"""))
        t.execute(call("web_tap", """{"x":500,"y":500,"target":"中间"}"""))
        assertEquals("タブを切り替えた後は座標でクリックする前に画面を再読み取りします", 2, web.reads)
        t.execute(call("web_tabs", """{"action":"close","tabId":"t1"}"""))
        t.execute(call("web_tap", """{"x":500,"y":500,"target":"中间"}"""))
        assertEquals(3, web.reads)
    }

    @Test fun evidenceOnWebAddsPageTextWithoutScrolling() = runTest {
        val web = FakeWebPort(); val t = d(web = web)
        assertFalse("Webページ本文" in t.evidence().freshText)
        t.go()
        val ev = t.evidence()
        assertFalse(ev.freshText, "正文" in ev.freshText)
        assertTrue(ev.freshText, "egress_blocked" in ev.freshText)
        assertTrue(web.calls.none { it.startsWith("readPage") })
    }

    /** ツールの結果には、画面テキスト以外の事実 (Web ページのタイトル、URL、HTTP ステータス、開いているアプリと名前) が証拠として含まれます。作業モデルのヒントは含まれていません。 */
    @Test fun toolResultFactsReachTheEvidence() = runTest {
        val web = FakeWebPort().apply { title = "Example Domain"; url = "https://example.com/" }
        val phone = FakeScreen()
        val t = d(phone = phone, web = web)
        t.execute(call("web_navigate", """{"url":"https://example.com/"}"""))
        val apps = t.execute(call("phone_list_apps")).content["apps"]!!.jsonArray
        val pkg = apps.first().jsonObject.s("package")
        val label = apps.first().jsonObject.s("label")
        t.execute(call("phone_launch_app", """{"package":"$pkg"}"""))
        val ev = t.evidence()
        assertTrue(ev.read(ObsKind.Fact).toString(), ev.read(ObsKind.Fact).any { "Webページタイトル「Example Domain」" in it && "https://example.com/" in it && "HTTPステータス 200" in it })
        assertTrue(ev.read(ObsKind.Fact).toString(), ev.read(ObsKind.Fact).any { "phone_launch_app が $pkg を開きました" in it && label in it })
        assertTrue(ev.sources(ev.ledger.all).any { "Example Domain" in it })
    }

    // モデルで表示されるすべての項目は、検証者によって比較できなければなりません。読み取り可能な要素のない Web ページの場合、読み取り画面の URL とタイトルは依然としてマテリアルです。閲覧中の Web ページにあるリンクや画像の説明も同様です。
    @Test fun everyFieldOfAPageReadReachesTheEvidence() = runTest {
        val web = FakeWebPort().apply { title = "只有标题的页"; elements = emptyList() }
        web.page = web.page.copy(text = "", links = listOf("查看说明" to "https://shop.example.com/help"), images = listOf("商品主图" to "https://img.example.com/1.jpg"))
        val t = d(web = web)
        t.go()
        t.execute(call("web_observe"))
        t.execute(call("web_read_page"))
        val ev = t.evidence()
        assertFalse(ev.freshText, "只有标题的页" in ev.freshText)
        assertFalse(ev.read(ObsKind.Read, ObsKind.Reply).toString(), ev.read(ObsKind.Read, ObsKind.Reply).any { "只有标题的页" in it })
        val seen = ev.sources(ev.ledger.all).joinToString("\n")
        assertFalse(seen, "查看说明" in seen)
        assertFalse(seen, "商品主图" in seen)
        assertTrue(seen, "egress_blocked" in seen || "egress_blocked" in ev.freshText)
    }

    @Test fun webScreenTextCarriesThePageTitle() = runTest {
        val web = FakeWebPort().apply { title = "Example Domain" }
        val t = d(web = web)
        t.go()
        assertFalse("Example Domain" in t.evidence().freshText)
        assertTrue("egress_blocked" in t.evidence().freshText)
    }

    @Test fun uploadGoesThroughIrreversibleCheck() = runTest {
        val web = FakeWebPort().apply {
            elements = listOf(UiElement("@e1", "file", "提交订单", 900, 2300, listOf(700, 940, 990, 990), null, true, false, false))
        }
        val m = FakeModel(
            reply(call("web_navigate", """{"url":"https://shop.example.com/cart"}""")),
            reply(call("web_observe")),
            reply(upload("wt1-1", "@e1", "/workspace/a.jpg")),
            reply(call("end_not_done", """{"message":"停"}""")),
        )
        AgentLoop(m, d(web = web), "BASE", confirmations = ConfirmationGate()).run("传")
        assertEquals("confirmation_required", m.result(3).s("error"))
        assertTrue(web.calls.none { it.startsWith("upload") })
    }

    /** Web サイトを開き、タブを変更してアクション リストを入力します (チェッカーは、あなたがどこにいたかを確認できます)。 Web ページ上のアクションに付属する画面は、アプリではなくサイトを報告します。 */
    @Test fun browserActionsAreLogged() = runTest {
        val t = d()
        t.go("https://shop.example.com/cart")
        t.execute(call("web_tabs", """{"action":"list"}"""))
        val actions = t.evidence().actions
        assertTrue(actions.toString(), actions.any { "web_navigate(https://shop.example.com/cart)" in it })
        assertTrue(actions.toString(), actions.any { "web_tabs(list)" in it })
    }

    private fun resp(id: String, body: String? = null, note: String? = null) = CapturedResponse(
        id, "https://api.example.com/items?token=Bearer abcdefghijkl", "GET", "Fetch", 200, "application/json", 40, false,
        CapturedResponse.State.DONE, null, 0, body = body, bodyNote = note,
    )

    @Test fun captureWithoutDevtoolsIsUnavailable() = runTest {
        val t = d()
        t.go()
        assertEquals("devtools_unavailable", t.execute(call("web_capture")).content.s("error"))
        assertEquals("devtools_unavailable", t.execute(call("web_capture", """{"id":"1"}""")).content.s("error"))
        assertEquals("devtools_unavailable", t.execute(call("web_wait_for", """{"condition":"network_idle"}""")).content.s("error"))
        // のテキストとアドレスの条件には DevTools は必要ありません
        assertEquals("true", t.execute(call("web_wait_for", """{"condition":"text_present","value":"已加入"}""")).content.s("met"))
    }

    @Test fun captureListsAndGetsRedactedBody() = runTest {
        val web = FakeWebPort().apply {
            devtools = true
            captured = listOf(resp("1", note = "not_finished"), resp("2", body = "{\"key\":\"sk-abcdefghijklmnopqrstuv\",\"n\":${"1".repeat(30)}}"))
        }
        val t = d(web = web)
        t.go()
        val list = t.execute(call("web_capture", """{"urlContains":"items","method":"GET","sinceLastAction":true}""")).content
        assertEquals(2, list["responses"]!!.jsonArray.size)
        assertFalse("body" in list["responses"]!!.jsonArray[0].jsonObject)
        assertFalse(list.toString(), list.toString().contains("abcdefghijkl"))
        assertTrue(web.calls.contains("captures items GET true"))
        val one = t.execute(call("web_capture", """{"id":"2","maxChars":20}""")).content
        assertEquals("true", one.s("egressBlocked"))
        assertFalse("body" in one)
        assertFalse("sk-" in one.toString())
        assertEquals("browser_or_webview", t.execute(call("web_capture", """{"id":"1"}""")).content.s("reason"))
        assertEquals("capture_not_found", t.execute(call("web_capture", """{"id":"9"}""")).content.s("error"))
    }

    @Test fun waitValidatesAndReportsOutcome() = runTest {
        val web = FakeWebPort().apply { devtools = true; waitOutcome = WaitOutcome(false, 3000, "https://shop.example.com/done") }
        val t = d(web = web)
        t.go()
        assertEquals("invalid_arguments", t.execute(call("web_wait_for", """{"condition":"text_present"}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_wait_for", """{"condition":"network_idle","seconds":20}""")).content.s("error"))
        assertEquals("invalid_arguments", t.execute(call("web_wait_for", """{"condition":"element"}""")).content.s("error"))
        val r = t.execute(call("web_wait_for", """{"condition":"url_contains","value":"done","seconds":3}""")).content
        assertEquals("false", r.s("met")); assertEquals("3000", r.s("waitedMs"))
        assertTrue(web.calls.contains("wait url_contains done 3000"))
    }

    @Test fun unsettledActionIsNoted() = runTest {
        val web = FakeWebPort().apply { result = ActionResult.Ok(changed = true, settled = false) }
        val t = d(web = web)
        t.go()
        val sid = t.execute(call("web_observe")).content.s("snapshotId")
        val r = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).content
        assertEquals("false", r.s("settled"))
        assertTrue(r.s("hint"), "まだ読み込み中または変化中" in r.s("hint") && "web_wait_for" in r.s("hint"))
        web.result = ActionResult.Ok(changed = true, settled = true)
        assertFalse("settled" in t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).content)
    }

    /** 資格情報フィールド、JWT、および資格情報を含むクエリ パラメーター: モデルに送信された結果や検証証拠には表示されません。 */
    @Test fun captureSecretsNeverLeaveTheLedger() = runTest {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val web = FakeWebPort().apply {
            devtools = true
            captured = listOf(resp("1", body = """{"access_token":"tok-plain-1","user":{"password":"hunter2","name":"Ann"},"x":"$jwt"}""")
                .copy(url = "https://api.example.com/me?session_id=s-plain-2&page=1"))
        }
        val t = d(web = web)
        t.go()
        val out = t.execute(call("web_capture", """{"id":"1"}""")).content.toString() +
            t.execute(call("web_capture")).content.toString() + t.evidence().read(ObsKind.Read, ObsKind.Reply).joinToString()
        for (secret in listOf("tok-plain-1", "hunter2", jwt, "s-plain-2", "Ann")) assertFalse(secret, out.contains(secret))
        assertTrue(out, out.contains("egressBlocked"))
    }

    @Test fun captureFlagsRiskControlResponse() = runTest {
        val web = FakeWebPort().apply { devtools = true; captured = listOf(resp("1", body = """{"code":37,"message":"您的访问行为异常"}""")) }
        val t = d(web = web)
        t.go()
        val r = t.execute(call("web_capture", """{"id":"1"}""")).content
        assertEquals("browser_or_webview", r.s("reason"))
        assertFalse("您的访问行为异常" in r.toString())
        web.captured = listOf(resp("2", body = """{"code":0,"message":"Success"}"""))
        assertFalse("Success" in t.execute(call("web_capture", """{"id":"2"}""")).content.toString())
    }

    @Test fun pageDwellBeforeChangingPageOnSameSite() = runTest {
        val web = FakeWebPort()
        val t = toolDispatcher(FakeScreen(), web = web, workspacePath = { WorkspacePathCheck.Ok },
            webPacing = { PagePacing.human(kotlin.random.Random(1)) })
        // 1ページ目、2ページ目の開きを待たない(1ページ目は滞留しない)
        assertEquals(0, t.execute(call("web_navigate", """{"url":"https://shop.example.com/list"}""")).waitMs)
        assertEquals(0, t.execute(call("web_navigate", """{"url":"https://shop.example.com/list?page=2"}""")).waitMs)
        val sid = t.execute(call("web_observe")).content.s("snapshotId")
        // 2 番目のページが十分に表示されない場合は、次をクリックしてください: 最初に停留所を作成し、この通話の待ち時間として記録します。
        val tap = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}"""))
        assertTrue("${tap.waitMs}", tap.waitMs in 1..PagePacing.Limits().firstDwellMs.last)
        // 十分に停止しました: 同じページ上の次のアクションは決して待機しません。タイピングは待ち時間なし
        assertEquals(0, t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).waitMs)
        // 他のウェブサイトは異なります
        assertEquals(0, t.execute(call("web_navigate", """{"url":"https://news.example.org/"}""")).waitMs)
    }

    @Test fun pageLimitStopsPageChangesUntilTheUserAnswers() = runTest {
        val web = FakeWebPort()
        val t = toolDispatcher(FakeScreen(), web = web, workspacePath = { WorkspacePathCheck.Ok },
            webPacing = { PagePacing.human(kotlin.random.Random(1), limits = PagePacing.Limits(pageCap = 2)) })
        assertEquals(JsonPrimitive(true), t.go("https://shop.example.com/a")["ok"])
        assertEquals(JsonPrimitive(true), t.go("https://shop.example.com/b")["ok"])
        // この Web サイトには、このタスクに関する 2 ページがすでにあります。ページを開いてクリックし、停止した理由を説明します。
        val nav = t.go("https://shop.example.com/c")
        assertEquals("page_limit", nav.s("error"))
        assertTrue(nav.s("message"), nav.s("message").contains("ask_user") && nav.s("message").contains("shop.example.com"))
        assertFalse(web.calls.any { it.startsWith("navigate https://shop.example.com/c") })
        val sid = t.execute(call("web_observe")).content.s("snapshotId")
        assertEquals("page_limit", t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).content.s("error"))
        assertFalse(web.calls.any { it.startsWith("tapRef") })
        // このページを読んで、通常どおり他の Web サイトにアクセスしてください
        assertEquals(JsonPrimitive(true), t.go("https://news.example.org/")["ok"])
        // ユーザーが返信しました: 続きを読んでください
        t.noteUserAnswer("还要接着看吗?", "继续")
        assertEquals(JsonPrimitive(true), t.go("https://shop.example.com/c")["ok"])
    }

    // ページの最大数は閲覧に制限されています。この Web サイトで外部アクション (「即時通信」) を実行した後、バッチで実行し続けても、そのアクションによって中断されることはなくなり、滞在時間も変わりません。他のウェブサイトは通常通り継続します。
    @Test fun pageLimitIsLiftedOnASiteAfterAnOutwardAction() = runTest {
        val web = FakeWebPort().apply {
            elements = listOf(
                UiElement("@e1", "button", "立即沟通", 900, 300, listOf(800, 100, 999, 160), null, true, false, false),
                UiElement("@e2", "link", "下一个职位", 540, 1300, listOf(0, 1200, 999, 1400), null, true, false, false),
            )
        }
        val t = toolDispatcher(FakeScreen(), web = web, workspacePath = { WorkspacePathCheck.Ok },
            webPacing = { PagePacing.human(kotlin.random.Random(1), limits = PagePacing.Limits(pageCap = 2)) })
        val sink = object : RunSink { override fun event(e: RunEvent) {} }
        val executor = ActionExecutor(t, OperationGuards(), null, gate = {}, recorder = ActionRecorder(sink), autonomous = true)
        suspend fun run(name: String, args: String) = executor.run(Part.ToolCall("c", name, args), Origin.Model(1)).execution()
        t.go("https://jobs.example.com/a")
        t.go("https://jobs.example.com/b")
        var sid = t.execute(call("web_observe")).content.s("snapshotId")
        // 閲覧のみ：上限に達しても何もしない
        assertEquals("page_limit", run("web_tap", """{"ref":"@e2","snapshotId":"$sid"}""").content.s("error"))
        // は外部アクションをブロックしません
        assertEquals(JsonPrimitive(true), run("web_tap", """{"ref":"@e1","snapshotId":"$sid"}""").content["ok"])
        assertTrue(web.calls.any { it.startsWith("tapRef @e1") })
        // その後、この Web サイト上のページの変更は通常どおり機能し、変更されたページはそのまま残ります。
        assertEquals(JsonPrimitive(true), run("web_navigate", """{"url":"https://jobs.example.com/c"}""").content["ok"])
        sid = t.execute(call("web_observe")).content.s("snapshotId")
        val next = run("web_tap", """{"ref":"@e2","snapshotId":"$sid"}""")
        assertEquals(JsonPrimitive(true), next.content["ok"])
        assertTrue("${next.waitMs}", next.waitMs > 0)
        // ユーザーは回答したため、今後は設定しません
        t.noteUserAnswer("接着吗?", "继续")
        for (i in 1..3) assertEquals(JsonPrimitive(true), run("web_navigate", """{"url":"https://jobs.example.com/d$i"}""").content["ok"])
        // 他の Web サイトでも同様のことが行われます。
        t.go("https://shop.example.org/1")
        t.go("https://shop.example.org/2")
        assertEquals("page_limit", t.go("https://shop.example.org/3").s("error"))
    }

    @Test fun carefulSitesDwellLongerAndIdleScrollsAreRecorded() = runTest {
        val web = FakeWebPort()
        val t = toolDispatcher(FakeScreen(), web = web, workspacePath = { WorkspacePathCheck.Ok },
            webPacing = { PagePacing.human(kotlin.random.Random(1)) },
            webPlacePace = { if (it == "jobs.example.com") PlacePace.CAREFUL else PlacePace.NORMAL },
            webIdleScroll = { IdleScroll(kotlin.random.Random(2)) })
        t.go("https://jobs.example.com/list")
        t.go("https://jobs.example.com/list?page=2")
        val sid = t.execute(call("web_observe")).content.s("snapshotId")
        val tap = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}"""))
        assertTrue("${tap.waitMs}", tap.waitMs in PagePacing.Limits.CAREFUL.firstDwellMs)
        // は滞在中をゆっくりとスクロールしました。モデルではなく、ランニング記録のみを入力してください
        val idle = web.calls.count { it.startsWith("idle ") }
        assertTrue("$idle", idle in 1..3)
        assertEquals(JsonPrimitive(idle), tap.record["idleScrolls"])
        assertFalse("idleScrolls" in tap.content)
    }

    @Test fun coordinateTapsDoNotIdleScroll() = runTest {
        val web = FakeWebPort()
        val t = toolDispatcher(FakeScreen(), web = web, workspacePath = { WorkspacePathCheck.Ok },
            webPacing = { PagePacing.human(kotlin.random.Random(1)) }, webIdleScroll = { IdleScroll(kotlin.random.Random(2)) })
        t.go("https://shop.example.com/list")
        t.go("https://shop.example.com/list?page=2")
        t.execute(call("web_observe"))
        val tap = t.execute(call("web_tap", """{"x":500,"y":500}"""))
        assertTrue("${tap.waitMs}", tap.waitMs > 0)
        assertTrue(web.calls.none { it.startsWith("idle ") })
    }

    @Test fun idleScrollIsNotCountedAsTheActionsEffect() = runTest {
        val web = FakeWebPort()
        val t = toolDispatcher(FakeScreen(), web = web, workspacePath = { WorkspacePathCheck.Ok },
            webPacing = { PagePacing.human(kotlin.random.Random(1)) }, webIdleScroll = { IdleScroll(kotlin.random.Random(2)) })
        t.go("https://shop.example.com/list")
        t.go("https://shop.example.com/list?page=2")
        val sid = t.execute(call("web_observe")).content.s("snapshotId")
        // 要素を上に移動するには、滞在中にスクロールします。クリック自体には何も変化しません (ポートは何も変化を報告しません)
        web.onIdle = { web.elements = web.elements.map { it.copy(centerY = it.centerY - 40, bounds = listOf(it.bounds[0], it.bounds[1] - 40, it.bounds[2], it.bounds[3] - 40)) } }
        web.result = com.wangzan101.mika.core.uistate.ActionResult.Ok()
        val tap = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}"""))
        assertTrue(web.calls.any { it.startsWith("idle ") })
        // は、スクロール後のアクション前の外観を再確認しました。何も変更せずに真実を伝える効果はありません。モデルの手のスクリーンの位置は正確ではなくなり、古いものとしてマークされます
        assertEquals(tap.content.toString(), "no_effect", tap.content["effect"]!!.jsonObject.s("kind"))
        assertEquals(JsonPrimitive(true), tap.content["interfaceStale"])
    }
}
