package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import com.wangzan101.mika.core.verify.RecordedVerifications
import com.wangzan101.mika.core.verify.Verdict
import com.wangzan101.mika.core.verify.Grounding
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * オフラインでの再スコアリングとプルバック評価の実行: 現在の質問、検証ルール、検証を使用して、モデルを調整したり、携帯電話に接続したりせずに再計算します。ディレクトリが設定されていない場合はスキップします。
 *
 *   MIKA_EVAL_RUN=/path/to/<runname> ./gradlew :core:test --tests '*RescoreEvalRun*' --rerun
 *
 * ディレクトリには、scores.jsonl および <question id>/run/events.jsonl (評価者によって書き込まれたとおり) が存在する必要があります。各質問:
 * - 終了 (現在の終了方法によるメイン番号、[natural] を参照): 最初の最終応答 (電話をかけずに言葉で返信、古いレコードでは終了) でタスクが終了します。
 *   リスクに応じたチェック: 回答の値がその時点の帳簿と一致しないか、外部の操作を実行し、その時点で検証に合格しませんでした。実行時と同様に一度返され、次の最終回答が取得されます。
 *   が正しくない場合は未検証とみなされ (未確認としてマークされて渡されます)、正しい場合は成功となります。 1本目は最終回答前に諦めてユーザーに引き渡し、いつものように止められました。
 *   古いレコードのリマインダー（ツールが調整されておらずリマインダーが失敗した）と最後に戻った検証失敗が上記のエンディングに置き換えられました。
 * - 比較: 古いルールの結果 (最後の検証のみを確認し、現在の [Verifier.backstop] 再チェックを使用) と、それが合格したかどうか。
 * - チェック項目: 解答、画面読み取り、障害物、質問、記憶が走行記録に基づいて再計算されます。携帯電話でチェックされる現在の結果 (screen_has、web_has、プローブ、確認カード) と期待値は、実行前プローブの現在の結果から取得されます。
 * 証拠は、レポートの主要番号である証拠台帳 ([RecordedVerifications.ledgerSources]、ランタイム検証者と同じ選択方法) に従って再構築されます。
 * の回答の Answer_grounded チェックでも、同じ元帳 ([RunFacts.evidence]) が使用されます。
 * 回答を提出した後の判定の結果 (結果イベント、[com.wangzan101.mika.core.agent.OutcomeJudge] を参照) は、レコード ([Scoring.recorded]) に従って計算されます。
 * まだ完了していないか、部分的にしか完了していないと正直に述べた回答は、正直に諦めたとみなされます。判断のみを認める解答は同じ解答です（現在の終了方法に合わせて解答を変更した場合は使用されません）。古いレコードにはこのイベントはありません。
 * 相対日付は、検証の時刻に従って変換されます (タイム ゾーンは MIKA_RESCORE_ZONE で、ローカル タイム ゾーンは使用されません)。引き継ぎのためにユーザーに渡された単一の列は、渡されたものとしてカウントされません。結果は <ディレクトリ>/rescore.md に書き込まれます。
 */
class RescoreEvalRun {
    private val tasksDir = listOf("eval/tasks", "../eval/tasks").map(::File).first { it.isDirectory }

    @Test fun rescore() {
        val dir = (System.getenv("MIKA_EVAL_RUN") ?: System.getProperty("mika.eval.run"))?.let(::File)
        assumeTrue("MIKA_EVAL_RUN not set", dir != null && File(dir, "scores.jsonl").isFile)
        val zone = System.getenv("MIKA_RESCORE_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val tasks = tasksDir.walkTopDown().filter { it.isFile && it.extension == "json" }.map { EvalTasks.parse(it.readText()) }.associateBy { it.id }
        val rows = mutableListOf("| id | 结局 当时→现在 | 判的结局 | 通过 当时→现在 | 交给用户 | 没过的核对项 | 未确认 | 旧规则(核查兜底)时 |", "|---|---|---|---|---|---|---|---|")
        var before = 0
        var live = 0
        var legacyPassed = 0
        var nudged = 0
        var total = 0
        var falseBefore = 0
        var falseNow = 0
        var unverifiedWrong = 0
        var judgedNotDone = 0
        val handed = mutableMapOf<String, Int>()
        File(dir, "scores.jsonl").readLines().filter { it.isNotBlank() }.forEach { line ->
            val old = Json.parseToJsonElement(line) as JsonObject
            val id = old.str("id") ?: return@forEach
            val task = tasks[id] ?: return@forEach
            val events = File(dir, "$id/run/events.jsonl").takeIf { it.isFile }?.readLines()
                ?.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }.orEmpty()
            val oldOutcome = old.str("outcome").orEmpty()
            val oldFailed = (old["failedChecks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            fun score(outcome: String, answer: String? = null, upto: Int = events.size, judged: String? = null): TaskScore {
                val observed = observed(task, events.subList(0, upto), old, outcome, oldFailed, zone, answer).copy(judged = judged)
                val s = Scoring.score(task, observed)
                // 期待値は、オフラインでは計算できない実行前プローブ({probe:...})のチェック項目から取得され、その時点の結果が使用されます。
                val failed = s.failedChecks.filterNot { k ->
                    val i = k.substringBefore(':').toIntOrNull() ?: return@filterNot false
                    "{probe:" in task.checks[i].toString() && k !in oldFailed
                }
                return s.copy(passed = failed.isEmpty(), failedChecks = failed)
            }
            val n = natural(task, events, oldOutcome, zone)
            // 判決は渡された回答に基づいて行われます。現在の終了方法に従って答えを変更すると、その答えは使用されません。
            val delivered = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason
            val judged = RunOutcome.recorded(events)?.wire?.takeIf { n == null || n.answer.trim() == delivered?.trim() }
            val score = n?.let { score(it.outcome, answer = it.answer, upto = it.upto, judged = judged) } ?: score(oldOutcome, judged = judged)
            val nowOutcome = n?.outcome ?: oldOutcome
            val oldRule = recheck(task, events, oldOutcome, zone)
            val legacy = score(oldRule)
            val wasPassed = old.str("passed") == "true"
            total++
            if (wasPassed) before++
            if (score.passed) live++
            if (legacy.passed) legacyPassed++
            if (n?.nudged == true) nudged++
            if (old.str("falseSuccess") == "true") falseBefore++
            if (score.falseSuccess) falseNow++
            if (score.failureClass == FailureClass.unverified_wrong) unverifiedWrong++
            val notDone = judged == RunOutcome.Partial.wire || judged == RunOutcome.NotPossible.wire
            if (notDone) judgedNotDone++
            score.handedOver?.let { handed[it] = (handed[it] ?: 0) + 1 }
            val mark = if (wasPassed != score.passed || oldOutcome != nowOutcome || notDone || score.handedOver != null) " **" else ""
            val legacyNote = if (legacy.passed != score.passed || oldRule != nowOutcome) "$oldRule,通过 ${yes(legacy.passed)}" else ""
            rows += "| $id$mark | $oldOutcome → $nowOutcome | ${judged.orEmpty()} | ${yes(wasPassed)} → ${yes(score.passed)} | ${score.handedOver.orEmpty()} | " +
                "${score.failedChecks.joinToString(", ")} | ${n?.unconfirmed.orEmpty().joinToString("、")} | $legacyNote |"
        }
        val report = "# 重新打分 ${dir!!.name}(自然结束:不带调用的回复就是回答)\n\n" +
            "通过:当时 $before/$total → 现在 $live/$total\n\n" +
            "对比:旧规则(只看最后一次核查,现在的兜底重核)$legacyPassed/$total\n\n" +
            "退回过一次的:$nudged\n\n" +
            "假成功:当时 $falseBefore → 现在 $falseNow(判下来没办成、按如实放弃算的 $judgedNotDone);未核实且没过:$unverifiedWrong\n\n" +
            "交给用户(不算通过):" + (handed.entries.joinToString(",") { "${it.key} ${it.value}" }.ifEmpty { "无" }) + "\n\n" +
            rows.joinToString("\n") + "\n"
        File(dir, "rescore.md").writeText(report)
        println(report)
    }

    private fun yes(b: Boolean) = if (b) "是" else "否"

    /** 現在の終了方法に従って再計算された終了: [answer] は渡された答え、[upto] はその後のイベントの添字 (後続のイベントは発生しません)、[unconfirmed] は未確認としてマークされたいくつかの場所です。 */
    private class Natural(val outcome: String, val answer: String, val upto: Int, val unconfirmed: List<String>, val nudged: Boolean)

    /**
     * 最初の最終回答によりタスクが終了します (クラスの説明を参照)。以前に他の方法で終了したことがある場合 (放棄、引き継ぎ、停止、タイムアウト)、または最終的な答えがまったくない場合は、null が返され、終了は同じになります。
     * それまでの台帳 ([Grounding.check]) に従って各回答を検証します。外部対応 ([RunFacts.actedOutward]) を実行するときは、同じステップで記録された検証結果を参照してください。
     */
    private fun natural(task: EvalTask, events: List<RunEvent>, oldOutcome: String, zone: ZoneId): Natural? {
        if (oldOutcome == "timeout" || oldOutcome == "cancelled" || oldOutcome == "skipped") return null
        val finals = events.indices.filter { RunFacts.isFinalAnswer(events[it]) }
        if (finals.isEmpty()) return null
        // ミッションは最初の答えの前に別の方法で終了しました (引き継いでレベルが停止したことを確認することはまだ後の問題としてカウントされます): いつものように
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val pairs = RecordedVerifications.pairs(events).associate { (v, f) -> f to (events[v] as RunEvent.Verifier) }
        fun check(f: Int): List<String> {
            val r = events[f] as RunEvent.ModelReply
            val answer = RecordedVerifications.answer(events, f) ?: return emptyList()
            val seen = events.subList(0, f)
            val ledger = RunFacts.ledger(seen, RECORDED_TOOLS)
            val today = java.time.Instant.ofEpochMilli(r.atMs).atZone(zone).toLocalDate()
            val gaps = Grounding.check(goal, answer, ledger.forAnswer(goal, answer).map { it.text }, today).unmatched.map { it.text }
            val outward = RunFacts.actedOutward(seen)
            val verdict = pairs[f]?.verdict
            return when {
                gaps.isNotEmpty() -> gaps.distinct()
                outward && verdict != null && verdict != Verdict.Pass.wire -> listOf(RecordedVerifications.arg(pairs[f]!!.detailJson, "reason").orEmpty().ifBlank { verdict })
                else -> emptyList()
            }
        }
        val first = finals.first()
        val missing = check(first)
        if (missing.isEmpty()) return Natural("succeeded", RecordedVerifications.answer(events, first).orEmpty(), first + 1, emptyList(), nudged = false)
        // 一度戻ります: 最終回答を書き留めます。それ以上ない場合は、最初のものを未確認としてマークし、それを渡します。
        val second = finals.getOrNull(1) ?: return Natural("unverified", RecordedVerifications.answer(events, first).orEmpty(), first + 1, missing, nudged = false)
        val again = check(second)
        return Natural(if (again.isEmpty()) "succeeded" else "unverified", RecordedVerifications.answer(events, second).orEmpty(), second + 1, again, nudged = true)
    }

    /** 古いルール (比較のみ): 最後の検証が最終的な答えになります。その時点で修正された（または手放された）人は、現在の検証に従って再チェックされます。 */
    private fun recheck(task: EvalTask, events: List<RunEvent>, oldOutcome: String, zone: ZoneId): String {
        if (oldOutcome != "succeeded" && oldOutcome != "unverified") return oldOutcome
        val (v, f) = RecordedVerifications.pairs(events).lastOrNull() ?: return oldOutcome
        val e = events[v] as RunEvent.Verifier
        val detail = runCatching { Json.parseToJsonElement(e.detailJson) as JsonObject }.getOrNull() ?: return oldOutcome
        val reason = detail.str("reason").orEmpty()
        // 検証者によって合格と判断された人のみが考慮されます。その時点で合格したか、cannot_verify に変更されました (削除された「同じ場所ではない」ルールを含む)
        val modelPassed = e.verdict == Verdict.Pass.wire || reason == Verifier.NOT_IN_READS || reason == OLD_NOT_ON_SCREEN || reason == Verifier.TRUNCATED || reason == OLD_NOT_ONE_SOURCE
        if (!modelPassed) return oldOutcome
        val asPass = Verifier.parse(JsonObject(detail + ("verdict" to JsonPrimitive("pass"))).toString()) ?: return oldOutcome
        if (asPass.verdict != Verdict.Pass) return oldOutcome
        val answer = RecordedVerifications.answer(events, f) ?: return oldOutcome
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val sources = RecordedVerifications.ledgerSources(events, v, f, goal, answer)
        val today = java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate()
        val now = Verifier.backstop(asPass, goal, answer, sources, imageSent = false, today)
        return if (now.verdict == Verdict.Pass) "succeeded" else "unverified"
    }

    private fun observed(
        task: EvalTask, events: List<RunEvent>, old: JsonObject, outcome: String, oldFailed: List<String>, zone: ZoneId,
        given: String? = null,
    ): Observed {
        fun passedThen(i: Int, type: String) = "$i:$type" !in oldFailed
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val probes = mutableMapOf<String, String>()
        task.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ScreenHas -> screenHas[Checks.screenKey(c)] = passedThen(i, "screen_has")
                is Check.WebHas -> webHas[Checks.webKey(c)] = passedThen(i, "web_has")
                is Check.ProbeEquals -> probes[c.probe] = if (passedThen(i, "probe_equals")) c.expected else ""
                else -> Unit
            }
        }
        val card = task.checks.filterIsInstance<Check.ConfirmationShows>().firstOrNull()
            ?.takeIf { c -> passedThen(task.checks.indexOf(c), "confirmation_shows") }
            ?.let { c -> listOfNotNull(c.action?.let { "action" to it }, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap() }
        val answer = given ?: events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason?.takeIf { outcome == "succeeded" || outcome == "unverified" || outcome == "failed" }
            ?: old.str("answer").orEmpty()
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val ev = RunFacts.evidence(events, RECORDED_TOOLS, goal, answer)
        val end = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.atMs ?: events.lastOrNull()?.atMs
        return Observed(
            outcome = outcome,
            answer = answer,
            evidence = ev,
            today = end?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
            confirmation = card,
            screenHas = screenHas,
            probes = probes,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = old.str("wallMs")?.toLongOrNull() ?: 0,
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            failReason = old.str("failReason"),
            goal = goal,
            webHas = webHas,
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, RECORDED_TOOLS),
        )
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** 古いレコードの検証の理由は、削除された「値が記載内容と同じではない」ルールによって拒否されました。 */
    /** 「根拠が見つからない」を記述変更前にメモ。 */
    private val OLD_NOT_ON_SCREEN = "回答的依据在屏幕、网页或命令输出里都找不到"
    private val OLD_NOT_ONE_SOURCE = "回答里的值和它说的东西不是从同一个页面或 App 读来的"
}
