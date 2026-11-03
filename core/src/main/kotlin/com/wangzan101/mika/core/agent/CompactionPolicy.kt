package com.wangzan101.mika.core.agent

/**
 * モデルの圧縮アカウンティング: このタスクが厳しい圧縮に変更されたかどうか (モデルが長すぎると報告されています)、最後に発行されたときに使用された折り畳みステップの数、および使用された強度。
 * 各モデルには 1 つのコピーがあり、軽量モデルの圧縮はメイン モデルには影響しません。
 */
internal data class CompactionState(val aggressive: Boolean = false, val lastFolded: Int = 0, val lastAggressive: Boolean = false)

/** 圧縮のタイミング: どんなときに圧縮イベントを1件記録するか、コンテキストが長すぎるときにさらに強く圧縮して再送するか。純関数。 */
internal object CompactionPolicy {
    /** 今回の送信で [folded] ステップを畳んだ。返した後の記録と、圧縮イベントを記録するかどうか（畳んだステップ数や強さが変わったときだけ記録する）。 */
    fun sent(s: CompactionState, folded: Int): Pair<CompactionState, Boolean> {
        val report = folded > 0 && (folded != s.lastFolded || s.aggressive != s.lastAggressive)
        return s.copy(lastFolded = folded, lastAggressive = s.aggressive) to report
    }

    /**
     * モデルがコンテキスト長超過を報告した。超過のたびに再送は1回だけ: [retried] が真（今回の呼び出しが既に超過で再送済み）なら再送しない;
     * そうでなければ [shrinks] が完全な履歴から改めて強く圧縮し直し、送ったばかりのものより小さくなった場合だけ再送する（前回の強い圧縮の後に履歴が伸びていた場合も含む）。以後は常に強く圧縮する。
     */
    fun overflow(s: CompactionState, retried: Boolean, shrinks: () -> Boolean): Pair<CompactionState, Boolean> =
        s.copy(aggressive = true) to (!retried && shrinks())
}
