package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.i18n.tr

/** バックグラウンドで実行されたままになっているページについてインターフェイスに表示される内容: どれくらいの時間放置されているのか、またなぜ閉じられたのか。 */
object KeptPageCopy {
    /** 「1 時間 52 分」、「2 時間」、「8 分」; 1 分未満の場合は 1 分とみなされます。 */
    fun duration(ms: Long): String {
        val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return when {
            h == 0 -> tr(R.string.kept_m, m)
            m == 0 -> tr(R.string.kept_h, h)
            else -> tr(R.string.kept_hm, h, m)
        }
    }

    /** には、自動シャットオフを表す [ms] が残ります (「... 後に自動的にシャットオフ」と綴られます)。 */
    fun closesIn(ms: Long): String = duration(ms)

    /** シャットダウンの理由。認識できない場合は「Mika が終了しました」と表示されます (古い記録、書き留められていない)。 */
    fun reason(wire: String?): String = when (KeptPageClose.of(wire)) {
        KeptPageClose.Expired -> tr(R.string.kept_reason_expired)
        KeptPageClose.Memory -> tr(R.string.kept_reason_memory)
        KeptPageClose.Crashed -> tr(R.string.kept_reason_crashed)
        KeptPageClose.User -> tr(R.string.kept_reason_user)
        KeptPageClose.Replaced -> tr(R.string.kept_reason_replaced)
        KeptPageClose.AppExit, null -> tr(R.string.kept_reason_app_exit)
    }

    /** そのラウンドの次の文: "バックグラウンド Web ページが閉じられました (理由)";理由が記載されていない場合（閉店直後や美香が途中退場した場合）には理由は記載されません。 */
    fun closedNote(wire: String?): String = if (KeptPageClose.of(wire) == null) tr(R.string.kept_closed_plain) else tr(R.string.kept_closed_note, reason(wire))
}
