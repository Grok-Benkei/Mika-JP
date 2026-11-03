package com.wangzan101.mika.core.knowledge

import org.junit.Assert.assertTrue
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

/** 一時リソースディレクトリ：[files]（相対パス→内容）を記述し、ビルド時と同じルールで検証・インデックス生成し、それをクラスパスとしてローダーに返却します。 */
fun fixtureLoader(files: Map<String, String>): ClassLoader {
    val root = Files.createTempDirectory("kn").toFile().apply { deleteOnExit() }
    files.forEach { (p, t) -> File(root, p).apply { parentFile.mkdirs() }.writeText(t) }
    val r = KnowledgeIndex.build(root)
    assertTrue(r.errors.toString(), r.errors.isEmpty())
    File(root, KnowledgeIndex.RESOURCE).apply { parentFile.mkdirs() }.writeText(KnowledgeIndex.encode(r.entries))
    return URLClassLoader(arrayOf(root.toURI().toURL()), null)
}

fun testCatalog(vararg files: Pair<String, String>): GuideCatalog = GuideCatalog.loadBundled(fixtureLoader(mapOf(*files)))

private fun list(xs: List<String>) = xs.joinToString(", ", "[", "]") { "\"$it\"" }

fun appMd(name: String, packages: List<String>, sites: List<String> = emptyList(), body: String = "正文$name", extra: String = "") =
    "knowledge/apps/$name/APP.md" to "---\nname: $name\ndisplay: $name\npackages: ${list(packages)}\nsites: ${list(sites)}\nopen: $name\n" +
        "description: $name 的说明\n$extra---\n$body\n"

fun siteMd(name: String, apps: List<String> = emptyList(), body: String = "正文$name", domains: List<String> = listOf(name)) =
    "knowledge/sites/$name/SITE.md" to "---\nname: $name\ndisplay: $name\ndomains: ${list(domains)}\napps: ${list(apps)}\nopen: $name\n" +
        "mode: desktop\ndescription: $name 的说明\n---\n$body\n"

fun skillMd(name: String, display: String = name, apps: List<String> = emptyList(), sites: List<String> = emptyList(), body: String = "## 做法\n\n正文$name", extra: String = "") =
    "skills/$name/SKILL.md" to "---\nname: $name\ndisplay: $display\ndescription: $name 的说明\napps: ${list(apps)}\nsites: ${list(sites)}\n" +
        "$extra---\n$body\n"
