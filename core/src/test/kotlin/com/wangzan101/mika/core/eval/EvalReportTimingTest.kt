package com.wangzan101.mika.core.eval

import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportTimingTest {
    private fun pair(id: String, calls: Int, input: Long, cached: Long, modelMs: Long, toolMs: Long) =
        TaskScore(id, true, false, false, emptyList(), null) to
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), calls, 10_000, input, 10, cached, null,
                modelCalls = calls, modelMs = modelMs, toolMs = toolMs)

    @Test fun reportsCallMedianAndCacheRatio() {
        val scores = listOf(
            pair("a", 4, 400, 100, 5_000, 2_000),
            pair("b", 6, 300, 50, 5_000, 2_000),
            pair("c", 10, 300, 100, 5_000, 1_000),
        )
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null, flags = "inlineScreen,replay")
        assertTrue(md, "| モデル呼び出し回数の中央値 | 6 |" in md)
        assertTrue(md, "| モデル呼び出し回数 P90 | 10 |" in md)
        assertTrue(md, "| キャッシュ利用率（cached/input） | 25.0% |" in md)
        // には合計 30 秒かかります (モデルに 15 秒、ツールに 5 秒)
        assertTrue(md, "| モデルの待機時間の割合 | 50.0% |" in md)
        assertTrue(md, "| ツール実行時間の割合 | 16.7% |" in md)
        assertTrue(md, "| 手順再実行の適合 | 0 |" in md)
        assertTrue(md, "| 手順再実行からモデルへ移行 | 0 |" in md)
        assertTrue(md, "inlineScreen,replay" in md)
    }

    // の合計割合は 100% です。モデルとツールを除けば、これは「その他/待機ユーザー」です。待機中のインターフェースが測定されていない場合は、「(データはまだありません)」と記述します
    @Test fun sharesSumToHundredWithOtherRow() {
        val scores = listOf(pair("a", 4, 400, 100, 5_000, 2_000), pair("b", 6, 300, 50, 5_000, 2_000), pair("c", 10, 300, 100, 5_000, 1_000))
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null)
        assertTrue(md, "| その他・ユーザー待機の割合 | 33.3% |" in md)
        assertTrue(md, "| うち画面待機の割合 | （データなし） |" in md)
    }

    // リプレイ評価: バリアント リプレイヒット、リターン、リターンが完了しました。元の質問は別にリストされており、インデックスには含まれていません
    @Test fun replayRatesAndOriginalsSection() {
        fun v(id: String, passed: Boolean, hits: Int, backs: Int) = TaskScore(id, passed, false, false, emptyList(), null) to
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 2, 10_000, 0, 0, 0, null, replayHits = hits, replayHandBacks = backs)
        val scores = listOf(v("a~v1", true, 1, 0), v("a~v2", true, 0, 1), v("b~v1", false, 0, 1), v("b~v2", true, 0, 0))
        val md = EvalReport.markdown("r", "m", scores, emptyList(), null, originals = listOf(pair("a", 4, 1, 0, 1, 1)))
        assertTrue(md, "| 手順再実行の適合率（最後まで実行したタスク） | 1/4(25.0%) |" in md)
        assertTrue(md, "| 手順再実行からモデルへ戻した割合 | 2/4(50.0%) |" in md)
        assertTrue(md, "| モデルへ戻した後の達成率 | 1/2(50.0%) |" in md)
        assertTrue(md, "## 元のタスク（手順記録用。上記の指標には含めません）" in md)
        assertTrue(md, "実行 4 件" in md)
    }
}
