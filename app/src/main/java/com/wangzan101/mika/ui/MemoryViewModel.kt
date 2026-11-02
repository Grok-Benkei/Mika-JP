package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.memory.MemoryIndex
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemoryRejected
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.AppKnowledgeTrial
import com.wangzan101.mika.settings.MemorySettings
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.tool.Verb

/** ページには、書き留められた内容、個人スキル、確認を待っている内容 (承認されるべきプラクティス + スキルとアプリの知識のドラフト) の 3 つのセクションがあります。 */
enum class Segment { Memories, Skills, Pending }

/** リスト内のメモリ。 [source] は、「あなたは 10 月 23 日に「...」と言った」または「あなたの手書き」です。 */
data class Row(
    val id: String, val kind: MemoryKind, val content: String, val keywords: List<String>, val source: String,
    val pinned: Boolean, val pending: Boolean, val replaces: String?,
)

/** [clearStage]: 0 未クリア、1 初回確認、2 2 回目確認。 [message] は 1 回限りのリマインダーです (「この種の情報は覚えません」など)。 */
data class MemoryUi(
    val segment: Segment = Segment.Memories,
    val query: String = "",
    val pending: List<Row> = emptyList(),
    val rows: List<Row> = emptyList(),
    val brokenNotice: String? = null,
    val switches: MemorySwitches = MemorySwitches(),
    val message: String? = null,
    val clearStage: Int = 0,
    val skills: SkillsUi = SkillsUi(),
) {
    /** 「確認中」セクションの項目の数: 承認される思い出 + スキルドラフト + まだ没収されていないアプリのナレッジドラフト。 */
    val pendingCount: Int get() = pending.size + skills.drafts.size + skills.appKnowledge.count { it.stage == AppKnowledgeStage.Draft }
}

/**
 * 「記憶とスキル」ページのステータス: セグメント化、検索 (空のクエリは更新時間の逆順)、承認される実践リストの先頭、編集、先頭、削除、手書き追加、
 * すべてクリア（確認2つ）、スイッチ3つ。スキルのセグメンテーションについては、[SkillsUi] を参照してください。
 */
class MemoryViewModel(
    private val store: MemoryStore,
    private val settings: MemorySettings,
    private val skills: PersonalSkills?,
    scope: CoroutineScope,
    brokenNotice: StateFlow<String?> = MutableStateFlow(null),
    /** すべてクリア: メモリ ファイルを削除し、不良ファイルを保存し、個人スキルとキーを再作成します。 */
    private val wipe: () -> Unit = { store.clearAll() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** はメモリを削除しました。ダイアログ内でメモリを指すプロンプトも削除されました。 */
    private val onForgotten: (String) -> Unit = {},
    /** 変更されたスキルを受け入れるときに指定されたツール名を確認してください。 */
    private val toolNames: Set<String> = knownToolNames(),
    /** スキルスクリプトを実行するためのツール名。 */
    private val scriptTool: String? = knownTools().nameOf(Verb.RunBundledScript),
    /** 変更されたスキルを受け入れる場合: スクリプトを実行するツールに関しては、スクリプトがリスト内で実行可能としてマークされている必要があります。 */
    private val runnableScripts: Set<String> = emptySet(),
    private val appDrafts: AppKnowledgeDraftStore? = null,
) {
    private val local = MutableStateFlow(MemoryUi())
    private val skillsVersion = MutableStateFlow(0)

    val ui: StateFlow<MemoryUi> = combine(local, store.items, settings.switches, brokenNotice, skillsVersion) { l, items, sw, broken, _ ->
        val all = filtered(items, l.query)
        l.copy(
            pending = all.filter { it.status == MemoryStatus.pending }.map(::row),
            rows = all.filter { it.status == MemoryStatus.active }.map(::row),
            brokenNotice = broken, switches = sw,
            skills = skillsUi(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, MemoryUi())

    fun select(s: Segment) = local.update { it.copy(segment = s) }
    fun search(q: String) = local.update { it.copy(query = q) }
    fun dismissMessage() = local.update { it.copy(message = null) }

    fun edit(id: String, content: String, keywords: List<String>) =
        report(store.update(id, content, keywords, MemorySource("manual", atMs = clock())).exceptionOrNull())
    fun setPinned(id: String, pinned: Boolean) = store.setPinned(id, pinned)
    fun delete(id: String) {
        if (store.forget(id).isSuccess) onForgotten(id)
    }
    fun approve(id: String) { store.approve(id) }
    /** 承認する＝削除するメソッドの「しない」をクリックします。 */
    fun reject(id: String) = delete(id)

    fun add(kind: MemoryKind, content: String, keywords: List<String>) =
        report(store.add(kind, content, keywords, MemorySource("manual", atMs = clock())).exceptionOrNull())

    fun requestClear() = local.update { it.copy(clearStage = 1) }
    fun cancelClear() = local.update { it.copy(clearStage = 0) }
    fun confirmClear() {
        val stage = local.value.clearStage
        if (stage == 2) wipe()
        local.update { it.copy(clearStage = if (stage == 1) 2 else 0) }
        skillsVersion.update { it + 1 }
    }

    fun setSwitches(transform: (MemorySwitches) -> MemorySwitches) = settings.update(transform)

    private fun report(e: Throwable?) {
        val msg = when ((e as? MemoryRejected)?.code) {
            null -> null
            "sensitive" -> tr(R.string.memory_sensitive)
            "too_long" -> tr(R.string.memory_too_long)
            "memory_full" -> tr(R.string.memory_full)
            "unavailable" -> tr(R.string.memory_unavailable)
            else -> tr(R.string.memory_not_changed)
        }
        if (msg != null) local.update { it.copy(message = msg) }
    }

    private fun filtered(items: List<MemoryItem>, query: String): List<MemoryItem> =
        if (query.isBlank()) items.sortedByDescending { it.updatedAt }
        else MemoryIndex(items).search(query, items.size.coerceAtLeast(1), includePending = true).map { it.first }

    private fun row(m: MemoryItem) = Row(m.id, m.kind, m.content, m.keywords, sourceLine(m.source), m.pinned, m.status == MemoryStatus.pending, m.replaces)

    // ---- スキルセグメンテーション ----
    private fun skillsUi(): SkillsUi = skills?.let { SkillsUi.of(it, appDrafts) } ?: SkillsUi()
    private fun skillsChanged() = skillsVersion.update { it + 1 }

    /** はドラフトを受け入れます。 [edited] は、ユーザーによって変更された全文です。草案と同様にフォーマットとセキュリティチェックを通過する必要がありますが、受け入れられず、説明も受けられません。 */
    fun approveSkill(draftId: String, edited: String? = null) {
        val p = skills ?: return
        val problem = edited?.let { SkillDrafter.validate(it, toolNames, scriptTool, runnableScripts) }
        if (problem != null) {
            local.update { it.copy(message = SKILL_PROBLEMS[problem] ?: tr(R.string.skill_bad_format)) }
            return
        }
        if (p.approve(draftId, edited) == null) {
            local.update { it.copy(message = tr(R.string.skill_changed)) }
        }
        skillsChanged()
    }

    fun rejectSkill(draftId: String) { skills?.reject(draftId); skillsChanged() }

    fun approveAppDraft(id: String) { appDrafts?.approve(id); skillsChanged() }
    fun rejectAppDraft(id: String) { appDrafts?.reject(id); skillsChanged() }

    fun suspendSkill(name: String) = changeSkill(name) { it.copy(status = SkillStatus.suspended) }

    /** 次回何か問題が発生したときにすぐに非アクティブ化されないように、復元時に古い結果をクリアします。 */
    fun restoreSkill(name: String) = changeSkill(name) {
        it.copy(status = it.restoredStatus(), outcomes = emptyList())
    }

    fun deleteSkill(name: String) { skills?.remove(name); skillsChanged() }

    fun restorePreviousSkill(name: String) {
        val p = skills ?: return
        val revision = p.revisions(name).firstOrNull() ?: return
        if (!p.restore(revision.id)) local.update { it.copy(message = tr(R.string.skill_restore_failed)) }
        skillsChanged()
    }

    private fun changeSkill(name: String, change: (PersonalSkill) -> PersonalSkill) {
        val p = skills ?: return
        p.get(name)?.let { p.put(change(it)) }
        skillsChanged()
    }

    companion object {
        private val SKILL_PROBLEMS get() = mapOf(
            "sensitive" to tr(R.string.memory_sensitive),
            "invalid_name" to tr(R.string.skill_invalid_name),
            "missing_description" to tr(R.string.skill_missing_description),
            "missing_section" to tr(R.string.skill_missing_section),
            "too_long" to tr(R.string.skill_too_long),
            "unknown_tool" to tr(R.string.skill_unknown_tool),
        )

        fun sourceLine(s: MemorySource): String =
            if (s.type == "manual") tr(R.string.memory_manual)
            else SimpleDateFormat(tr(R.string.date_pattern_md), uiLocale()).format(Date(s.atMs)).let { day ->
                if (s.channel != null && s.channel != "Ui") tr(R.string.memory_said_via, day, s.channel, s.quote.orEmpty())
                else tr(R.string.memory_said, day, s.quote.orEmpty())
            }
    }
}

/**
 * アプリの知識の補足。確認: [revision] が true の場合、それは現在使用されているもののリビジョンであり、それ以外の場合は最初の追加です。
 * [changedLines] は、現在使用されている行に対して追加または削除された行です (最初に追加されたときは比較的空白です)。
 * [replaced]: 受け入れられたリビジョンは試用中であり、一時的にこのリビジョンと置き換えられました (挿入されません。リビジョンはアーカイブ後に置き換えられます)。
 */
data class AppKnowledgeRow(val id: String, val name: String, val markdown: String, val stage: AppKnowledgeStage,
    val revision: Boolean = false, val changedLines: List<String> = emptyList(), val replaced: Boolean = false)

/** スキル セグメントの内容: 承認されるドラフトと承認された個人スキル (現在使用されているものが最初)。 */

data class SkillsUi(val drafts: List<SkillRow> = emptyList(), val installed: List<SkillRow> = emptyList(),
    val appKnowledge: List<AppKnowledgeRow> = emptyList()) {
    companion object {
        fun of(skills: PersonalSkills, appDrafts: AppKnowledgeDraftStore? = null): SkillsUi {
            val installed = skills.list()
            val byName = installed.associateBy { it.name }
            val drafts = skills.drafts().map { (id, d) ->
                row(id, d).copy(changedLines = d.replacesDraftOf?.let { byName[it] }?.let { changedLines(it.markdown, d.markdown) }.orEmpty())
            }
            val all = appDrafts?.all().orEmpty()
            val byId = all.associateBy { it.id }
            val appRows = all.filter { it.stage != AppKnowledgeStage.Archived }.map { d ->
                val row = AppKnowledgeRow(d.id, d.display.ifBlank { d.appName }, d.markdown, d.stage)
                if (d.stage != AppKnowledgeStage.Draft) row.copy(replaced = AppKnowledgeTrial.inUse(all, d.appName)?.id != d.id)
                else row.copy(revision = d.revises != null,
                    changedLines = changedLines(d.revises?.let { byId[it] }?.markdown?.let(AppKnowledgeDrafter::facts).orEmpty(), d.markdown))
            }
            return SkillsUi(drafts, installed.sortedBy { it.status == SkillStatus.suspended }.map { row(it.name, it, skills) }, appRows)
        }

        private fun row(id: String, s: PersonalSkill, skills: PersonalSkills? = null): SkillRow {
            val meta = FrontMatter.parse(s.markdown).first
            return SkillRow(
                id, s.name, (meta["display"] as? String)?.takeIf { it.isNotBlank() } ?: s.name, meta["description"] as? String ?: "",
                s.markdown, suspended = s.status == SkillStatus.suspended, trial = s.status == SkillStatus.trial,
                canRestore = skills?.revisions(s.name)?.isNotEmpty() == true,
                recent = s.outcomes.takeLast(3).map(::outcomeLabel),
                userCreated = s.createdByUser,
            )
        }

        /** 元のドラフトと比較して更新されたドラフト (スキルまたはアプリの知識の補足): 「+」で行が追加され、「-」で行が削除されました (空白行はなくなりました)。 */
        fun changedLines(old: String, new: String): List<String> {
            val before = old.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val after = new.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            return after.filter { it !in before }.map { "+ $it" } + before.filter { it !in after }.map { "- $it" }
        }

        private fun outcomeLabel(o: String) = when (o) {
            "verified" -> tr(R.string.verify_passed)
            "unverified" -> tr(R.string.verify_unverified)
            "verifier_rejected" -> tr(R.string.verify_returned)
            else -> tr(R.string.capsule_failed)
        }
    }
}

/** [id] はドラフト番号です (受け入れられるものはスキル名です)。 [changedLines] は、更新されたドラフトで元のスキルから変更された行です。 */
data class SkillRow(
    val id: String, val name: String, val display: String, val description: String, val markdown: String,
    val suspended: Boolean = false, val trial: Boolean = false, val canRestore: Boolean = false,
    val recent: List<String> = emptyList(), val changedLines: List<String> = emptyList(),
    /** ユーザーは、それをタスクに書き留めるように要求しました (完了したタスクからは学習されません)。 */
    val userCreated: Boolean = false,
)
