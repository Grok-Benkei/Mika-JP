package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink

/**
 * 実行中のレコードは原因と結果の順序で書き込まれます。呼び出しで読み取られて証拠台帳 ([observed]) に記録された観察が最初に保存され、この呼び出しのツール結果の後に書き出されます。
 * ツールの結果は読み取られず (戻るときに 1 つの画面が読み取られ、バッチの完了後に 1 回目で確認されます)、次の他のイベントの前に書き込まれます。モデルの応答とツールの結果の間には何もありません。
 */
internal class RecordOrder(private val sink: RunSink) : RunSink {
    private val pending = mutableListOf<RunEvent.Observed>()

    fun observed(e: RunEvent.Observed) {
        synchronized(pending) { pending += e }
    }

    override fun event(e: RunEvent) {
        if (e !is RunEvent.ModelReply && e !is RunEvent.ToolResult) flush()
        sink.event(e)
        if (e is RunEvent.ToolResult) flush()
    }

    override fun media(mediaId: Int, png: ByteArray) = sink.media(mediaId, png)

    private fun flush() {
        val out = synchronized(pending) { pending.toList().also { pending.clear() } }
        out.forEach(sink::event)
    }
}
