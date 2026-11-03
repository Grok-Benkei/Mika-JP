package com.wangzan101.mika

import androidx.test.platform.app.InstrumentationRegistry

/** デバイス テストは、アプリが保持する TaskCenter からステータスを読み取ります。 TaskService は Android ホストのみを担当します。 */
object DeviceTaskState {
    private val center get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp).taskState
    val state get() = center.state
    val busy get() = center.busy
    val takeoverStage get() = center.takeoverStage
    val conversationId get() = center.conversationId
    val frames get() = center.frames
    val runs get() = center.runs
    val summarizing get() = center.summarizing
    val endedRunIds get() = center.endedRunIds
    fun run(conversationId: String) = center.run(conversationId)
}
