package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.ceil

/** 100 万トークンあたりの単価。実行時に渡され、ウェアハウスには書き込まれません。 */
data class Prices(val inputPerM: Double, val outputPerM: Double, val cachedPerM: Double)

/** は要約マークダウンを実行します。各質問の指標と結論のみが含まれており、回答の全文とスクリーンショットは含まれていません（倉庫に送信されます）。 */
object EvalReport {
    fun markdown(
        run: String,
        model: String,
        scores: List<Pair<TaskScore, Observed>>,
        skipped: List<Pair<String, String>>,
        prices: Prices?,
        /** この操作用のスイッチの組み合わせ ([com.wangzan101.mika.core.agent.SpeedFlags.describe])。 */
        flags: String = "none",
        /** 並列評価中のラウンド全体の最初の質問から最後の質問の終了までの時間。シリアル評価中は空であり、この行には表示されません。 */
        totalWallMs: Long? = null,
        /** 複数の質問を同時に実行します (evalParallel)。 1はシリアルです。 */
        parallel: Int = 1,
        /** は、リプレイ評価 (evalVariants) で最初に実行される元の質問であり、ルートを記録するために使用されます。これは別のセクションとしてリストされており、インジケーターには含まれていません。 */
        originals: List<Pair<TaskScore, Observed>> = emptyList(),
    ): String {
        val n = scores.size
        val passed = scores.count { it.first.passed }
        // 正直に答えて「できなかった（結果が判定された）」と答えた場合は成功とみなされません。
        val reportedSuccess = scores.count { Scoring.recorded(it.second).outcome == "succeeded" }
        val falseSuccess = scores.count { it.first.falseSuccess }
        val unverified = scores.count { it.first.unverified }
        val steps = scores.map { it.second.steps.toDouble() }
        val secs = scores.map { it.second.wallMs / 1000.0 }
        val inT = scores.sumOf { it.second.inputTokens }
        val outT = scores.sumOf { it.second.outputTokens }
        val cachedT = scores.sumOf { it.second.cachedTokens }
        val calls = scores.map { it.second.modelCalls.toDouble() }
        val wallT = scores.sumOf { it.second.wallMs }

        val sb = StringBuilder()
        sb.appendLine("# 評価の集計 $run")
        sb.appendLine()
        sb.appendLine("モデル:$model、設定:$flags、実行 $n 件、スキップ ${skipped.size} 件" + (if (parallel > 1) "、同時実行 $parallel 件。" else "。"))
        sb.appendLine()
        sb.appendLine("## 指標")
        sb.appendLine()
        sb.appendLine("| 指標 | 値 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 達成率 | ${ratio(passed, n)} |")
        if (totalWallMs != null) sb.appendLine("| 合計経過時間（秒） | ${num(totalWallMs / 1000.0)} |")
        sb.appendLine("| 誤った成功報告の割合（成功報告に対して） | ${ratio(falseSuccess, reportedSuccess)} |")
        sb.appendLine("| 誤った成功報告の割合（全件に対して） | ${ratio(falseSuccess, n)} |")
        sb.appendLine("| 未確認の成功 | $unverified |")
        sb.appendLine("| 未確認かつ不合格 | ${scores.count { it.first.failureClass == FailureClass.unverified_wrong }} |")
        // は画面などに表示されるログインウォールにぶつかります。ユーザーに引き渡されます: 単一カラム、成功とみなされません (判断は人次第です)
        scores.mapNotNull { it.first.handedOver }.groupingBy { it }.eachCount().toSortedMap()
            .forEach { (kind, c) -> sb.appendLine("| ユーザーへ引き継ぎ:$kind（達成に含めません） | $c |") }
        sb.appendLine("| ステップ数の中央値 | ${num(median(steps))} |")
        sb.appendLine("| ステップ数 P90 | ${num(p90(steps))} |")
        sb.appendLine("| 所要時間の中央値（秒） | ${num(median(secs))} |")
        sb.appendLine("| 所要時間 P90（秒） | ${num(p90(secs))} |")
        sb.appendLine("| モデル呼び出し回数の中央値 | ${num(median(calls))} |")
        sb.appendLine("| モデル呼び出し回数 P90 | ${num(p90(calls))} |")
        sb.appendLine("| モデルの待機時間の割合 | ${percent(scores.sumOf { it.second.modelMs }, wallT)} |")
        sb.appendLine("| ツール実行時間の割合 | ${percent(scores.sumOf { it.second.toolMs }, wallT)} |")
        val waitT = scores.sumOf { it.second.waitMs }
        // およびその他のインターフェイスの時間は、ツールによって報告される必要があります。時間がない場合は測定されていないことになりますので0%と書かないと
        sb.appendLine("| うち画面待機の割合 | ${if (waitT == 0L) "（データなし）" else percent(waitT, wallT)} |")
        // モデルとツールに加えて、ユーザーの応答または同意の待機、バックオフの再試行、インターフェイスのオーバーヘッド。 3 つの項目を合計すると 100% になります
        val otherT = (wallT - scores.sumOf { it.second.modelMs } - scores.sumOf { it.second.toolMs }).coerceAtLeast(0)
        sb.appendLine("| その他・ユーザー待機の割合 | ${percent(otherT, wallT)} |")
        sb.appendLine("| 手順再実行の適合 | ${scores.sumOf { it.second.replayHits }} |")
        sb.appendLine("| 手順再実行からモデルへ移行 | ${scores.sumOf { it.second.replayHandBacks }} |")
        if (scores.any { it.second.replayHits + it.second.replayHandBacks > 0 }) {
            val handedBack = scores.filter { it.second.replayHandBacks > 0 }
            sb.appendLine("| 手順再実行の適合率（最後まで実行したタスク） | ${ratio(scores.count { it.second.replayHits > 0 }, n)} |")
            sb.appendLine("| 手順再実行からモデルへ戻した割合 | ${ratio(handedBack.size, n)} |")
            sb.appendLine("| モデルへ戻した後の達成率 | ${ratio(handedBack.count { it.first.passed }, handedBack.size)} |")
        }
        sb.appendLine("| 入力トークン | $inT |")
        sb.appendLine("| うちキャッシュ済みトークン | $cachedT |")
        sb.appendLine("| 出力トークン | $outT |")
        sb.appendLine("| キャッシュ利用率（cached/input） | ${percent(cachedT, inT)} |")
        val perTask = scores.filter { it.second.inputTokens > 0 }.map { it.second.cachedTokens * 100.0 / it.second.inputTokens }
        sb.appendLine("| タスクごとのキャッシュ利用率の中央値 | ${if (perTask.isEmpty()) "—" else num(median(perTask)) + "%"} |")
        if (prices != null) {
            // 入力トークン 含缓存部分,缓存部分按缓存单价计
            val cost = ((inT - cachedT) * prices.inputPerM + cachedT * prices.cachedPerM + outT * prices.outputPerM) / 1_000_000
            sb.appendLine("| 費用 | ${num(cost)} |")
        }
        sb.appendLine()

        metricsSection(sb, scores)

        sb.appendLine("## 種類別の件数")
        sb.appendLine()
        val byClass = scores.mapNotNull { it.first.failureClass }.groupingBy { it }.eachCount()
        if (byClass.isEmpty()) sb.appendLine("失敗はありません。")
        else {
            sb.appendLine("| 種類 | 件数 |")
            sb.appendLine("|---|---|")
            FailureClass.entries.filter { it in byClass }.forEach { sb.appendLine("| ${it.name} | ${byClass.getValue(it)} |") }
        }
        sb.appendLine()

        sb.appendLine("## タスク別の結果")
        sb.appendLine()
        sb.appendLine("| id | 結果 | 合格 | 種類 | 検証 | ステップ数 | 秒数 | 入力トークン | キャッシュ利用率 | 出力トークン |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        scores.forEach { (s, o) ->
            // は答えを渡して判定を完了できませんでした: 判定の結末を書きます
            val judged = o.judged?.takeIf { Scoring.recorded(o) !== o }?.let { "（判定:$it）" }.orEmpty()
            sb.appendLine("| ${s.id} | ${o.outcome}$judged${s.handedOver?.let { "（ユーザーへ引き継ぎ:$it）" }.orEmpty()} | ${if (s.passed) "はい" else "いいえ"} | ${s.failureClass?.name ?: ""} | ${o.verdict ?: ""} | ${o.steps} | " +
                "${num(o.wallMs / 1000.0)} | ${o.inputTokens} | ${percent(o.cachedTokens, o.inputTokens)} | ${o.outputTokens} |")
        }
        sb.appendLine()

        if (originals.isNotEmpty()) {
            sb.appendLine("## 元のタスク（手順記録用。上記の指標には含めません）")
            sb.appendLine()
            sb.appendLine("| id | 結果 | 合格 | ステップ数 | 秒数 |")
            sb.appendLine("|---|---|---|---|---|")
            originals.forEach { (s, o) ->
                sb.appendLine("| ${s.id} | ${o.outcome}${s.handedOver?.let { "（ユーザーへ引き継ぎ:$it）" }.orEmpty()} | ${if (s.passed) "はい" else "いいえ"} | ${o.steps} | ${num(o.wallMs / 1000.0)} |")
            }
            sb.appendLine()
        }

        sb.appendLine("## スキップ")
        sb.appendLine()
        if (skipped.isEmpty()) sb.appendLine("ありません。") else skipped.forEach { (id, why) -> sb.appendLine("- $id:$why") }
        sb.appendLine()

        sb.appendLine("## タスクの対象外で開いたアプリ（記録のみ。失敗に含めません）")
        sb.appendLine()
        val drift = scores.filter { it.first.leftScope.isNotEmpty() }
        if (drift.isEmpty()) sb.appendLine("ありません。") else drift.forEach { (s, _) -> sb.appendLine("- ${s.id}:${s.leftScope.joinToString("、")}") }
        sb.appendLine()

        sb.appendLine("## 残ったテストデータのマーカー（手動削除が必要）")
        sb.appendLine()
        val markers = (originals + scores).mapNotNull { (s, _) -> s.marker?.let { s.id to it } }
        if (markers.isEmpty()) sb.appendLine("ありません。") else markers.forEach { (id, m) -> sb.appendLine("- $id:$m") }
        return sb.toString()
    }

    /**
     * 「実行概要」セクション: 実行記録のある質問は、[RunMetrics] に従って要約されます (最初のフィードバック、ラウンドトリップ数、時間方向。バッチ内のサブステップは繰り返しカウントされません)。
     * 次に、各質問に 1 行ずつ答えます。このセクションは、録音された質問がない限り表示されません。
     */
    private fun metricsSection(sb: StringBuilder, scores: List<Pair<TaskScore, Observed>>) {
        val rows = scores.mapNotNull { (s, o) -> o.metrics?.let { s.id to it } }
        if (rows.isEmpty()) return
        val st = RunStats.of(rows.map { it.second })
        sb.appendLine("## 実行の要約")
        sb.appendLine()
        sb.appendLine("実行記録から集計しました（${rows.size}件）。時間はMikaの処理開始から計測し、読み取りと操作は実行時の分類に従います。バッチ内のサブステップは重複して数えません。")
        sb.appendLine()
        sb.appendLine("| 指標 | 値 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 所要時間の中央値（秒） | ${num(st.medianTotalMs / 1000.0)} |")
        sb.appendLine("| 最初の応答までの中央値（秒） | ${st.medianFirstFeedbackMs?.let { num(it / 1000.0) } ?: "—"} |")
        sb.appendLine("| モデルとの平均往復回数 | ${num(Math.round(st.avgModelCalls * 10) / 10.0)} |")
        sb.appendLine("| モデル処理時間の割合 | ${percent(st.modelMs, st.totalMs)} |")
        sb.appendLine("| 画面読み取り時間の割合 | ${percent(st.observeMs, st.totalMs)} |")
        sb.appendLine("| 操作時間の割合 | ${percent(st.actMs, st.totalMs)} |")
        sb.appendLine("| その他（待機・再試行）の割合 | ${percent(st.otherMs, st.totalMs)} |")
        sb.appendLine()
        sb.appendLine("| id | 秒数 | 最初の応答（秒） | 往復 | 全リクエスト | モデル（秒） | 読み取り（秒） | 操作（秒） | その他（秒） | 読み取り / 操作回数 | トークン |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        rows.forEach { (id, m) ->
            sb.appendLine("| $id | ${secs(m.totalMs)} | ${m.firstFeedbackMs?.let(::secs) ?: "—"} | ${m.modelCalls} | ${m.allCalls ?: "—"} | " +
                "${secs(m.modelMs)} | ${secs(m.observeMs)} | ${secs(m.actMs)} | ${secs(m.otherMs)} | ${m.observeCalls} / ${m.actCalls} | ${m.tokens} |")
        }
        sb.appendLine()
    }

    /** 各質問の実行概要、JSON 配列 (評価結果ディレクトリ内の metrics.json)、各項目は `id` および `metrics` です。実行記録のない質問はリストされません。 */
    fun metricsJson(scores: List<Pair<TaskScore, Observed>>): String = JsonArray(scores.mapNotNull { (s, o) ->
        o.metrics?.let { m -> JsonObject(mapOf("id" to JsonPrimitive(s.id), "metrics" to METRICS_JSON.encodeToJsonElement(RunMetrics.serializer(), m))) }
    }).toString()

    private val METRICS_JSON = Json { encodeDefaults = true }

    private fun secs(ms: Long) = num(Math.round(ms / 100.0) / 10.0)

    /** しきい値の結論: [passed] が false の場合、[reasons] には基準を満たしていない領域がリストされます。 */
    data class Gate(val passed: Boolean, val reasons: List<String>)

    /**
     * マッチング質問のしきい値: 学習したスキルによるラウンド実行の誤った成功 ([treated]) は 0 でなければなりません。
     * であり、完了した数値はベースライン ([baseline]) から 1 を引いた値を下回っていません。
     */
    fun gate(baseline: List<TaskScore>, treated: List<TaskScore>): Gate {
        val reasons = mutableListOf<String>()
        val fs = treated.count { it.falseSuccess }
        if (fs > 0) reasons += "スキル使用時に誤った成功報告が$fs 件ありました(${treated.filter { it.falseSuccess }.joinToString("、") { it.id }})"
        val b = baseline.count { it.passed }
        val t = treated.count { it.passed }
        if (t < b - 1) reasons += "達成数$t は基準の$b より${b - t}件少なくなっています（許容差は1件まで）"
        return Gate(reasons.isEmpty(), reasons)
    }

    /**
     * 基準とスキル使用時の両実行を比較するレポート: 達成率、誤った成功報告の割合、未確認の成功。観測データがある場合は、ステップ数と所要時間の中央値も加える。
     * 学習した各スキルは数回使用されました ([skillUse])。最後は閾値の結論です。
     */
    fun compare(
        baseline: List<TaskScore>,
        treated: List<TaskScore>,
        skillUse: Map<String, Int>,
        baselineObserved: List<Observed> = emptyList(),
        treatedObserved: List<Observed> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.appendLine("## 基準とスキル使用時の比較")
        sb.appendLine()
        sb.appendLine("| 指標 | 基準 | スキル使用時 | 変化 |")
        sb.appendLine("|---|---|---|---|")
        fun row(name: String, f: (List<TaskScore>) -> Int) {
            val b = f(baseline); val t = f(treated)
            sb.appendLine("| $name | $b/${baseline.size} | $t/${treated.size} | ${signed(t - b)} |")
        }
        row("達成") { s -> s.count { it.passed } }
        row("誤った成功報告") { s -> s.count { it.falseSuccess } }
        row("未確認の成功") { s -> s.count { it.unverified } }
        if (baselineObserved.isNotEmpty() && treatedObserved.isNotEmpty()) {
            fun med(o: List<Observed>, f: (Observed) -> Double) = median(o.map(f))
            val bs = med(baselineObserved) { it.steps.toDouble() }; val ts = med(treatedObserved) { it.steps.toDouble() }
            sb.appendLine("| ステップ数の中央値 | ${num(bs)} | ${num(ts)} | ${signedNum(ts - bs)} |")
            val bt = med(baselineObserved) { it.wallMs / 1000.0 }; val tt = med(treatedObserved) { it.wallMs / 1000.0 }
            sb.appendLine("| 所要時間の中央値（秒） | ${num(bt)} | ${num(tt)} | ${signedNum(tt - bt)} |")
            // 質問番号に応じて投与量が一致します。両方のラウンドで不合格だった質問のみが比較され、各質問の中央値が取得されます（2 ラウンドの質問数が異なる場合、合計は比較できません）
            val bById = baseline.map { it.id }.zip(baselineObserved).toMap()
            val pairs = treated.map { it.id }.zip(treatedObserved).mapNotNull { (id, t) -> bById[id]?.let { it to t } }
            if (pairs.isNotEmpty()) {
                fun row(name: String, f: (Observed) -> Double) {
                    val b = median(pairs.map { f(it.first) }); val t = median(pairs.map { f(it.second) })
                    sb.appendLine("| $name（対応する${pairs.size}件の中央値） | ${num(b)} | ${num(t)} | ${signedNum(t - b)} |")
                }
                row("入力トークン") { it.inputTokens.toDouble() }
                row("出力トークン") { it.outputTokens.toDouble() }
                row("キャッシュ利用率 %") { if (it.inputTokens == 0L) 0.0 else Math.round(it.cachedTokens * 1000.0 / it.inputTokens) / 10.0 }
            }
        }
        sb.appendLine()
        sb.appendLine("## 学習したスキルの使用回数")
        sb.appendLine()
        if (skillUse.isEmpty()) sb.appendLine("学習したスキルは使用されませんでした。")
        else skillUse.entries.sortedByDescending { it.value }.forEach { (name, n) -> sb.appendLine("- $name:$n 回") }
        sb.appendLine()
        val g = gate(baseline, treated)
        sb.appendLine("## 合格条件")
        sb.appendLine()
        sb.appendLine(if (g.passed) "合格: スキル使用時に誤った成功報告がなく、達成数が基準から1件を超えて減っていません。" else "不合格:")
        g.reasons.forEach { sb.appendLine("- $it") }
        return sb.toString()
    }

    private fun signed(d: Int) = if (d > 0) "+$d" else "$d"
    private fun signedNum(d: Double) = if (d > 0) "+${num(d)}" else num(d)

    internal fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** 最も近いランク法: ソート後の ceil(0.9n) 番目。 */
    internal fun p90(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[ceil(0.9 * s.size).toInt() - 1]
    }

    private fun ratio(a: Int, b: Int): String =
        if (b == 0) "$a/0(—)" else "$a/$b(${String.format(Locale.ROOT, "%.1f", a * 100.0 / b)}%)"

    private fun percent(a: Long, b: Long): String =
        if (b == 0L) "—" else String.format(Locale.ROOT, "%.1f%%", a * 100.0 / b)

    private fun num(x: Double): String =
        if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.')
}
