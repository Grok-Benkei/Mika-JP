package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.AppKnowledgeTrial
import com.wangzan101.mika.core.knowledge.LearnedNote
import com.wangzan101.mika.core.knowledge.FrontMatter
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 各バージョンと段階台帳のアプリ知識補足。確認すべきもの（草案）はナレッジベースでは読み取られません。各アプリには最大 1 つの確認対象があり、挿入されるのは 1 つだけです。
 * ([AppKnowledgeTrial.inUse]): 試用期間中に、承認されたリビジョンが古いリビジョンに置き換わります。古いものは公式になったらアーカイブされます。アーカイブされると、古いものに置き換えられます。
 * (変更前のファイルはアーカイブに残ります)。
 */
class AppKnowledgeFiles(dir: File) : AppKnowledgeDraftStore {
    private val root = File(dir, "knowledge/app-drafts")
    private val archive = File(root, "archive")
    private val journal = File(root, "changes.jsonl")
    private val idPattern = Regex("[A-Za-z0-9_-]+")

    @Synchronized override fun all(): List<AppKnowledgeDraft> =
        root.listFiles { f -> f.isDirectory && f.name != "archive" && idPattern.matches(f.name) }.orEmpty()
            .mapNotNull { d ->
                runCatching {
                    val row = Json.decodeFromString(AppKnowledgeDraft.serializer(), File(d, "meta.json").readText())
                    row.copy(markdown = File(d, "APP.md").readText())
                }.getOrNull()
            }

    @Synchronized override fun putPending(draft: AppKnowledgeDraft): Boolean {
        val rows = all()
        if (!idPattern.matches(draft.id) || !idPattern.matches(draft.appName) ||
            draft.stage != AppKnowledgeStage.Draft ||
            rows.any { it.appName == draft.appName && it.stage == AppKnowledgeStage.Draft } || stale(draft, rows)) return false
        save(draft)
        return true
    }

    @Synchronized override fun approve(id: String): Boolean {
        val rows = all()
        val old = rows.firstOrNull { it.id == id && it.stage == AppKnowledgeStage.Draft } ?: return false
        if (stale(old, rows)) {
            save(old.copy(stage = AppKnowledgeStage.Archived))
            return false
        }
        save(old.copy(stage = AppKnowledgeStage.Trial))
        archiveStale(old.appName)
        return true
    }

    @Synchronized override fun reject(id: String): Boolean {
        val old = all().firstOrNull { it.id == id && it.stage != AppKnowledgeStage.Archived } ?: return false
        save(old.copy(stage = AppKnowledgeStage.Archived))
        archiveStale(old.appName)
        return true
    }

    /** の試用版は注入されたものです。置き換えられた古いバージョンは正式版に変換されるときに一緒にアーカイブされ、古いバージョンはアーカイブ時に自然に置き換えられます。 */
    @Synchronized override fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft? {
        val rows = all()
        val old = AppKnowledgeTrial.inUse(rows, appName)?.takeIf { it.stage == AppKnowledgeStage.Trial } ?: return null
        val next = AppKnowledgeTrial.next(old, good)
        if (next.stage == AppKnowledgeStage.Verified) rows.filter {
            it.appName == appName && it.id != old.id && (it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified)
        }.forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
        save(next)
        archiveStale(appName)
        return next
    }

    /** 各アプリに挿入されるコピー。古いバージョンでは、ヘッダーを含む APP.md 全体が作成され、本文のみが取り込まれていました。 */
    @Synchronized override fun notes(): Map<String, LearnedNote> {
        val rows = all()
        return rows.map { it.appName }.distinct().mapNotNull { app -> AppKnowledgeTrial.inUse(rows, app) }
            .associate { row -> row.appName to LearnedNote(FrontMatter.parse(row.markdown).second.trim(), trial = row.stage == AppKnowledgeStage.Trial) }
    }

    /** リビジョンのドラフトの対象となったコピーは、現在のものではなくなりました (別のリビジョンが最初に受け入れられました)。受け入れると、そのコピーは破棄されます。 */
    private fun stale(draft: AppKnowledgeDraft, rows: List<AppKnowledgeDraft>) =
        draft.revises != AppKnowledgeTrial.inUse(rows, draft.appName)?.id

    /**
     * 使用していたコピーが変更された後 (受け入れられ、アーカイブされ、試用段階が変更されました)、他のコピーでドラフトされた確認対象のリビジョンは受け入れられなくなりました: アーカイブ済み、
     * 常に「確認される各アプリの最大 1 つのコピー」を占有し、このアプリの将来のドラフトをブロックしないようにします。
     */
    private fun archiveStale(appName: String) {
        val rows = all()
        rows.filter { it.appName == appName && it.stage == AppKnowledgeStage.Draft && stale(it, rows) }
            .forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
    }

    private fun save(row: AppKnowledgeDraft) {
        val d = File(root, row.id)
        if (d.exists()) {
            val revision = UUID.randomUUID().toString()
            d.copyRecursively(File(archive, revision), overwrite = true)
            journal.parentFile.mkdirs()
            journal.appendText(revision + " " + row.id + " " + row.stage + " " + System.currentTimeMillis() + "\n")
        }
        d.mkdirs()
        atomic(File(d, "APP.md"), row.markdown)
        atomic(File(d, "meta.json"), Json.encodeToString(AppKnowledgeDraft.serializer(), row.copy(markdown = "")))
    }

    private fun atomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
