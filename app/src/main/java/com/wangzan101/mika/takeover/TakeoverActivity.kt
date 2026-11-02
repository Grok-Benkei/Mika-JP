package com.wangzan101.mika.takeover

import android.app.Activity
import android.os.Bundle
import com.wangzan101.mika.task.TaskService

/** はインターフェイスを表示しません。通知内でクリックされた引き継ぎボタンをタスク サービスに転送し、すぐに終了します。 */
class TakeoverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getIntExtra(TaskService.EXTRA_WAIT_KEY, -1)?.takeIf { it >= 0 }
        // ボタンはどのタスクに属しますか?複数のタスクが同時に引き継がれるのを待っている場合、それらのタスクを他のタスクに割り当てることはできません。
        val run = intent?.getStringExtra(TaskService.EXTRA_RUN)
        when (intent?.action) {
            TaskService.ACTION_TAKE_OVER -> TaskService.takeOver(this, key, run)
            TaskService.ACTION_HAND_BACK -> TaskService.handBack(this, key, run)
            TaskService.ACTION_FINISHED_MYSELF -> TaskService.finishedMyself(this, key, run)
            TaskService.ACTION_SKIP -> TaskService.skip(this, key, run)
        }
        finish()
    }
}
