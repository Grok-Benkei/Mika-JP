package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ループによって報告される最終状態は公開されずに書き出されます。最終状態は、実行番号を記憶した後に TaskRun.end によってのみ書き込まれます。
 * それ以外の場合、インターフェイスは最初に終了を受信し、取得された実行番号は空になり、カード上に「プロセスの表示」がありません。
 */
class TaskPublishTest {
    @Test fun loopTerminalStatesAreNotPublished() {
        assertFalse(TaskService.loopMayPublish(TaskState.Succeeded("ok")))
        assertFalse(TaskService.loopMayPublish(TaskState.Failed("x", FailKind.GaveUp)))
        assertFalse(TaskService.loopMayPublish(TaskState.Cancelled))
        assertFalse(TaskService.loopMayPublish(TaskState.Idle))
        assertTrue(TaskService.loopMayPublish(TaskState.Running(1, "看屏幕", "phone_observe")))
        assertTrue(TaskService.loopMayPublish(TaskState.WaitingInput("哪个?", null)))
    }
}
