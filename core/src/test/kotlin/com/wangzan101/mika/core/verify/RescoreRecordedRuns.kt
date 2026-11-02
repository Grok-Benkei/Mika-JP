package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * オフライン再計算: 現在の [Grounding] を使用して、記録された実行を再確認します (モデルを調整せずに)。ディレクトリが設定されていない場合はスキップします。
 *
 *   MIKA_RESCORE_DIR=/path/to/runs ./gradlew :core:test --tests '*RescoreRecordedRuns*' --rerun
 *
 * は、ディレクトリ内のすべての events.jsonl を再帰的に検索します。各検証 (検証者イベント) ごとに、コアで [RecordedVerifications] の終了 (順序に関係なく) を押して答えを取得します。
 * の証拠では、検証前のすべての画面読み取りテキストとツール結果のすべての文字列 (フィニッシュ自身を除く) が使用され、相対日付は検証の時刻 (取得されたタイムゾーン) に従って変換されます。
 * MIKA_RESCORE_ZONE、このマシンを使用するように設定されていません)。結果は <ディレクトリ>/rescore.tsv に書き込まれます: 実行、その時点の結論と理由、現在の結論、根拠のない事実。
 */
class RescoreRecordedRuns {
    @Test fun rescore() {
        val dir = (System.getenv("MIKA_RESCORE_DIR") ?: System.getProperty("mika.rescore.dir"))?.let(::File)
        assumeTrue("MIKA_RESCORE_DIR not set", dir != null && dir.isDirectory)
        val zone = System.getenv("MIKA_RESCORE_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val rows = mutableListOf("run\tthen\tthen_reason\tnow\tnot_found")
        dir!!.walk().filter { it.name == "events.jsonl" }.sorted().forEach { f ->
            val events = f.readLines().mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }
            val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal.orEmpty()
            for ((i, fi) in RecordedVerifications.pairs(events)) {
                val e = events[i] as RunEvent.Verifier
                val answer = RecordedVerifications.answer(events, fi) ?: continue
                val g = Grounding.check(goal, answer, RecordedVerifications.sources(events, i, fi),
                    java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate())
                val now = when (g.found) { true -> "found"; false -> "not_found"; null -> "nothing_checkable" }
                val thenReason = RecordedVerifications.arg(e.detailJson, "reason").orEmpty()
                rows += listOf(f.parentFile.name, e.verdict, thenReason, now, g.unmatched.joinToString(" | ") { "${it.kind}:${it.text}" })
                    .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
            }
        }
        File(dir, "rescore.tsv").writeText(rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
    }
}
