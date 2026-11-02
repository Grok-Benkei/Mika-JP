package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.ActionDigest
import com.wangzan101.mika.core.skills.ActionLine
import com.wangzan101.mika.core.skills.Eligibility
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
enum class AppKnowledgeStage { Draft, Trial, Verified, Archived }

/**
 * アプリの自習用知識補足版 (事実のみの短いリスト。このアプリの説明に添付されています)。
 * [lesson]: 未完了の操作またはつまずいた操作から学びました。 [revises]: ドラフト時にこのアプリで使用される補足。これはそのリビジョンです (最初の補足は null)。
 */
@Serializable
data class AppKnowledgeDraft(val id: String, val appName: String, val packageName: String,
    val markdown: String, val sourceRun: String, val stage: AppKnowledgeStage, val createdAt: Long,
    val trialOutcomes: List<Boolean> = emptyList(), val lesson: Boolean = false, val revises: String? = null,
    /** ユーザーに表示されるアプリ名 (「設定」など)。古いレコードはなく、インターフェイスは [appName] に戻ります。 */
    val display: String = "")

/** は、注入時にアプリの説明に添付される自習サプリメントです。 [trial] が true の場合、まだトライアル中であり、完了したタスクには使用されていません。 */
data class LearnedNote(val text: String, val trial: Boolean)


/**
 * アプリの知識を独学。各アプリには、確認対象のコピー ([AppKnowledgeStage.Draft]) を最大 1 つと、挿入されるコピー ([inUse]) を 1 つだけ含めることができます。
 * 受け入れられたリビジョンは、試用期間中に一時的にその改訂版のコピーと置き換わります (そのコピーは挿入または削除されません)。リビジョンが公式に変換されると古いリビジョンがアーカイブされ、リビジョンがアーカイブされると古いリビジョンが置き換えられます。
 */
interface AppKnowledgeDraftStore {
    fun all(): List<AppKnowledgeDraft>
    /** がまだ確認されていない同じアプリである場合、または [AppKnowledgeDraft.revises] が現在挿入されているもの (古いリビジョン) ではない場合、受け入れられず false が返されます。 */
    fun putPending(draft: AppKnowledgeDraft): Boolean
    /**
     * 改訂されたコピーが現在挿入されているものではない (古い) 場合、そのコピーはその場でアーカイブされ、false が返されます。現在のコピーを受信、アーカイブし、変更しようとした後、
     * 同じアプリの古いリビジョンと保留中のリビジョンをまとめてアーカイブし、今後のドラフトがブロックされないようにします。
     */
    fun approve(id: String): Boolean
    fun reject(id: String): Boolean
    /** 試用中のものを使った実行が終わった: [good] は今回を成し遂げたか（[LearnGate]）; [AppKnowledgeTrial.next] に従って正式化、保持、アーカイブのいずれかにする。 */
    fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft?
    fun notes(): Map<String, LearnedNote>

    /** この App が現在注入している補足。 */
    fun inUse(appName: String): AppKnowledgeDraft? = AppKnowledgeTrial.inUse(all(), appName)
}

/** 試用結果で段階をどう変えるか; 個人スキルと同じ: 順調に成し遂げたものは正式化し、教訓から学んだものは2回続けて悪くなければアーカイブせず、それ以外は1回悪ければアーカイブする。 */
object AppKnowledgeTrial {
    const val LESSON_BAD_STREAK = 2

    fun next(d: AppKnowledgeDraft, good: Boolean): AppKnowledgeDraft {
        val outcomes = d.trialOutcomes + good
        val stage = when {
            good -> AppKnowledgeStage.Verified
            !d.lesson -> AppKnowledgeStage.Archived
            outcomes.size >= LESSON_BAD_STREAK && outcomes.takeLast(LESSON_BAD_STREAK).none { it } -> AppKnowledgeStage.Archived
            else -> AppKnowledgeStage.Trial
        }
        return d.copy(stage = stage, trialOutcomes = outcomes)
    }

    /** [rows] の中で [appName] に注入されたもの: 試用中か検証済みで、試用中の改訂に一時的に取って代わられていないもの。 */
    fun inUse(rows: List<AppKnowledgeDraft>, appName: String): AppKnowledgeDraft? {
        val app = rows.filter { it.appName == appName }
        val shadowed = app.filter { it.stage == AppKnowledgeStage.Trial }.mapNotNull { it.revises }.toSet()
        return app.firstOrNull { (it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified) && it.id !in shadowed }
    }
}

/** ドラフト時に注目すべきラン: [end] と [outcome] は、判定後のエンディングによって応答されるハンドオフを伴うエンディングです。 */
data class AppRunInput(
    val runId: String, val goal: String, val end: TaskState, val outcome: RunOutcome, val events: List<RunEvent>, val tools: ToolIndex,
)

/**
 * はアプリ内で一度にどのようなプロセスを実行しますか? [Done] は正常に完了しました。 [Bumpy] は完了しましたが、途中でエラーが発生したり、インターフェイスが変更されなかったり、戻ったり戻ったりしました。
 * [NotDone] 未完了、または部分的にしか完了していません。後の2つは「次は寄り道を少なくする」ための教訓です。 [wire] は、リクエスト内のモデルに書き込まれるエンディングです。
 */
enum class AppRunKind(val wire: String) { Done("done"), Bumpy("bumpy"), NotDone("not_done") }

/**
 * このアプリの知識を補うために、特定のアプリを実行するプロセスをリビジョンに書き込みます。(ツールなしで) モデルを取得し、それにこのアプリの組み込み命令を与えてください。
 * は補足、エンディング、および簡略化されたアクション リスト (エラー コードとインターフェイスの変更を含む) を使用しており、変更後に全文を補足する必要があります。追加する価値のあるものが何もない場合 (最も一般的)、何も生成されません。
 *
 * 返品されたサプリメントはそのまま受け付けられます。パスワードと確認コードが記載された完全なコピーは受け付けられません。
 */
class AppKnowledgeDrafter(private val model: ChatModel) {
    suspend fun draft(run: AppRunInput, app: KnowledgeEntry, builtIn: String?, current: AppKnowledgeDraft?, id: String, atMs: Long): AppKnowledgeDraft? {
        if (app.surface != Surface.Android || app.packages.isEmpty()) return null
        if (Eligibility.interrupted(run.events) != null || run.events.any { it is RunEvent.Replay && it.matched }) return null
        val lines = ActionDigest.of(run.events, run.tools).filter { it.where in app.packages }
        val kind = kind(run.end, run.outcome, lines, run.tools) ?: return null
        // が確認され追加されました。一度に正常に完了した場合、それはおそらく何か新しいものがあることを意味するものではありません (モデルは NONE のみを返します): いいえ、お願いします。
        if (kind == AppRunKind.Done && current?.stage == AppKnowledgeStage.Verified) return null
        val said = when (val end = run.end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> return null
        }
        val existing = current?.let { facts(it.markdown) }
        val request = Prompts.fill(INPUT, mapOf(
            "app" to app.display + " (" + app.packages.joinToString(", ") + ")",
            "outcome" to kind.wire,
            "goal" to run.goal,
            "said" to said,
            "built_in" to builtIn.orEmpty(),
            "current" to existing.orEmpty(),
            "actions" to ActionDigest.render(lines),
        ))
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.Mika)), emptyList())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        val raw = SkillDrafter.stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
        if (raw.isEmpty() || raw.equals(NOTHING, ignoreCase = true)) return null
        if (!valid(raw, run.tools)) return null
        val facts = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (facts == existing) return null
        return AppKnowledgeDraft(id, app.name, app.packages.first(), facts, run.runId, AppKnowledgeStage.Draft, atMs,
            lesson = kind != AppRunKind.Done, revises = current?.id, display = app.display)
    }

    companion object {
        /** が正常に完了すると、このアプリには少なくとも非常に多くの、書く価値のある効果的なアクションが存在することになります (個人スキルの製図と同じ閾値)。 */
        const val MIN_DONE_ACTIONS = Eligibility.MIN_ACTIONS
        /** 教訓は、このアプリでは少なくともこれだけのステップを移動する必要があるということです。1 ～ 2 ステップで停止するもののほとんどは、最初の段階でスタックし (ログインしていない、開けない)、インターフェースを説明できません。 */
        const val MIN_LESSON_ACTIONS = 3
        /** エラーを引き起こすアクションが多すぎたり、インターフェースが変わらない場合は、つまずいていると考えられます。クリックを 1 回逃すのはよくあることですが、入り口またはインターフェースが予想と 2 回異なることを意味します。 */
        const val BUMPY_MISSES = 2
        /** 何度も戻るのは遠回りです。内容を読んで一度戻るのが一般的ですが、おそらく入り口を2回間違えて戻ってしまったからでしょう。 */
        const val BUMPY_BACKS = 2
        /** 補足はせいぜいこれだけの項目とこのくらいの単語です。注入するときは、説明の最後に付ける必要があり、説明自体よりも長くなってはなりません。 */
        const val MAX_FACTS = 12
        const val MAX_CHARS = 1500
        /** モデルが「補う価値のあるものはない」と言ったときは、この語だけを返す。 */
        const val NOTHING = "NONE"

        val PROMPT: String by lazy { Prompts.role("app_knowledge") }
        private val INPUT: String by lazy { Prompts.role("app_knowledge_input") }

        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /**
         * 補足の事実行1件: 頭部、見出し、空行を取り除き、「- 」で始まらなければ付ける。旧版が起草していたのは APP.md 全体であり、
         * こうすれば続けて改訂し、どの行が変わったか比較もできる。
         */
        fun facts(markdown: String): String = FrontMatter.parse(markdown).second.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n") { if (it.startsWith("- ")) it else "- " + it.removePrefix("-").removePrefix("*").trim() }

        /** 今回の実行が [app] の中でどのような経過をたどったか; 学ぶ価値のないものは null（キャンセルされたもの、判定できなかったもの、検証を通らなかったもの、アクションが少なすぎるもの）。 */
        fun kind(end: TaskState, outcome: RunOutcome, lines: List<ActionLine>, tools: ToolIndex): AppRunKind? {
            val misses = lines.count { it.error != null || it.changed == false }
            val backs = lines.count { tools.verb(it.tool) == Verb.Back }
            val bumpy = lines.size >= MIN_LESSON_ACTIONS && (misses >= BUMPY_MISSES || backs >= BUMPY_BACKS)
            val notDone = (end is TaskState.Succeeded && (outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible)) ||
                (end is TaskState.Failed && (end.kind == FailKind.GaveUp || end.kind == FailKind.StepLimit))
            return when {
                LearnGate.done(end, outcome) -> when {
                    bumpy -> AppRunKind.Bumpy
                    lines.count { it.error == null } >= MIN_DONE_ACTIONS -> AppRunKind.Done
                    else -> null
                }
                notDone && lines.size >= MIN_LESSON_ACTIONS -> AppRunKind.NotDone
                else -> null
            }
        }

        /** 今回携帯電話で使ったアプリの中で、ナレッジエントリを持っているアプリは数少ない（順番に1つずつ）。エントリのないアプリには補足を追加する場所がなく、学習することもできません。 */
        fun touched(events: List<RunEvent>, tools: ToolIndex, knowledge: KnowledgeStore): List<KnowledgeEntry> =
            ActionDigest.of(events, tools).mapNotNull { it.where }.distinct()
                .mapNotNull { knowledge.byPackage(it)?.takeIf { e -> e.surface == Surface.Android } }.distinctBy { it.name }

        /** には、「-」で始まる事実行のみがあり、エントリと単語の数が上限内にあり、未登録のツール名が記載されておらず、機密フィルタリングが失敗します。 */
        private fun valid(facts: String, tools: ToolIndex): Boolean {
            val lines = facts.lines().filter { it.isNotBlank() }
            return lines.isNotEmpty() && lines.size <= MAX_FACTS && facts.length <= MAX_CHARS &&
                lines.all { it.startsWith("- ") && it.length > 4 } &&
                TOOL_MENTION.findAll(facts).all { it.groupValues[1] in tools.names } &&
                SensitiveFilter.hit(facts) == null
        }
    }
}
