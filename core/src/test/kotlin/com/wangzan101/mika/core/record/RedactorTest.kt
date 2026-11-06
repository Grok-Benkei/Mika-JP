package com.wangzan101.mika.core.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.verb

class RedactorTest {
    /** Google の新しい形式の認証キー (AQ. で始まる) は古い AIza と同じで、Redactor を通るすべてのパスがブロックされています。 */
    @Test fun googleAuthKeyMaskedEverywhere() {
        val key = "AQ." + "Ab8RN6Lq_x-Zt3VwP0kJ9sYcH2mE4uFgD7aB"
        fun gone(s: String) { assertFalse(s, s.contains(key)); assertFalse(s, s.contains("Ab8RN6Lq_x-Zt3VwP0kJ9sYcH2mE4uFgD7aB")) }
        assertEquals("key=*** ok", Redactor.text("key=$key ok"))
        gone(Redactor.evidence("Key: $key"))
        gone(Redactor.args(verb("phone_type_text"), """{"text":"$key"}""", secretField = false))
        gone(Redactor.pageData("""{"value":"$key"}"""))
        val n = Redactor.network(url = "https://a.test/p?x=$key", body = """{"k":"$key"}""", headers = mapOf("X-Goog-Api-Key" to key, "X-Note" to key))
        gone(n.url!!); gone(n.body!!); n.headers!!.values.forEach(::gone)
        // は短すぎるため、次で始まりません。
        assertEquals("AQ.short", Redactor.text("AQ.short"))
        assertEquals("XAQ.Ab8RN6Lq_x-Zt3VwP0kJ9sY", Redactor.text("XAQ.Ab8RN6Lq_x-Zt3VwP0kJ9sY"))
    }

    @Test fun passwordFieldTextMasked() {
        val out = Redactor.args(verb("phone_type_text"), """{"text":"hunter2","ref":"@e3","snapshotId":"s1"}""", secretField = true)
        val o = Json.parseToJsonElement(out).jsonObject
        assertEquals("***", o["text"]!!.jsonPrimitive.content)
        assertEquals("@e3", o["ref"]!!.jsonPrimitive.content)
        assertFalse(out.contains("hunter2"))
    }

    @Test fun apiKeyLikeStringsMasked() {
        val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        val t = Redactor.text("key is $key and header Authorization: Bearer abc.DEF-123_xyz done")
        assertFalse(t.contains(key))
        assertFalse(t.contains("abc.DEF-123_xyz"))
        assertTrue(t.contains("***"))
        assertTrue(t.endsWith(" done"))
        val google = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"
        val gh = "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"
        val t2 = Redactor.text("g=$google h=$gh")
        assertFalse(t2.contains(google)); assertFalse(t2.contains(gh))
        // はパスワード ボックスではありませんが、パラメータ内の Key のような文字列もカバーする必要があります。
        val a = Redactor.args(verb("phone_type_text"), """{"text":"$key"}""", secretField = false)
        assertFalse(a.contains(key))
    }

    @Test fun authorizationAndScreenPayloadMarkersDoNotSurviveLogs() {
        val dummy = "sk-dummy" + "NotARealKey0123456789"
        val logged = Redactor.text("Authorization: Bearer $dummy screen=password-pixels png-base64")
        assertFalse(logged.contains(dummy))
        assertFalse(logged.contains("Bearer sk-dummy"))
        assertTrue(logged.contains("***"))
    }

    @Test fun ordinaryTextKept() {
        val s = "打开设置,搜索 sky-blue 主题 task-1234 Bearer"
        assertEquals(s, Redactor.text(s))
        val args = """{"text":"你好","pressEnter":true}"""
        assertEquals("你好", Json.parseToJsonElement(Redactor.args(verb("phone_type_text"), args, secretField = false)).jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("""{"ref":"@e1","snapshotId":"s"}""", Redactor.args(verb("phone_tap"), """{"ref":"@e1","snapshotId":"s"}""", secretField = false))
    }

    private fun scriptCode(code: String): String {
        val args = """{"code":${kotlinx.serialization.json.JsonPrimitive(code)}}"""
        return Json.parseToJsonElement(Redactor.args(verb("web_script"), args, secretField = false)).jsonObject["code"]!!.jsonPrimitive.content
    }

    @Test fun scriptStringLiteralsAllMasked() {
        // 単語がどのように渡されるかは関係ありません: mika.type に直接、事前に保存された変数、スプライシング、DOM への書き込み
        val c = scriptCode("const s = await mika.snapshot();\nawait mika.type(find(s, '密码'), 'hunter2');\nconst pw = \"abc\" + 'def';\n" +
            "document.querySelector('#p').value = pw;\nawait mika.type(s.elements[0], `x${'$'}{pw}y${'$'}{'zz'}`);")
        for (secret in listOf("hunter2", "abc", "def", "密码", "#p", "zz", "`x")) assertFalse(secret, secret in c)
        // 形状のみが残ります: 引用符の種類、テンプレート内の式の位置、およびコード自体
        assertTrue(c, "mika.type(find(s, '***'), '***')" in c)
        assertTrue(c, "\"***\" + '***'" in c)
        assertTrue(c, "`***${'$'}{pw}***${'$'}{'***'}***`" in c)
        assertEquals("const a = '';", scriptCode("const a = '';"))
    }

    @Test fun scriptEscapedQuotesStayInsideLiteral() {
        val c = scriptCode("const a = 'it\\'s hunter2'; const b = \"say \\\"hi\\\"\";")
        assertEquals("const a = '***'; const b = \"***\";", c)
    }
}
