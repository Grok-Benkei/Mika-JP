package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/** 実行のイベント ディスパッチャー。レコード、インターフェイス、配信の各コピーを 1 部ずつ注文でき、1 人の受信者が失敗しても他の受信者には影響しません。 */
class RunEventBus : RunSink {
    private val listeners = CopyOnWriteArrayList<RunSink>()

    fun subscribe(listener: RunSink): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun event(e: RunEvent) {
        listeners.forEach { listener ->
            try { listener.event(e) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* 操作はサブスクライバー障害によって中断できません */ }
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        listeners.forEach { listener ->
            try { listener.media(mediaId, png) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* 同上 */ }
        }
    }
}
