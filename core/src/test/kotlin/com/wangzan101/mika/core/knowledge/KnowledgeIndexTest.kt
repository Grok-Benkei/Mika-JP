package com.wangzan101.mika.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KnowledgeIndexTest {
    private fun errors(vararg files: Pair<String, String>): List<String> {
        val root = Files.createTempDirectory("kn").toFile().apply { deleteOnExit() }
        files.forEach { (p, t) -> File(root, p).apply { parentFile.mkdirs() }.writeText(t) }
        return KnowledgeIndex.build(root).errors
    }

    private fun assertError(part: String, vararg files: Pair<String, String>) {
        val e = errors(*files)
        assertTrue("expected an error with '$part', got $e", e.any { part in it })
    }

    @Test fun validTreeIndexesAllKinds() {
        val loader = fixtureLoader(mapOf(
            appMd("shop", listOf("com.shop"), sites = listOf("shop.com"), extra = "package_aliases: [\"小店 | com.shop\"]\n"),
            siteMd("shop.com", apps = listOf("shop"), body = "看 `references/a.md`", domains = listOf("shop.com", "www.shop.cn")),
            "knowledge/sites/shop.com/references/a.md" to "附件",
            skillMd("buy", apps = listOf("shop"), sites = listOf("shop.com"), extra = "terms: [购物]\n"),
        ))
        val rows = GuideCatalog.loadBundled(loader).rows
        assertEquals(listOf("app:shop", "site:shop.com", "skill:buy"), rows.map { "${it.kind}:${it.name}" })
        assertEquals(mapOf("小店" to "com.shop"), rows[0].packageAliases)
        assertEquals(listOf("购物"), rows[2].terms)
        assertEquals(listOf("shop.com", "shop.cn"), rows[1].domains)
        assertEquals(listOf("references/a.md"), rows[1].files)
    }

    @Test fun requiredFieldsAndLimits() {
        assertError("ヘッダー（--- で囲んだメタデータ）がありません", "knowledge/apps/a/APP.md" to "没有头部")
        assertError("はディレクトリ名", appMd("a", listOf("p.a")).let { (p, t) -> p.replace("/a/", "/b/") to t })
        assertError("packagesがありません", appMd("a", emptyList()))
        assertError("未知のキーがあります: assistent", appMd("a", listOf("p.a"), extra = "assistent: 小助手\n"))
        // 移行されたメタデータ キーは削除されました。書いたら報告してください。
        listOf("risk: read", "status: draft", "updated: 2026-10-24", "verified_on: x")
            .forEach { assertError("未知のキーがあります: ${it.substringBefore(':')}", appMd("a", listOf("p.a"), extra = "$it\n")) }
        assertError("termsはスキル専用", appMd("a", listOf("p.a"), extra = "terms: [购物]\n"))
        assertError("termsはスキル専用", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\nterms: [购物]\n") })
        assertError("descriptionは60 文字以内", appMd("a", listOf("p.a")).let { (p, t) -> p to t.replace("description: a 的说明", "description: " + "长".repeat(61)) })
        assertError("上限2500", appMd("a", listOf("p.a"), body = "字".repeat(2501)))
        assertError("modeにはdesktopまたはmobile", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "") })
        // ペース: アプリと Web サイトを作成できますが、注意が必要です。スキルは書けない
        assertError("paceに指定できる値:", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\npace: slow\n") })
        assertError("paceに指定できる値:", appMd("a", listOf("p.a"), extra = "pace: slow\n"))
        assertError("paceを指定できるのはアプリとウェブサイト", skillMd("s", extra = "pace: careful\n"))
        assertTrue(errors(appMd("a", listOf("p.a"), extra = "pace: careful\n")).isEmpty())
        val careful = siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\npace: careful\n") }
        assertTrue(errors(careful).isEmpty())
        assertError("をdomainsに含めてください", siteMd("a.com", domains = listOf("b.com")))
        assertError("SKILL.md がありません", "skills/x/notes.md" to "x")
        assertError("linksの", appMd("a", listOf("p.a"), extra = "links: [\"文件 | file:///sdcard/a\"]\n"))
        // スキルテキストに上限なし
        assertTrue(errors(skillMd("long", body = "字".repeat(5000))).isEmpty())
    }

    @Test fun uniqueNamesPackagesAndDomains() {
        assertError("パッケージ名p.a が複数のアプリで定義", appMd("a", listOf("p.a")), appMd("b", listOf("p.a")))
        assertError("ドメインa.com が複数のサイトで定義", siteMd("a.com"), siteMd("b.com", domains = listOf("b.com", "a.com")))
        assertError("名前dup が重複", appMd("dup", listOf("p.a")), skillMd("dup"))
    }

    @Test fun linksMustExistBothWays() {
        assertError("sitesに指定されたa.com がありません", appMd("a", listOf("p.a"), sites = listOf("a.com")))
        assertError("appsにaがありません", appMd("a", listOf("p.a"), sites = listOf("a.com")), siteMd("a.com"))
        assertError("appsに指定されたnope がありません", skillMd("s", apps = listOf("nope")))
    }

    @Test fun referencedAttachmentsMustExist() {
        assertError("参照ファイルreferences/x.md が", appMd("a", listOf("p.a"), body = "读 `references/x.md`"))
        // 別のエントリに名前を付ける場合は、そのエントリ内で検索します
        assertTrue(errors(appMd("a", listOf("p.a"), body = "读 `skill:s` 的 `references/x.md`"), skillMd("s"), "skills/s/references/x.md" to "x").isEmpty())
        assertError("参照先skill:t がありません", appMd("a", listOf("p.a"), body = "见 skill:t"))
        assertError("実行可能と定義されたscripts/run.pyがありません", skillMd("s"), "skills/s/scripts/manifest.json" to """[{"file":"scripts/run.py","runnable":true}]""")
    }

    @Test fun localPathsRejectedExceptLicenseFiles() {
        assertTrue(errors(appMd("a", listOf("p.a"), body = "见 /Users/me/x")).any { "ローカル端末の絶対パス" in it })
        assertTrue(errors(appMd("a", listOf("p.a")), "knowledge/apps/a/assets/LICENSE.md" to "/Users/me/x").isEmpty())
    }
}
