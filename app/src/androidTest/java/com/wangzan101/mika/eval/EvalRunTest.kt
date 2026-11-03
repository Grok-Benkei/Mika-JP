package com.wangzan101.mika.eval

import com.wangzan101.mika.DeviceTaskState
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.eval.AppCapabilities
import com.wangzan101.mika.core.eval.Check
import com.wangzan101.mika.core.eval.EvalCategory
import com.wangzan101.mika.core.eval.EvalPairing
import com.wangzan101.mika.core.eval.Checks
import com.wangzan101.mika.core.eval.EvalReport
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunMetricsStore
import com.wangzan101.mika.core.eval.EvalTask
import com.wangzan101.mika.core.eval.EvalTasks
import com.wangzan101.mika.core.eval.Observed
import com.wangzan101.mika.core.eval.Prices
import com.wangzan101.mika.core.eval.RunFacts
import com.wangzan101.mika.core.eval.Scoring
import com.wangzan101.mika.core.eval.TaskScore
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import com.wangzan101.mika.core.assembly.recordedTools
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.core.cap.phone.OperatingPace

/**
 * 実機評価: [TaskService] の実パスを通じてタスクセットを 1 つずつ実行し、スコアを付けて概要を書き込みます。パラメータが明示的に渡された場合にのみ実行されます。
 *
 * `-e evalRun <名前> [-e evalSet <assets ディレクトリまたは ID の接頭辞>] [-e evalPriceIn <100万あたりの入力トークン単価> -e evalPriceOut <出力単価>
 *  [-e evalPriceCached <キャッシュされた単価>]] [-e evalOnTakeover cancel|wait] [-e evalAllowIrreversible true] [-e evalFlags none|<key=on,…>]
 *  [-e evalChannel web|app] [-e evalSplit tune|holdout]`
 *
 * の質問は、テスト パッケージ アセットのカテゴリ ディレクトリ (リポジトリの eval/tasks/<category>/)、Web ページの質問ディレクトリ web、およびスモークテストのディレクトリ eval-smoke から取得されます。 evalSet が渡されない場合、スモークの質問を除くすべての質問が実行されます。
 * evalSplit では、この質問プールのみを実行できます (質問のパーティションに従って、すべてを渡して実行しないでください)。ホールドアウト プールの結果は、リリースの決定にのみ使用され、プロンプトの言葉や知識を調整するためには使用されません。
 * evalChannel が与えられ、各質問のチャネルが Web ページまたはアプリになるように強制され (ターゲットの前に「Web ページを使用する」/「アプリを使用する」を追加します)、2 つのルートを比較します。
 * メイン モデルは設定で構成されています (最初に LiveRunTest#configureProvider を使用します)。
 * の結果は、アプリの外部ファイル ディレクトリ eval/<evalRun>/:summary.md、scores.jsonl、<質問 ID>/run/ (質問の完全な実行記録) に書き込まれます。
 *
 * evalFlags (デフォルトはなし) は [FeatureFlags.setSpeed] 経由で有効になり、実行後に元の値に復元されます。スイッチの組み合わせが概要に書き込まれます。
 * evalPace (高速/標準/人間、デフォルトは高速) は、この評価におけるアプリの操作のペースです。設定を変更しないでください。評価はデフォルトでも高速に実行されます。
 *
 * `-e evalVariants on` (プレイバック評価、シリアル): バリエーションのある質問のみを実行します。各質問に対して最初に元の質問を実行し (完了後のルートに注意してください)、次にそのバリエーションを順番に実行します。
 * によって要約されたインジケーターはバリアントのみをカウントしており、元の質問は別のセクションにリストされています。バリアントの実行記録は、<元の質問 ID>_v<n>/ に記録されます。
 *
 * `-e evalParallel <n>` (n ≥ 2、ParallelTask​​s を同時に開く必要があります): 「使用されるアプリは互いに素である」 ([EvalPairing]) による貪欲なグループ化、同じグループ内の質問は同時に実行されます。
 * の概要と「合計実時間」。同じグループ内の質問は、自動回答 (質問内のキーワードに従って各質問に対する回答を検索します) とステップ数の上限 (グループ内で最大) を共有します。 同じグループ内の別の質問がアプリを占有しているために質問
 * が完了していない場合、失敗の理由は [Scoring.INTERFERENCE_PREFIX] として記録されます (評価者の問題はカウントされず、別のカテゴリにリストされます)。
 *
 * レビューの質問ごとに新しいダイアログを使用します。質問モード (開発者向けオプションで自律モードがオフになっています) で、レベルが有効であることを確認します。確認したい場合は、[testTasks.evalHooks.autoDecideConfirmation]が自動で応答します。
 * (接続されていない場合は、タイムアウトになるまで待って、同意しないを押してください)。書き込み質問と取り消し可能な質問は、自動的に拒否され、すべての要件が満たされた場合にのみ実行されます。取り消しできない質問は明示的に渡す必要があります
 * `-e evalAllowIrreversible true`、デフォルトでは実行されません。
 */
@RunWith(AndroidJUnit4::class)
class EvalRunTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    private var savedFlags: SpeedFlags? = null

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        savedFlags?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    @Test
    fun runEval() {
        val run = args.getString("evalRun")
        assumeTrue(run != null)
        require(RUN_NAME.matches(run!!)) { "evalRun must match $RUN_NAME" }
        val onTakeover = args.getString("evalOnTakeover") ?: "cancel"
        require(onTakeover == "cancel" || onTakeover == "wait") { "evalOnTakeover must be cancel or wait" }
        testTasks.evalHooks.onTakeover = onTakeover
        // レビューは送信、削除、または配置されることはなく、すべてのレベルで拒否されます。
        testTasks.evalHooks.autoDecideConfirmation = { false }
        // の評価は、デフォルトではスキルを学習したり、自動的に記憶したりしません。取得されたスコアは、前のラウンドで学習した内容の影響を受けません。テストを受けるときに明示的にオンにする必要があります。
        testTasks.evalHooks.learnSkills = args.getString("learnSkills") == "on"
        testTasks.evalHooks.rememberMemory = args.getString("memory") == "on"
        testTasks.evalHooks.autoApproveSkillDrafts = args.getString("autoApproveSkills") == "true"
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("evalPace")) ?: OperatingPace.FAST
        val allowIrreversible = args.getString("evalAllowIrreversible") == "true"
        val prices = prices()
        val flags = SpeedFlags.parse(args.getString("evalFlags") ?: "none")
        val featureFlags = FeatureFlags.get(context)
        savedFlags = featureFlags.speedFlags()
        featureFlags.setSpeed(flags)

        // 習得スキルと記憶のステータス：デフォルトでクリア、ラウンド3で合格（②で習得したスキルで実行） keepState=true 予約済み
        if (args.getString("keepState") != "true") MemoryRuntime.of(context).wipeAll()
        val split = args.getString("split")
        require(split == null || split == "train" || split == "test") { "split must be train or test" }
        val evalSplit = args.getString("evalSplit")?.takeIf { it.isNotBlank() }
        require(evalSplit == null || evalSplit in EvalTasks.PARTITIONS) { "evalSplit must be tune or holdout" }
        // evalChannel web|app: 同じバッチの質問が Web またはアプリに強制的に送信されます (2 つのルートの比較)
        val channel = args.getString("evalChannel")?.takeIf { it.isNotBlank() }
        // 同じセッション内の質問は、同じ会話内の ID の順に実行されるため、(セッション、ID) に従って配置されます。
        val tasks = loadTasks(args.getString("evalSet")).filter { split == null || it.split == split }
            .filter { evalSplit == null || it.partition == evalSplit }
            .map { t -> if (channel != null) t.copy(channel = channel) else t }
            .sortedWith(compareBy({ it.session.orEmpty() }, { it.id }))
        check(tasks.isNotEmpty()) { "no eval tasks matched evalSet=${args.getString("evalSet")}" }
        val errors = EvalTasks.validateAll(tasks)
        check(errors.isEmpty()) { "invalid eval tasks:\n" + errors.joinToString("\n") }

        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        val out = File(ext, "eval/$run").apply { deleteRecursively(); mkdirs() }
        DeviceSupport.shell()
        DeviceSupport.service()
        val model = ProviderStore.get(context).configFor(ModelRole.Main)?.model ?: error("no main model configured")
        val probes = EvalProbes(DeviceSupport::sh, File(ext, "eval/logged-in.txt"))
        val runs = RunStore(context)

        val parallel = args.getString("evalParallel")?.toInt() ?: 1
        require(parallel == 1 || flags.parallelTasks) { "evalParallel needs evalFlags parallelTasks=on" }
        val variants = args.getString("evalVariants") == "on"
        // バリアントの場合は、元の質問で記憶したばかりのルートを使用する必要があり、質問を次々と実行することしかできません。
        require(!variants || parallel == 1) { "evalVariants runs serially; drop evalParallel" }
        val scores = mutableListOf<Pair<TaskScore, Observed>>()
        val skipped = mutableListOf<Pair<String, String>>()
        val scoresFile = File(out, "scores.jsonl")
        val runnable = mutableListOf<EvalTask>()
        for (t in tasks) {
            // は機器を変更するか、元に戻せない操作で問題が発生します。レベルは自動的に拒否され、接続されていない場合は実行されません。不可逆型は明示的に解放する必要がある
            if (t.category in GATED && testTasks.evalHooks.autoDecideConfirmation == null) {
                skipped += t.id to "評価の自動拒否が未設定のため ${t.category.wire()} のタスクをスキップしました"
                continue
            }
            if (t.category == EvalCategory.Irreversible && !allowIrreversible) {
                skipped += t.id to "取り消せない操作のタスクは既定でスキップします（実行には evalAllowIrreversible=true が必要です）"
                continue
            }
            val unmet = t.requires.map(probes::requirementMet).filter { !it.first }.map { it.second }
            if (unmet.isNotEmpty()) {
                skipped += t.id to unmet.joinToString(";")
                continue
            }
            if (variants && t.variants.isEmpty()) {
                skipped += t.id to "リプレイ評価はバリエーションのあるタスクだけを実行します"
                continue
            }
            runnable += t
        }
        if (variants) {
            val originals = mutableListOf<Pair<TaskScore, Observed>>()
            for (t in runnable) {
                val (task, observed) = runOne(t, run, probes, runs, File(out, t.id))
                originals += Scoring.score(task, observed) to observed
                scoresFile.appendText(scoreLine(originals.last().first, observed) + "\n")
                for (v in EvalTasks.variantsOf(t)) {
                    val (vt, vo) = runOne(v, run, probes, runs, File(out, v.id.replace('~', '_')))
                    val score = Scoring.score(vt, vo)
                    scores += score to vo
                    scoresFile.appendText(scoreLine(score, vo) + "\n")
                }
            }
            File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), originals = originals))
            File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
            return
        }
        val t0 = System.currentTimeMillis()
        for (group in EvalPairing.groups(runnable, parallel)) {
            val results = if (parallel == 1) listOf(runOne(group.single(), run, probes, runs, File(out, group.single().id), sessionConversation(group.single())))
                else runGroup(group, run, probes, runs, out)
            for ((task, observed) in results) {
                val score = Scoring.score(task, observed)
                scores += score to observed
                scoresFile.appendText(scoreLine(score, observed) + "\n")
            }
        }
        val total = if (parallel > 1) System.currentTimeMillis() - t0 else null
        File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), total, parallel))
        File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
        // 習得したスキルの使用回数（比較レポート用）
        val skillUse = scores.flatMap { it.second.skillsUsed }.groupingBy { it }.eachCount()
        File(out, "skill-use.json").writeText(JsonObject(skillUse.mapValues { JsonPrimitive(it.value) }).toString())
    }

    /** は一連の質問を同時に実行します (並列評価)。質問ごとにダイアログを開き、最後までそれぞれのタスク オブジェクトに従ってください。 */
    private fun runGroup(group: List<EvalTask>, run: String, probes: EvalProbes, runs: RunStore, out: File): List<Pair<EvalTask, Observed>> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val prepared = group.map { t ->
            val before = mutableMapOf<String, String>()
            var bad: String? = null
            for ((name, probe) in t.probesBefore) {
                val v = runCatching { probes.run(probe) }.getOrNull()
                if (v.isNullOrBlank() || v == "null") { bad = "プローブ $probe の結果を読み取れません"; break }
                before[name] = v
            }
            if (bad != null) t to bad else EvalTasks.substitute(t, run, before) to null
        }
        val tasks = prepared.filter { it.second == null }.map { it.first }
        testTasks.evalHooks.autoAnswer = { prompt, _ -> tasks.firstNotNullOfOrNull { t -> t.answers.entries.firstOrNull { it.key in prompt }?.value } }
        testTasks.evalHooks.maxSteps = tasks.mapNotNull { it.maxSteps }.maxOrNull()
        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return group.map { it to harness("前のグループのタスクが終了していません") }

        class Live(val task: EvalTask, val conversation: String, var run: TaskRun? = null, var t0: Long = 0, var tookOver: Boolean = false,
                   var timedOut: Boolean = false, var end: TaskState? = null, var wallMs: Long = 0, var failed: String? = null)
        val live = tasks.map { Live(it, UUID.randomUUID().toString()) }
        for (l in live) {
            l.t0 = System.currentTimeMillis()
            TaskService.start(context, l.task.goal, l.conversation)
            if (!waitFor(START_WAIT_MS) { DeviceTaskState.run(l.conversation) != null }) l.failed = "タスクが開始されませんでした" else l.run = DeviceTaskState.run(l.conversation)
        }
        while (live.any { it.run != null && it.end == null && it.failed == null }) {
            for (l in live) {
                val r = l.run ?: continue
                if (l.end != null || l.failed != null) continue
                val s = r.state.value
                if (s is TaskState.WaitingTakeover && !l.tookOver) {
                    l.tookOver = true
                    if (testTasks.evalHooks.onTakeover == "cancel") r.cancel()
                }
                val timeoutMs = (l.task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
                val elapsed = System.currentTimeMillis() - l.t0
                if (!l.timedOut && elapsed > timeoutMs) { l.timedOut = true; r.cancel() }
                if (l.timedOut && elapsed > timeoutMs + BUSY_WAIT_MS) l.failed = "タイムアウトでキャンセルした後もタスクが終了しません"
                // は、最後の仕上げが完了した後 (走行記録がオフになった後) にのみ登録オフィスから削除されました。
                if (DeviceTaskState.run(l.conversation) == null) {
                    l.end = r.state.value
                    l.wallMs = System.currentTimeMillis() - l.t0
                }
            }
            Thread.sleep(POLL_MS)
        }
        val results = prepared.filter { it.second != null }.map { (t, why) -> t to harness(why!!) }.toMutableList()
        for (l in live) {
            if (l.failed != null) { results += l.task to harness(l.failed!!); continue }
            val record = l.run?.runId?.value?.let { id -> runs.list().firstOrNull { it.id == id } }
            val events = record?.let { runs.events(it.id) }.orEmpty()
            record?.let { runs.copyTo(it.id, File(File(out, l.task.id), "run")) }
            val task = resolveApps(l.task, events, probes)
            val observed = observe(task, l.end!!, events, record != null, l.timedOut, l.tookOver, l.wallMs, probes, record?.id.orEmpty())
            // が失敗し、途中でアプリを占有している別の質問に遭遇しました: 単一の列が互いに干渉しています
            val interfered = observed.outcome != "succeeded" && events.any { it is RunEvent.ToolResult && "app_in_use_by_other_task" in it.resultJson }
            results += task to if (interfered) observed.copy(failReason = Scoring.INTERFERENCE_PREFIX + "同じグループの別タスクが対象のアプリを使用しています") else observed
        }
        return results
    }

    /** 同じセッション内の質問はダイアログを共有します (記憶質問: 2 番目の質問は最初の質問のダイアログで継続されます)。 */
    private val sessions = mutableMapOf<String, String>()
    private fun sessionConversation(t: EvalTask): String? = t.session?.let { sessions.getOrPut(it) { UUID.randomUUID().toString() } }

    /**
     * 2回の評価の比較としきい値: `-e evalCompareBase <基準実行名> -e evalCompareTreated <スキル使用時の実行名>`、
     * 2回の実行のscores.jsonlとスキル付き時のskill-use.jsonを読み込み、スキル付き時のディレクトリにcompare.mdを書き込みます。
     */
    @Test
    fun compareRuns() {
        val base = args.getString("evalCompareBase")
        val treated = args.getString("evalCompareTreated")
        assumeTrue(base != null && treated != null)
        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        fun read(run: String): List<Pair<TaskScore, Observed>> = File(ext, "eval/$run/scores.jsonl").readLines().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line) as JsonObject
            fun b(k: String) = (o[k] as? JsonPrimitive)?.content == "true"
            fun l(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            TaskScore((o["id"] as JsonPrimitive).content, b("passed"), b("falseSuccess"), b("unverified"), emptyList(), null) to
                Observed((o["outcome"] as? JsonPrimitive)?.content.orEmpty(), "", emptyList(), null, emptyMap(), emptyMap(), l("steps").toInt(), l("wallMs"), 0, 0, 0, null)
        }
        val b = read(base!!)
        val t = read(treated!!)
        val use = runCatching { (Json.parseToJsonElement(File(ext, "eval/$treated/skill-use.json").readText()) as JsonObject).mapValues { (it.value as JsonPrimitive).content.toInt() } }
            .getOrDefault(emptyMap())
        File(ext, "eval/$treated/compare.md").writeText(
            "# 比較 $base → $treated\n\n" + EvalReport.compare(b.map { it.first }, t.map { it.first }, use, b.map { it.second }, t.map { it.second }),
        )
    }

    /** 質問を実行し、プレースホルダーを置き換えて観察された事実を含む質問を返します。 [conversation] が空でない場合は、この会話の実行を続行します。 */
    private fun runOne(t: EvalTask, run: String, probes: EvalProbes, runs: RunStore, dir: File, conversation: String? = null): Pair<EvalTask, Observed> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val before = mutableMapOf<String, String>()
        for ((name, probe) in t.probesBefore) {
            val v = runCatching { probes.run(probe) }.getOrNull()
            if (v.isNullOrBlank() || v == "null") return t to harness("プローブ $probe の結果を読み取れません")
            before[name] = v
        }
        val task = EvalTasks.substitute(t, run, before)
        testTasks.evalHooks.autoAnswer = { prompt, _ -> task.answers.entries.firstOrNull { it.key in prompt }?.value }
        testTasks.evalHooks.maxSteps = task.maxSteps

        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return task to harness("前のタスクが終了していません")
        val prevConversation = DeviceTaskState.conversationId.value
        val t0 = System.currentTimeMillis()
        TaskService.start(context, task.goal, conversation)
        // 新しい会話が開かれると、番号が変わり、リクエストが受け入れられたことを示します。次に同じ会話が実行されると、サービスはビジー状態になります。
        val started = if (conversation == null) waitFor(START_WAIT_MS) { DeviceTaskState.conversationId.value != prevConversation }
            else waitFor(START_WAIT_MS) { DeviceTaskState.busy.value && DeviceTaskState.conversationId.value == conversation }
        if (!started) return task to harness("タスクが開始されませんでした")
        val timeoutMs = (task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
        var tookOver = false
        var timedOut = false
        // ビジーは、ステータスが終了に変化し、実行記録がオフになった後、false に戻ります。
        while (DeviceTaskState.busy.value) {
            val s = DeviceTaskState.state.value
            if (s is TaskState.WaitingTakeover && !tookOver) {
                tookOver = true
                if (testTasks.evalHooks.onTakeover == "cancel") TaskService.cancel(context)
            }
            if (!timedOut && System.currentTimeMillis() - t0 > timeoutMs) {
                timedOut = true
                TaskService.cancel(context)
            }
            if (timedOut && System.currentTimeMillis() - t0 > timeoutMs + BUSY_WAIT_MS) {
                return task to harness("タイムアウトでキャンセルした後もタスクが終了しません")
            }
            Thread.sleep(POLL_MS)
        }
        val wallMs = System.currentTimeMillis() - t0
        val end = DeviceTaskState.state.value

        // 解答提出後、エンディング（バックエンド）で再度判定が行われます。走行記録に記録されるまで待ってから解答してください。それが失敗すれば、未知の部分が残ります。待てない場合は審査されません。
        if (end is TaskState.Succeeded) waitFor(JUDGE_WAIT_MS) {
            runs.list().firstOrNull { it.startedAtMs >= t0 }?.judged != null
        }
        val record = runs.list().firstOrNull { it.startedAtMs >= t0 }
        val events = record?.let { runs.events(it.id) }.orEmpty()
        record?.let { runs.copyTo(it.id, File(dir, "run")) }
        val resolved = resolveApps(task, events, probes)
        return resolved to observe(resolved, end, events, record != null, timedOut, tookOver, wallMs, probes, record?.id.orEmpty())
    }

    /** を実行した後、`{app:<能力>}` をこの電話機のパッケージ名に置き換えます。エージェントによって開かれた候補が優先されます ([AppCapabilities.choose] を参照)。 */
    private fun resolveApps(task: EvalTask, events: List<RunEvent>, probes: EvalProbes): EvalTask {
        val launched = RunFacts.appsLaunched(events, recordedTools())
        val written = RunFacts.appsWrittenIn(events, recordedTools())
        val chosen = EvalTasks.appCapabilities(task).mapNotNull { cap ->
            AppCapabilities.choose(probes.appCandidates(cap), launched, context.packageName, written)?.let { cap to it }
        }.toMap()
        return EvalTasks.resolveApps(task, chosen)
    }

    /** 質問後: エンディング、走行記録、終了後の調査に従って観察された事実を整理します。 */
    private fun observe(
        task: EvalTask, end: TaskState, events: List<RunEvent>, hasRecord: Boolean, timedOut: Boolean, tookOver: Boolean,
        wallMs: Long, probes: EvalProbes, runId: String,
    ): Observed {
        val confirmations = events.filterIsInstance<RunEvent.Confirmation>()
        // はレベルで停止しました: 最後の宣言が拒否され (レビューで自動的に)、その後エージェント レポートが終了しました
        val stoppedAtGate = RunFacts.stoppedAtGate(events)
        val outcome = when {
            timedOut -> "timeout"
            stoppedAtGate -> "confirmation"
            // 電話なしの返信が答えです。検証されていない場合でも成功としてカウントされます (検証項目については答えと帳簿を参照してください)。検証済みでも検証できない場合は未検証としてカウントされます
            end is TaskState.Succeeded -> if (end.doubtful) "unverified" else "succeeded"
            end is TaskState.Failed -> "failed"
            end == TaskState.Cancelled -> if (tookOver) "takeover" else "cancelled"
            else -> "timeout"
        }
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val after = mutableMapOf<String, String>()
        for (c in task.checks) when (c) {
            is Check.ScreenHas -> screenHas[Checks.screenKey(c)] =
                runCatching { runBlocking { probes.screenHas(c.packageName, c.text, c.deepLink) } }.getOrDefault(false)
            is Check.WebHas -> webHas[Checks.webKey(c)] =
                runCatching { runBlocking { probes.webHas(c.url, c.text) } }.getOrDefault(false)
            is Check.ProbeEquals -> after[c.probe] = runCatching { probes.run(c.probe) }.getOrDefault("")
            else -> Unit
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        val sources = RunFacts.evidence(events, recordedTools(), task.goal, answer)
        return Observed(
            outcome = outcome,
            answer = answer,
            // とランタイム検証者は同じ証拠台帳と同じチャレンジメソッドを持っています
            evidence = sources,
            today = java.time.LocalDate.now(),
            // はカードに表示される単語をチェックしています: アクションはカードのタイトルに中国語の表現を使用しています
            confirmation = confirmations.lastOrNull { it.decision != "mismatch" && it.decision != "required" }?.let { c ->
                val action = IrrevAction.entries.firstOrNull { it.name == c.action }?.let(ConfirmationCopy::actionLabel) ?: c.action
                listOfNotNull("action" to action, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap()
            },
            screenHas = screenHas,
            webHas = webHas,
            probes = after,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = wallMs,
            inputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.inputTokens ?: 0).toLong() },
            outputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.outputTokens ?: 0).toLong() },
            cachedTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.cachedTokens ?: 0).toLong() },
            // 失敗理由は失敗原因に基づいて生成され、エージェントのオリジナルの言葉は使用されません。
            leftScope = if (task.apps.isEmpty()) emptyList() else RunFacts.appsSeen(events, recordedTools()) - task.apps.toSet(),
            goal = task.goal,
            verdict = events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict,
            modelCalls = events.count { it is RunEvent.ModelReply },
            modelMs = events.filterIsInstance<RunEvent.ModelReply>().sumOf { it.latencyMs },
            toolMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.durationMs },
            waitMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.waitMs },
            // ルートの最後のステップが一致する場合、そのステップは完了したものとしてカウントされます。一致しないステップがある場合は、返されたものとしてカウントされます。
            replayHits = events.filterIsInstance<RunEvent.Replay>().count { it.matched && it.step == it.total },
            replayHandBacks = events.filterIsInstance<RunEvent.Replay>().count { !it.matched },
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            skillsUsed = events.filterIsInstance<RunEvent.SkillUsed>().map { it.name }.distinct(),
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, recordedTools()),
            judged = events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome,
            metrics = RunMetrics.of(events, runId),
            failReason = when {
                timedOut -> null
                end is TaskState.Failed -> Scoring.failReason(end)
                !hasRecord -> Scoring.HARNESS_PREFIX + "今回の実行記録が見つかりません"
                else -> null
            },
        )
    }

    private fun harness(why: String) = Observed(
        "failed", "", emptyList(), null, emptyMap(), emptyMap(), 0, 0, 0, 0, 0, Scoring.HARNESS_PREFIX + why,
    )

    private fun prices(): Prices? {
        val inP = args.getString("evalPriceIn")?.toDouble() ?: return null
        val outP = args.getString("evalPriceOut")?.toDouble() ?: error("evalPriceOut missing")
        return Prices(inP, outP, args.getString("evalPriceCached")?.toDouble() ?: inP)
    }

    /** テスト パッケージ アセットのすべての .json 質問。 [set] は、ディレクトリ (eval-smoke、read など) または ID プレフィックスです。 */
    private fun loadTasks(set: String?): List<EvalTask> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun walk(path: String): List<String> {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) return if (path.endsWith(".json")) listOf(path) else emptyList()
            return children.sorted().flatMap { walk("$path/$it") }
        }
        // の質問は、カテゴリ ディレクトリ (eval/tasks/<category>/)、特別な質問コレクション、Web ページの質問ディレクトリ、および喫煙に関する質問ディレクトリにのみ配置されます。アセットルートの下には、依存ライブラリによって取り込まれた他の JSON があります。
        val dirs = EvalCategory.entries.map { it.wire() } + SUITE_DIRS + WEB_DIR + SMOKE_DIR
        return dirs.flatMap(::walk)
            .filter { path -> set != null || (SUITE_DIRS + SMOKE_DIR).none { path.startsWith("$it/") } }
            .map { path ->
                val task = runCatching { EvalTasks.parse(assets.open(path).bufferedReader().use { it.readText() }) }
                    .getOrElse { throw IllegalStateException("cannot parse eval task $path", it) }
                path to task
            }
            .filter { (path, task) -> set == null || path.startsWith("$set/") || task.id.startsWith(set) }
            .map { it.second }
    }

    /** タスク ファイルおよびディレクトリで使用されるカテゴリ名。 */
    private fun EvalCategory.wire(): String = when (this) {
        EvalCategory.Read -> "read"
        EvalCategory.Write -> "write"
        EvalCategory.Irreversible -> "irreversible"
        EvalCategory.Impossible -> "impossible"
        EvalCategory.Barrier -> "barrier"
    }

    private fun scoreLine(s: TaskScore, o: Observed): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", s.id)
        put("passed", s.passed)
        put("falseSuccess", s.falseSuccess)
        put("unverified", s.unverified)
        put("failedChecks", JsonArray(s.failedChecks.map(::JsonPrimitive)))
        put("failureClass", s.failureClass?.name)
        put("outcome", o.outcome)
        put("judged", o.judged)
        put("answer", o.answer.take(2000))
        put("failReason", o.failReason)
        put("verdict", o.verdict)
        put("leftScope", JsonArray(s.leftScope.map(::JsonPrimitive)))
        put("steps", o.steps)
        put("wallMs", o.wallMs)
        put("inputTokens", o.inputTokens)
        put("outputTokens", o.outputTokens)
        put("cachedTokens", o.cachedTokens)
        put("modelCalls", o.modelCalls)
        put("modelMs", o.modelMs)
        put("toolMs", o.toolMs)
        put("waitMs", o.waitMs)
        o.metrics?.let { put("metrics", RunMetricsStore.JSON.encodeToJsonElement(RunMetrics.serializer(), it)) }
    })

    private fun waitFor(timeoutMs: Long, test: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!test()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    companion object {
        private val RUN_NAME = Regex("^[A-Za-z0-9_-]+$")
        private const val SMOKE_DIR = "eval-smoke"
        /** 特別問題セット: 合わせ問題 (3 ラウンド実行)、記憶問題。 evalSet が渡されない場合、実行されません。明示的に選択する必要があります。 */
        private val SUITE_DIRS = listOf("skills", "memory")
        /** Web ページのタイトル ディレクトリ (eval/tasks/web/)、さまざまなカテゴリ。 */
        private const val WEB_DIR = "web"
        private const val DEFAULT_TIMEOUT_SEC = 600
        private const val POLL_MS = 250L
        private const val START_WAIT_MS = 15_000L
        private const val BUSY_WAIT_MS = 60_000L
        /** 結果を決定するモデルが自分自身を呼び出すまでの上限は、2 つの候補モデルごとに 1 回につき 30 秒です。 */
        private const val JUDGE_WAIT_MS = 65_000L
        private val GATED = setOf(EvalCategory.Write, EvalCategory.Irreversible)
    }
}
