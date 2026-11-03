package com.wangzan101.mika.task

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskChannelsTest {
    // Repair 1 M4: 「Need You」チャネル (引き継ぎ、質問、確認の共有) が高優先で確立されます
    @Test fun attentionChannelCreatedHigh() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, TaskService.CHANNEL_IMPORTANCE[TaskService.CHANNEL_ATTENTION])
    }

    // 結果は 1 回ポップアップ表示されますが、進行状況は静かなままです
    @Test fun resultHighProgressQuiet() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, TaskService.CHANNEL_IMPORTANCE[TaskService.CHANNEL_RESULT])
        assertEquals(NotificationManager.IMPORTANCE_LOW, TaskService.CHANNEL_IMPORTANCE["task_progress"])
    }

    // 古い結果チャネルは低いレベルで構築されているため、より高いレベルに調整することはできません。ID を新しいものに変更し、古い ID を削除してください。
    @Test fun resultChannelUsesNewIdAndOldIsDeleted() {
        assertEquals("task_result_v2", TaskService.CHANNEL_RESULT)
        assertEquals("task_result", TaskService.OLD_CHANNEL_RESULT)
        assertNotEquals(TaskService.OLD_CHANNEL_RESULT, TaskService.CHANNEL_RESULT)
        assertFalse(TaskService.CHANNEL_IMPORTANCE.containsKey(TaskService.OLD_CHANNEL_RESULT))
    }
}
