package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScriptProtocolTest {
    private fun details(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun parsesCalls() {
        val c = PageScriptProtocol.parseCall("""{"id":3,"op":"tap","args":{"ref":"@e2"}}""")!!
        assertEquals(3, c.id); assertEquals("tap", c.op); assertEquals("\"@e2\"", c.args["ref"].toString())
        // には引数がなく、空のオブジェクトです。ログのIDは0です
        assertEquals(JsonObject(emptyMap()), PageScriptProtocol.parseCall("""{"id":0,"op":"log"}""")!!.args)
    }

    @Test fun rejectsMalformedCalls() {
        for (bad in listOf("", "nope", "[]", """{"op":"tap"}""", """{"id":"1","op":"tap"}""", """{"id":-1,"op":"tap"}""", """{"id":1,"op":5}""")) {
            assertNull(bad, PageScriptProtocol.parseCall(bad))
        }
    }

    @Test fun settleEmbedsResultAsLiteral() {
        val r = buildJsonObject { put("ok", true); put("value", "a\"b</script>\n") }
        val e = PageScriptProtocol.settle(7, r)
        assertTrue(e.startsWith("globalThis.__mikaSettle && globalThis.__mikaSettle(7, {"))
        // JSON エスケープにより、文字列が式を切り捨てないことが保証されます。
        assertTrue("\\\"b" in e && "\\n" in e)
    }

    @Test fun runPassesCodeAsDataNotSource() {
        // モデル コードは、式にスペルを含めずに、コンパイル用の文字列として事前保存された AsyncFunction に渡されます。
        assertEquals("globalThis.__mikaRun(\"return '1\\\"'\")", PageScriptProtocol.run("return '1\"'"))
    }

    @Test fun errorLineFromStack() {
        // コンパイルされたソース コード: 関数ヘッダー、") {"、'use strict';、内側のアロー関数ごとに 1 行、モデル コードは 5 行目から始まります
        val (msg, line) = PageScriptProtocol.error(details("""{"text":"Uncaught (in promise)","lineNumber":0,
            "exception":{"description":"TypeError: x\n    at eval (mika-script.js:7:9)\n    at mika-prelude.js:80:1"}}"""))
        assertEquals("TypeError: x", msg); assertEquals(3, line)
    }

    @Test fun errorWithoutScriptFrameHasNoLine() {
        val (msg, line) = PageScriptProtocol.error(details("""{"text":"Uncaught","lineNumber":2,
            "exception":{"description":"SyntaxError: Unexpected token '}'\n    at mika-prelude.js:80:16"}}"""))
        assertEquals("SyntaxError: Unexpected token '}'", msg); assertNull(line)
        val (m2, l2) = PageScriptProtocol.error(details("""{"text":"Uncaught 5","lineNumber":0}"""))
        assertEquals("Uncaught 5", m2); assertNull(l2)
    }

    @Test fun onlyPlainJavaScriptGlobalsAreAllowed() {
        // ECMAScript 自体のみがリリースされています。どれもブラウザに対してグローバルではなく、文字列をコードとして実行できるものはありません。
        val allowed = PageScriptProtocol.ALLOWED_GLOBALS
        for (g in listOf("document", "window", "self", "globalThis", "location", "fetch", "XMLHttpRequest", "setTimeout", "navigator", "history",
            "localStorage", "frames", "top", "parent", "eval", "Function", "console", "HTMLElement", "Element", "Node", "Event", "CSSStyleSheet",
            "Image", "Option", "WebSocket", "postMessage", "open", "structuredClone", "queueMicrotask", "WebAssembly")) {
            assertFalse(g, g in allowed)
        }
        for (g in listOf("JSON", "Math", "Array", "Object", "Promise", "Map", "Date", "RegExp", "Number", "String")) assertTrue(g, g in allowed)
    }

    @Test fun preludeShadowsEveryGlobalAndClosesStringEval() {
        val p = PageScriptProtocol.PRELUDE
        // は、コンストラクターを閉じる前に AsyncFunction を保存します。マスクされた名前は、グローバル オブジェクトとそのプロトタイプ チェーン全体から取得されます。
        assertTrue(p.indexOf("const AsyncFunction =") < p.indexOf("'constructor', unavailable"))
        assertTrue("for (let o = G; o; o = O.getPrototypeOf(o)) for (const k of O.getOwnPropertyNames(o))" in p)
        for (k in listOf("fixed(G, 'eval', unavailable)", "fixed(G, 'Function', unavailable)", "fixed(G.Error, 'prepareStackTrace', undefined)")) assertTrue(k, k in p)
        // は厳密モードで、これは未定義で、カバーされる名前はすべて未定義で渡され、最後のパラメーターは mika です。戻り値は JSON 文字列のみです。
        assertTrue("new AsyncFunction(...shadow, 'mika', \"'use strict';\\nreturn (async () => {\\n\" + code" in p)
        assertTrue("apply(fn, undefined, [...blanks, mika])" in p)
        assertTrue("stringify(r)" in p)
        // 事前スクリプトは DOM 自体には影響しません。ページ オブジェクトは DOM を通じてモデル コードに渡されません。
        for (dom in listOf("document.", "querySelector", "innerText", "HTMLElement")) assertFalse(dom, dom in p)
        assertTrue(p.trimEnd().endsWith("//# sourceURL=${PageScriptProtocol.PRELUDE_URL}"))
    }

    @Test fun preludeSendsCallsThroughTheOutbox() {
        val p = PageScriptProtocol.PRELUDE
        // は DevTools バインディングに依存しません (その場合、ランタイムを開く必要があります)。呼び出しは送信ボックスに入れられ、__mikaTake によって引き渡され、名前もモデル コード内で隠されます。
        assertTrue("fixed(G, '__mikaTake'" in p)
        assertTrue(p.indexOf("fixed(G, '__mikaTake'") < p.indexOf("const shadow"))
        assertTrue(PageScriptProtocol.TAKE.startsWith("globalThis.__mikaTake("))
        for (op in listOf("snapshot", "read", "query", "queryAll", "text", "tap", "type", "scroll", "navigate", "waitText")) assertTrue(op, "call('$op'" in p)
    }

    @Test fun dynamicImportIsRejected() {
        assertNotNull(PageScriptProtocol.codeProblem("await import('data:text/javascript,1')"))
        assertNotNull(PageScriptProtocol.codeProblem("const m = import ( u )"))
        assertNotNull(PageScriptProtocol.codeProblem("import.meta"))
        assertNull(PageScriptProtocol.codeProblem("const important = 1; return important"))
    }

    @Test fun readerMasksSecretsWithThePageRule() {
        val r = PageScriptProtocol.READER
        // には page.js と同じルールがあります (リストについてはアプリの PageScriptSecretRuleTest を参照してください)。
        assertEquals(listOf("password", "hidden"), PageScriptProtocol.SECRET_INPUT_TYPES)
        for (t in listOf("current-password", "new-password", "one-time-code", "cc-number", "cc-csc")) assertTrue(t, t in PageScriptProtocol.SECRET_AUTOCOMPLETE)
        assertTrue("k === 'value' && secret(el)" in r)
        assertTrue("secret(el) ? (el.value ? MASK : '')" in r)
        assertTrue("const textOf = (el) => (secret(el) ? ''" in r)
        assertTrue("k.startsWith('on')" in r)
        // リーダーは純粋なデータのみを配信します
        assertTrue(PageScriptProtocol.read(buildJsonObject { put("mode", "count") }).startsWith("globalThis.__mikaRead({"))
    }

    @Test fun backstopSeesScriptInitiatedRequests() {
        fun req(stack: String) = details("""{"requestId":"1","request":{"url":"https://x/"},"initiator":{"type":"script","stack":$stack}}""")
        assertTrue(PageScriptProtocol.requestFromScript(req("""{"callFrames":[{"url":"mika-script.js","lineNumber":3}]}""")))
        // も非同期親チェーンに含まれます。
        assertTrue(PageScriptProtocol.requestFromScript(req("""{"callFrames":[],"parent":{"callFrames":[{"url":"mika-prelude.js"}]}}""")))
        // ページ自体によって送信されたスクリプト (mika.tap によってトリガーされるページ処理関数を含む) はカウントされません。
        assertFalse(PageScriptProtocol.requestFromScript(req("""{"callFrames":[{"url":"https://shop.example.com/app.js"}]}""")))
        assertFalse(PageScriptProtocol.requestFromScript(details("""{"requestId":"1","initiator":{"type":"other"}}""")))
    }

    @Test fun backstopStopsNavigationOutsideActions() {
        val nav = details("""{"frameId":"F1","reason":"scriptInitiated","url":"https://evil/"}""")
        assertTrue(PageScriptProtocol.navigationOutsideAction(nav, "F1", inAction = false))
        // mika.tap / mika.navigate これを実行すると、そのアクションの結果としてページジャンプが行われます。
        assertFalse(PageScriptProtocol.navigationOutsideAction(nav, "F1", inAction = true))
        // サブフレームは、
        assertFalse(PageScriptProtocol.navigationOutsideAction(nav, "F2", inAction = false))
    }
}
