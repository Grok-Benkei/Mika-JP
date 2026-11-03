package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** は、復元時のタイミング トリガーについていくつかのことを伝える必要があります。製品内の [TriggerRuntime] に転送します。 */
interface TriggerOutcomes {
    fun interrupted(key: String, reason: String)
    fun delivered(key: String)
    fun actionFailed(key: String, reason: String)
}

/**
 * 「操作中断」の余波: 既存の応答を再送信します。ステータスが不明な場合は再送信しません。それ以外の場合は「操作中断」を通知し、会話に書き込みます。
 * 2 つの入り口: プロセス再起動後の [recover] (アプリの起動とタスク サービスの起動の両方が調整されます。プロセス内で 1 回だけ実行され、その後は何も行われません。
 * アプリはプロセス内のどのサービスよりも前に起動するため、このプロセスの新しい実行が初めて呼び出されたときに誤って判断されることはありません)、
 * および [settleInterrupted] (タスク サービスが破棄され、プロセスがまだ存在する場合)。 **途中からは絶対に実行しないでください**: 画面の状態は復元できません。
 *
 * 「通知済み」に個別のフィールドはありません。配送台帳を参照してください: 応答がない場合、`interrupted:<実行ID>` のプロンプトが記録されます。ステータスが不明な場合、または配信が失敗した場合は、対応するプロンプトが記録されます。
 * が記録されている場合は処理されません。応答が配信された実行は、直接「終了」に変換されます。したがって、入口と複数の呼び出しの両方で 1 回だけ通知されます。
 */
class RunRecovery(
    private val store: RunStore,
    private val outbox: Outbox,
    private val channel: Channel,
    private val triggers: TriggerOutcomes,
    private val conversations: ConversationStore,
    private val scope: CoroutineScope,
) {
    private val done = AtomicBoolean(false)
    private val lock = Mutex()

    /** マークはここで同期的に完了します (新しく開かれた実行には影響しません)。その後の処理は [scope] でゆっくりと行われます。中断としてマークされた実行を返します。最初の呼び出しでない場合は、空になります。 */
    fun recover(live: Set<RunId> = emptySet()): List<StoredRun> {
        if (!done.compareAndSet(false, true)) return emptyList()
        val interrupted = store.interruptUnfinished(live)
        settleInterrupted()
        return interrupted
    }

    /**
     * 中断され、まだ通知されていないすべての操作をクリーンアップします。タスク サービスが破棄されると、その場で呼び出されます (プロセスはまだ存在しており、ユーザーは次の起動まで待つ必要はありません)。
     * [recover] も起動時にスキャンされ、終了する前にその場で殺された人を捕まえます。
     */
    fun settleInterrupted() {
        scope.launch {
            lock.withLock {
                store.all().filter { it.phase == RunPhase.Interrupted && !told(it) }
                    .forEach { run -> runCatching { settle(run) } }
            }
        }
    }

    private fun noticeIds(run: StoredRun) =
        if (run.reply != null && run.deliveryId != null) listOf("uncertain:${run.deliveryId}", "failed:${run.deliveryId}")
        else listOf("interrupted:${run.id.value}")

    private fun told(run: StoredRun) = noticeIds(run).any { outbox.get(it) != null }

    private suspend fun settle(run: StoredRun) {
        run.triggerWindowKey?.let { triggers.interrupted(it, "run interrupted before the result was confirmed") }
        val peer = ChannelPeer(LocalChannel.ID, "phone-owner", run.threadId)
        val deliveryId = run.deliveryId
        val reply = run.reply
        if (reply != null && deliveryId != null) {
            val prior = outbox.get(deliveryId)
            val state = when (prior?.state) {
                null, DeliveryState.Pending -> outbox.deliver(channel, peer,
                    prior?.delivery ?: Delivery.Result(deliveryId, run.threadId, reply, restored = true, title = tr(R.string.recovered_result))).state
                DeliveryState.Attempting -> outbox.deliver(channel, peer, prior.delivery).state
                else -> prior.state
            }
            when (state) {
                DeliveryState.Delivered -> finish(run, reply)
                DeliveryState.Failed -> outbox.deliver(channel, peer, Delivery.Notice("failed:$deliveryId", run.threadId,
                    tr(R.string.recover_delivery_failed)))
                else -> outbox.deliver(channel, peer, Delivery.Notice("uncertain:$deliveryId", run.threadId,
                    tr(R.string.recover_maybe_delivered)))
            }
        } else {
            val turnId = "interrupted:${run.id.value}"
            if (conversations.turns(run.threadId).none { it.runId == turnId }) {
                conversations.append(run.threadId, Turn(run.goal, tr(R.string.recover_interrupted_turn), "failed", runId = turnId))
            }
            outbox.deliver(channel, peer, Delivery.Notice(turnId, run.threadId,
                tr(R.string.recover_interrupted, run.goal.take(40))))
        }
    }

    private fun finish(run: StoredRun, reply: String) {
        store.save(run.copy(phase = RunPhase.Ended, roundInProgress = false))
        run.triggerWindowKey?.let { key ->
            if (run.runSucceeded == false) triggers.actionFailed(key, reply) else triggers.delivered(key)
        }
    }
}
