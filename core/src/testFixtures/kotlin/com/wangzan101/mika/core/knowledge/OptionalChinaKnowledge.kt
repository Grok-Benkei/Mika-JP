package com.wangzan101.mika.core.knowledge

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * 旧記録・外国語画面の互換検査に必要な中国系の資料を、テストで明示的に追加する。
 * optional/chinaは実行時のリソースへ含めず、標準カタログの生成にも参加させない。
 */
object OptionalChinaKnowledge {
    private val loader: ClassLoader by lazy {
        val repo = File("..").canonicalFile
        val sources = listOf(File(repo, "core/src/main/resources"), File(repo, "optional/china"))
        check(sources.all { it.isDirectory }) { "日本語の標準資料と中国系の任意資料が必要です" }
        val root = Files.createTempDirectory("mika-optional-china-test").toFile().apply { deleteOnExit() }
        sources.forEach { source ->
            listOf("knowledge/apps", "knowledge/sites", "skills").forEach { subtree ->
                File(source, subtree).walkTopDown().filter { it.isFile }.forEach { file ->
                    val path = file.relativeTo(source).invariantSeparatorsPath
                    File(root, path).apply { parentFile.mkdirs() }.writeBytes(file.readBytes())
                }
            }
        }
        val index = KnowledgeIndex.build(root)
        check(index.errors.isEmpty()) { "任意資料を追加したテスト用カタログが不正です:\n${index.errors.joinToString("\n")}" }
        File(root, KnowledgeIndex.RESOURCE).apply { parentFile.mkdirs() }.writeText(KnowledgeIndex.encode(index.entries))
        URLClassLoader(arrayOf(root.toURI().toURL()), null)
    }

    fun catalog(): GuideCatalog = GuideCatalog.loadBundled(loader)

    fun entries(): List<KnowledgeEntry> = KnowledgeStore.loadBundled(loader)
}
