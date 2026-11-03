package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit

class SelectorPolicyTest {
    companion object {
        /**
         * は、value 属性に基づいて要素を選択する方法です (ブラウザーで異なる値を持つ 2 つの入力ボックスに直面した場合、ヒットは異なります)。
         * ReadScriptEnforcementTest 同じバッチを使用してページ内の JS バージョンを実行します。両側が同じである必要があります。
         */
        val BY_VALUE = listOf(
            """input[value^="h"]""",
            """input[ VALUE ]""",
            """input[v\61 lue^="h"]""",
            """input[\76 alue]""",
            """input[v\000061lue^="h"]""",
            """input[\v\61 l\u\65 ^="h"]""",
            """input[*|value^="h"]""",
            """input[|value]""",
            """input[/* x */value^="h"]""",
            """input[value/* x */^="h"]""",
            """input[value^='h' i]""",
            """form:has(input[value^="h"])""",
            """input:is([value^="h"])""",
            // 引用符内のコメント マークはカウントされません。文字通りにコメントするだけの場合、中央の [値はコメントとして扱われます。
            """input:not([data-x="/*"])[value^="h"]:not([data-y="*/"])""",
            // 引用符内のエスケープ後の改行はエスケープに含まれますが、継続行のバックスラッシュと改行は段落を終了しません。
            "input:not([data-x=\"\\41\n\"])[value^=\"h\"]",
            "input:not([data-x=\"\\41\r\n\"])[value^=\"h\"]",
            "input:not([data-x=\"a\\\nb\"])[value^=\"h\"]",
            // 未完成の引用符と角括弧は、最後まで読むとブラウザによって埋められます。
            """input[value^="h""",
            // 引用符のない URL ( まで) は記号であり、その中のコメント マークや引用符はカウントされません。 :is() はこの項目を破棄しますが、次の内容は引き続き有効になります value 属性に従って
            """input:is(url(/*),[value^="h"])""",
            """input:is(url(x"),[value^="h"])""",
            """input:where(u\72 l(/*),[value^="h"])""",
            """input:is(URL( /*),[value^="h"])""",
        )

        /** が選択されていない場合は、通常どおりリリースされます。 */
        val NOT_BY_VALUE = listOf(
            "li.item",
            """input[name="q"]""",
            "input[data-value]",
            "input[value-x]",
            "input[valuex]",
            ".value",
            "#value",
            """input[type="value"]""",
            """[title="[value"]""",
            """[title='[value^=h]']""",
            """[data-x|="value"]""",
            "input:not([type=hidden])",
            """[d\61 ta-id]""",
            """[title="/*"], [data-x="*/"]""",
            """a[data-url="x"]""",
            "a.url",
            // 引用符で囲まれた URL (カウントされません: url( は復元後に見つかります)
            """[style*="url("]""",
        )
    }

    @Test fun valueSelectorsAreCaught() {
        for (s in BY_VALUE) assertTrue(s, SelectorPolicy.selectsByValue(s))
    }

    @Test fun otherSelectorsPass() {
        for (s in NOT_BY_VALUE) assertFalse(s, SelectorPolicy.selectsByValue(s))
    }

    @Test fun readsLikeCss() {
        assertEquals("[value]", SelectorPolicy.plain("""[v\61 lue]"""))
        assertEquals("[value]", SelectorPolicy.plain("""[v\000061lue]"""))
        assertEquals("[value]", SelectorPolicy.plain("""[\v\61 l\u\65 ]"""))
        assertEquals("a b", SelectorPolicy.plain("a/* [value] */b"))
        assertEquals("a ", SelectorPolicy.plain("a/* not closed"))
        assertEquals("""[title=""][x=""]""", SelectorPolicy.plain("""[title="[value\"/*"][x='\27']"""))
        // 改行は常に \n としてカウントされます。エスケープされていない改行は単語を引用符で囲みます。
        assertEquals("[a=\"\"\n[value]", SelectorPolicy.plain("[a=\"x\r\n[value]"))
        // 0. プロキシ領域内および範囲外のコード ポイントは置換文字に置き換えられます。最後の単一のバックスラッシュについても同様です。
        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD", SelectorPolicy.plain("""\0 \D800 \110000 \"""))
    }

    /** ページの JS バージョンと Kotlin バージョンは同じ判断を持ちます。つまり、上記の 2 つのグループに加えて、属性名の前後にランダムに綴られたエラーが発生しやすいフラグメントのバッチです。マシン上にノードがない場合はスキップします。 */
    @Test fun jsVersionAgrees() {
        val node = ReadScriptEnforcementTest.node()
        assumeTrue("no node on this machine", node != null)
        val names = listOf("value", "VaLuE", "v\\61 lue", "\\76 alue", "v\\000061lue", "*|value", "|value", " value", "valu", "valuex", "value-x", "data-value", "val/**/ue", "va\\\nlue")
        val parts = listOf(
            "[", "]", "v", "e", "\\61 ", "\\76", "\\", "\"", "'", "/*", "*/", "*|", "|", " ", "\t", "\n", "\r", "\r\n", "\u000C",
            "x", "=\"h\"", "^", "(", ")", ":is(", "\\\n", "\\41\n", "-", "0", "\u00E9", "url(", "URL(", "u\\72 l(",
        )
        val rnd = Random(7)
        fun noise(n: Int) = buildString { repeat(rnd.nextInt(n)) { append(parts[rnd.nextInt(parts.size)]) } }
        val random = List(3000) { noise(4) + (if (rnd.nextBoolean()) "[" else "") + noise(2) + names[rnd.nextInt(names.size)] + noise(4) }
        val all = BY_VALUE + NOT_BY_VALUE + random
        val dir = kotlin.io.path.createTempDirectory("selector-policy").toFile()
        val input = File(dir, "all.json").apply { writeText(JsonArray(all.map(::JsonPrimitive)).toString()) }
        val script = File(dir, "check.js").apply {
            writeText("'use strict';\n" + SelectorPolicy.JS + "\nconst all = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));\nprocess.stdout.write(JSON.stringify(all.map(selectsByValue)));\n")
        }
        val p = ProcessBuilder(node, script.path, input.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("node timed out", p.waitFor(60, TimeUnit.SECONDS))
        dir.deleteRecursively()
        val js = runCatching { Json.parseToJsonElement(out).jsonArray.map { it.jsonPrimitive.boolean } }.getOrElse { error("node output: $out") }
        assertEquals(all.size, js.size)
        for ((i, s) in all.withIndex()) assertEquals(s, SelectorPolicy.selectsByValue(s), js[i])
        // ランダムなスペルでは両方の結果が多数存在する必要があります。そうでない場合、比較は片側のみになります。
        val hits = random.count { SelectorPolicy.selectsByValue(it) }
        assertTrue("$hits of ${random.size}", hits > 300 && random.size - hits > 300)
    }
}
