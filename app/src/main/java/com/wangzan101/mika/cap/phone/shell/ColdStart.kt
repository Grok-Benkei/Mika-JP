package com.wangzan101.mika.cap.phone.shell

/**
 * launch_app cold=trueの場合、プロセスを終了できますか?プロセスを終了すると、どの画面でもこのアプリのステータスがクリアされます。
 * したがって、Mika の画面の外にタスクがある限り (バックグラウンドであっても)、終了することはなく、通常どおりに開始されます。
 */
enum class ColdStart {
    RESTART, WARM, IN_USE;

    companion object {
        fun decide(taskDisplays: List<Int>, agentDisplay: Int, visibleOnMain: Boolean): ColdStart = when {
            visibleOnMain -> IN_USE
            taskDisplays.any { it != agentDisplay } -> WARM
            else -> RESTART
        }
    }
}
