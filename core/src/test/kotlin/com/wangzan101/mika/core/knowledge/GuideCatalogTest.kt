package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import com.wangzan101.mika.core.assembly.toolDispatcher

class GuideCatalogTest {
    /** は、クラスパスとして一時ディレクトリに配置されるスクリプト リストを持つスキルです。リストに 1 つのファイルがありません (ビルド期間の検証によってブロックされるため、インデックスはここに直接書き込まれます)。 */
    private val catalog: GuideCatalog by lazy {
        val root = Files.createTempDirectory("skills").toFile()
        File(root, KnowledgeIndex.RESOURCE).apply { parentFile.mkdirs() }.writeText(KnowledgeIndex.encode(listOf(
            IndexEntry(kind = "skill", name = "report", display = "报表", description = "做报表",
                files = listOf("scripts/manifest.json", "scripts/tabulate.py", "scripts/fetch.mjs")),
        )))
        val dir = File(root, "skills/report").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: report\n---\n用 scripts/tabulate.py 汇总。")
        File(dir, "scripts").mkdirs()
        File(dir, "scripts/manifest.json").writeText(
            """[{"file":"scripts/tabulate.py","runnable":true},{"file":"scripts/fetch.mjs","runnable":true,"network":true},{"file":"scripts/missing.py","runnable":true}]""",
        )
        File(dir, "scripts/tabulate.py").writeText("print(1)")
        File(dir, "scripts/fetch.mjs").writeText("1")
        GuideCatalog.loadBundled(URLClassLoader(arrayOf(root.toURI().toURL()), null))
    }

    @Test fun sameNameInTwoKindsIsAmbiguous() {
        val root = Files.createTempDirectory("skills").toFile()
        File(root, KnowledgeIndex.RESOURCE).apply { parentFile.mkdirs() }.writeText(KnowledgeIndex.encode(listOf(
            IndexEntry(kind = "app", name = "phone", display = "电话", description = "拨号"),
            IndexEntry(kind = "skill", name = "phone", display = "手机", description = "选手机"),
        )))
        for (k in listOf("app", "skill")) File(root, KnowledgeIndex.dirOf(k) + "/phone").apply { mkdirs() }.resolve(KnowledgeIndex.fileOf(k)).writeText("---\nname: phone\n---\n$k")
        val c = GuideCatalog.loadBundled(URLClassLoader(arrayOf(root.toURI().toURL()), null))
        assertEquals("ambiguous_skill", (c.view("phone") as GuideView.Failed).error)
        assertTrue((c.view("skill:phone") as GuideView.Found).content.endsWith("skill"))
    }

    @Test fun longAttachmentTruncated() {
        val big = testCatalog(siteMd("big.com", body = "看 `references/big.md`"), "knowledge/sites/big.com/references/big.md" to "字".repeat(VIEW_LIMIT + 500))
        val v = big.view("site:big.com", "references/big.md") as GuideView.Found
        assertEquals("references/big.md", v.file)
        assertTrue(v.truncated)
        assertEquals(VIEW_LIMIT + 500, v.totalChars)
        assertTrue(v.content.startsWith(UNAVAILABLE_TOOLS_NOTE + "\n" + "字".repeat(VIEW_LIMIT)))
        assertTrue(v.content.endsWith("…(truncated; use file_path to view the other files)"))
        val short = big.view("site:big.com") as GuideView.Found
        assertTrue(!short.truncated && short.totalChars == short.content.length - UNAVAILABLE_TOOLS_NOTE.length - 1)
    }

    @Test fun noteDependsOnSandbox() {
        assertEquals(UNAVAILABLE_TOOLS_NOTE, toolsNote(false))
        assertEquals(SANDBOX_TOOLS_NOTE, toolsNote(true))
        val off = catalog.view("report") as GuideView.Found
        assertTrue(off.content.startsWith(UNAVAILABLE_TOOLS_NOTE))
        val on = catalog.view("report", note = toolsNote(true)) as GuideView.Found
        assertTrue(on.content.startsWith(SANDBOX_TOOLS_NOTE))
        assertEquals("skills/report", catalog.scriptSource(on.info))
        // 実際に存在するリストとリストにリストされているファイルのみがサンドボックスに同期されます。
        assertEquals(setOf("skills/report/scripts/manifest.json", "skills/report/scripts/tabulate.py", "skills/report/scripts/fetch.mjs"), catalog.scriptFiles().keys)
    }

    @Test fun skillViewListsScripts() = runTest {
        val sb = FakeSandbox()
        sb.manifests["skills/report"] = listOf(
            ScriptEntry("scripts/tabulate.py", runnable = true),
            ScriptEntry("scripts/fetch.mjs", runnable = true, network = true),
            ScriptEntry("scripts/login.py", runnable = false, reason = "需要账号授权"),
        )
        val call = Part.ToolCall("v1", "skill_read", """{"name":"report"}""")
        val r = Json.parseToJsonElement(toolDispatcher(FakeScreen(), skills = catalog, sandbox = sb).execute(call).content.toString()).jsonObject
        assertEquals("skills/report", r["scriptSource"]!!.jsonPrimitive.content)
        val scripts = r["scripts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("true", "false", "false"), scripts.map { it["runnable"]!!.jsonPrimitive.content })
        assertEquals(ScriptManifest.NEEDS_NETWORK, scripts[1]["reason"]!!.jsonPrimitive.content)
        assertEquals("需要账号授权", scripts[2]["reason"]!!.jsonPrimitive.content)
        assertTrue(r["content"]!!.jsonPrimitive.content.startsWith(SANDBOX_TOOLS_NOTE))
        // リストを作成する前に、パッケージ コピーに含まれるスキル カタログを確認してください。
        assertEquals(1, sb.prepares)
        // はまだ準備中です。スクリプトはリストされていません (リストは準備ができるまで待つ必要があります)。しかし、ツールは提供されています。メモは実行可能なスクリプトとして提供されます。
        sb.status = SandboxStatus.NotInstalled
        val pending = Json.parseToJsonElement(toolDispatcher(FakeScreen(), skills = catalog, sandbox = sb).execute(call).content.toString()).jsonObject
        assertNull(pending["scripts"])
        assertTrue(pending["content"]!!.jsonPrimitive.content.startsWith(SANDBOX_TOOLS_NOTE))
        // デバイスはサポートされていません: サンドボックス ツールはありません
        sb.status = SandboxStatus.Unsupported("x86")
        val off = Json.parseToJsonElement(toolDispatcher(FakeScreen(), skills = catalog, sandbox = sb).execute(call).content.toString()).jsonObject
        assertNull(off["scripts"])
        assertTrue(off["content"]!!.jsonPrimitive.content.startsWith(UNAVAILABLE_TOOLS_NOTE))
    }
}
