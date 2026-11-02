package com.wangzan101.mika.core.trigger

/** ローカル ユーザーのスケジュール管理。ホストはアラームの永続化と再配置を担当します。 */
interface ScheduleAdmin {
    fun list(threadId: String): List<TriggerDefinition>
    fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean
    fun remove(threadId: String, id: String): Boolean
    fun reschedule(threadId: String, id: String, atMs: Long): Boolean
    fun runNow(threadId: String, id: String): Boolean
}

/**
 * スケジュールされたタスクを保存した後の状況: [onTime] は false です。これは、システムが目覚まし時計を時間通りにすることを許可していないことを意味します (時間通りにならないか、起きられない可能性があります)。
 * このとき、[userNotified] は、今回はユーザーが実際に電源をオンにするよう求められたかどうかを尋ねました。
 */
data class Scheduled(val onTime: Boolean, val userNotified: Boolean = false)
