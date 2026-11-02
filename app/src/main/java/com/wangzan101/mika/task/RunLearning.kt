package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.AppRunInput
import com.wangzan101.mika.core.memory.Change
import com.wangzan101.mika.core.memory.MemoryExtractor
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.skills.DraftInput
import com.wangzan101.mika.core.skills.DraftResult
import com.wangzan101.mika.core.skills.Eligibility
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.skills.SkillNote
import com.wangzan101.mika.core.skills.SkillOutcomes
import com.wangzan101.mika.core.skills.SkillResult
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.wangzan101.mika.core.tool.ToolIndex

/** ランニング後に何かを学ぶために必要なすべての教材。タスクの終了時に [TaskRun] によって引き渡されます。 */
data class RunAftermath(
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val end: TaskState,
    /** 今回ユーザーが言ったこと;メモリがオフのときは空です。 */
    val utterances: List<String>,
    /** タスクのmemory_saveによって保存されました。 */
    val saved: List<Change>,
    val usedMemory: List<String>,
    val toolNames: Set<String>,
    /** 名前による検索ツールによって実行されるアクション。 */
    val tools: ToolIndex,
    /** この実行のイベント (実行レコードから読み取られます)。読み取られていない場合は空です。 */
    val events: suspend () -> List<RunEvent>,
    val origin: RunOrigin = RunOrigin.Ui,
    val knowledge: KnowledgeStore? = null,
    /** 合否は回答提出後に判定されます。完了したもの ([LearnGate]) からのみ学習してください。判定または合格しなかったコードは [RunOutcome.Unknown] です。 */
    val outcome: RunOutcome = RunOutcome.Unknown,
)

/**
 * タスク完了後: トライアルで使用した個人スキルとアプリの知識の結果を書き留め (自動的に無効化、公式またはアーカイブに変換される場合があります)、ユーザーの言葉から記憶を抽出します。
 * 完了したタスクの製図スキル。実践的なアプリは、完了した操作、失敗した操作、およびつまずいた操作から知識を補足する改訂版を作成します ([AppKnowledgeDrafter] を参照)。
 * の完了は、回答（[RunAftermath.outcome]）を提出した後の判定結果に基づいて計算されます。 「答えを渡す」必要はなく、最終検証（[LearnGate]）に合格する必要もありません。
 * は、結果通知が送信された後 ([launchAfter])、バックグラウンド コルーチンですべて行われます。どのステップでもエラーが発生しても、タスクの結果には影響しません。
 *
 * ドラフトしていないときの別の状況: 今回はリプレイ ルートを選択しました ([RunEvent.Replay] には対応する手順があります) - ルートは記憶されています。
 * 再度ドラフトすると、同じルートが再度コピーされるだけです。いつものように、ドラフトにはいくつかのバッチ ステップ (do_steps) だけが実行され、これはモデル自体によって計画されました。 メモリマスタースイッチがオフの場合、
 */
class RunLearning(
    private val switches: MemorySwitches,
    /** は null になります。 */
    private val store: MemoryStore?,
    private val skills: PersonalSkills,
    private val catalog: GuideCatalog,
    private val extractor: MemoryExtractor?,
    private val drafter: SkillDrafter?,
    private val ids: () -> String,
    /** 評価フック：ドラフト開始後すぐに置きます（補助輪）。 */
    private val autoApprove: Boolean = false,
    private val yieldToForeground: suspend () -> Unit = {},
    private val appDrafter: AppKnowledgeDrafter? = null,
    private val appDrafts: AppKnowledgeDraftStore? = null,
) {
    suspend fun learn(a: RunAftermath): RunNotes {
        yieldToForeground()
        val events = guarded(emptyList()) { a.events() }
        val used = events.filterIsInstance<RunEvent.SkillUsed>()
        val skillNotes = recordOutcomes(used, a.end, a.outcome).toMutableList()
        // トライアルでのアプリの知識と個人スキルは同じ結果に従って計算されます。キャンセルと失敗は記録されません。
        val trial = SkillOutcomes.resultOf(a.end, a.outcome)?.takeIf { it != SkillResult.unreviewed }
        val trialNames = events.filterIsInstance<RunEvent.State>().filter { it.name == "knowledge_trial_used" }.map { it.detail }.distinct()
        if (trial != null) trialNames.forEach { name -> guarded(null) { appDrafts?.recordTrial(name, trial == SkillResult.verified) } }

        val changes = a.saved.toMutableList()
        val store = store
        val extractor = extractor
        // 実行中の番号がない場合、プロンプトをハングする場所はありません。ユーザーはそれを見ることができず、元に戻すこともできません。つまり、抽出は行われません。
        val learnable = a.origin == RunOrigin.Ui && a.end is TaskState.Succeeded
        val readWeb = events.filterIsInstance<RunEvent.Observed>().any { it.place?.kind in setOf(PlaceKind.Site, PlaceKind.Page) }
        if (learnable && !readWeb && a.runId != null && store != null && extractor != null && switches.autoRemember && a.utterances.isNotEmpty()) {
            val now = store.clock()
            changes += guarded(emptyList()) {
                yieldToForeground()
                extractor.extract(a.utterances, MemoryExtractor.related(a.utterances, store.items.value), store) { q ->
                    MemorySource("user_said", q, a.conversationId, now, a.origin.name)
                }
            }
        }

        // 達成されたものからのみ学ぶ: 答えが真実に達成されなかった、一部だけが達成された、または達成されたと判断されなかった場合、草案は作成されません。
        val done = learnable && LearnGate.done(a.end, a.outcome)
        if (done) draft(a, events, used.map { it.name }.distinct())?.let(skillNotes::add)
        // アプリの知識は、未完成の操作やつまずきながらも学ぶことができます (価値があるかどうかは、AppKnowledgeDrafter が経験に基づいて判断します)
        if (a.origin == RunOrigin.Ui) skillNotes += draftApps(a, events)
        return RunNotes(a.conversationId, a.runId.orEmpty(), MemoryExtractor.notes(changes), a.usedMemory, skillNotes)
    }

    /** 各スキルは独立して読み取られ、書き換えられます（結果を失うことなく、他のタスクと同時に結果を記録します）。 1 つのエラーが他のものに影響を与えることはありません。 */
    private suspend fun recordOutcomes(used: List<RunEvent.SkillUsed>, end: TaskState, outcome: RunOutcome): List<SkillNote> {
        val result = SkillOutcomes.resultOf(end, outcome) ?: return emptyList()
        return used.groupBy { it.name }.mapNotNull { (name, uses) ->
            val next = guarded(null) {
                skills.update(name) { s -> if (s.status == SkillStatus.active || s.status == SkillStatus.trial) SkillOutcomes.record(s, result, followed = true) else null }
            } ?: return@mapNotNull null
            when (next.status) {
                SkillStatus.suspended -> SkillNote(tr(R.string.skill_suspended), suspended = name)
                SkillStatus.active -> if (uses.isNotEmpty() && result == SkillResult.verified) SkillNote(tr(R.string.skill_verified, name)) else null
                else -> null
            }
        }
    }

    private suspend fun draft(a: RunAftermath, events: List<RunEvent>, used: List<String>): SkillNote? {
        val d = drafter ?: return null
        val runId = a.runId ?: return null
        if (!switches.learnSkills) return null
        if (events.any { it is RunEvent.Replay && it.matched }) return null
        // 個人のスキルに従って作成され、結果のみが一度記録され、それ以上の製図は不要です
        val followed = used.firstOrNull().takeIf { SkillOutcomes.resultOf(a.end, a.outcome) == SkillResult.verified }
        val done = a.end as? TaskState.Succeeded
        if (Eligibility.check(events, followed, a.tools, done = LearnGate.done(a.end, a.outcome)) != null) return null
        val r = guarded(null) {
            yieldToForeground()
            d.draft(DraftInput(runId, a.goal, done?.answer.orEmpty(), done?.sources.orEmpty(), events, a.toolNames, a.tools), skills, catalog)
        }
        val draft = r as? DraftResult.Draft ?: return null
        // は確認すべきドラフトの更新です。新しいドラフトを開始せずにそのドラフトを書き戻してください。
        val id = draft.replacesPending ?: ids()
        guarded(Unit) { skills.putPending(id, draft.skill) }
        if (autoApprove) {
            guarded(null) { skills.approve(id) }
            return null
        }
        val description = FrontMatter.parse(draft.skill.markdown).first["description"] as? String ?: draft.skill.name
        return SkillNote(tr(R.string.skill_learned, description), draftId = id)
    }

    /** 今回タッチされ、ナレッジエントリがある各アプリは 1 回ずつ審査されます。同じアプリに確認が必要なリビジョンがある場合、再度ドラフトされることはありません。 */
    private suspend fun draftApps(a: RunAftermath, events: List<RunEvent>): List<SkillNote> {
        val drafter = appDrafter ?: return emptyList()
        val drafts = appDrafts ?: return emptyList()
        val knowledge = a.knowledge ?: return emptyList()
        val runId = a.runId ?: return emptyList()
        if (!switches.learnSkills) return emptyList()
        val run = AppRunInput(runId, a.goal, a.end, a.outcome, events, a.tools)
        return AppKnowledgeDrafter.touched(events, a.tools, knowledge).mapNotNull { app ->
            if (guarded(true) { drafts.all().any { it.appName == app.name && it.stage == AppKnowledgeStage.Draft } }) return@mapNotNull null
            val current = guarded(null) { drafts.inUse(app.name) }
            val draft = guarded(null) {
                yieldToForeground()
                drafter.draft(run, app, knowledge.builtIn(app.name), current, ids(), events.lastOrNull()?.atMs ?: 0L)
            } ?: return@mapNotNull null
            if (!guarded(false) { drafts.putPending(draft) }) return@mapNotNull null
            SkillNote(tr(if (draft.revises != null) R.string.skill_app_revised else R.string.skill_app_draft, app.display))
        }
    }

    /** 学習中のすべてのエラーは、他のステップには影響しません。キャンセルは通常通り破棄されます。 */
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    companion object {
        /** 最初に結果通知を送信し、その後バックグラウンドで学習します。ユーザーは最初に結果を確認します。学習が遅くても問題ありません。 */
        suspend fun launchAfter(scope: CoroutineScope, notify: suspend () -> Unit, learn: suspend () -> Unit): Job {
            notify()
            return scope.launch { learn() }
        }
    }
}
