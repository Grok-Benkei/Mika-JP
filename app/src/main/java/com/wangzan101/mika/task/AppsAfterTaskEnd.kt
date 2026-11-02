package com.wangzan101.mika.task

import android.util.Log
import com.wangzan101.mika.cap.phone.shell.AppsAfterTask
import com.wangzan101.mika.core.uistate.AfterTaskMarks
import com.wangzan101.mika.shell.IMikaShell

/**
 * タスクの最後に、Mika 画面上のアプリをどうするか: モデルの下の項目を内蔵アシスタント ([IMikaShell.finishDisplay]) に任せます。
 * は、ルール ([AppsAfterTask]) に従って仮想画面を離れるかオフにすることができ、その後、仮想画面を解放します。復元する前にミュートに調整します。復元時に残りの音が突然鳴らないように、最初に音を鳴らしているものをオフにします。
 * アシスタントが完了していない（エラーが発生して画面が認識されない）場合は、やはり最後に画面が解放され、画面とともに上記アプリが終了します。
 */
internal object AppsAfterTaskEnd {
    private const val TAG = "AppsAfterTask"

    /** このプロセスの最新タスクの終了時の結果 (診断情報は 1 行、数値のみ)。まだの場合は null です。 */
    @Volatile var lastLine: String? = null
        private set

    data class Outcome(val kept: List<String>, val closed: List<String>, val note: String?) {
        /** 走行記録の一文: 退出および電源オフのキーと理由。 */
        val detail: String
            get() = listOfNotNull(
                "kept ${kept.joinToString(",").ifEmpty { "-" }}",
                "closed ${closed.joinToString(",").ifEmpty { "-" }}",
                note,
            ).joinToString("; ")

        val diagnostics: String get() = AppsAfterTask.diagnosticsLine(kept.size, closed, note)
    }

    fun finish(shell: IMikaShell, displayId: Int, marks: AfterTaskMarks?): Outcome {
        val keep = marks?.keepKeys().orEmpty().toTypedArray()
        val close = marks?.closeKeys().orEmpty().toTypedArray()
        val result = try {
            shell.finishDisplay(displayId, keep, close)
        } catch (t: Throwable) {
            Log.w(TAG, "finishDisplay failed; apps close with the display", t)
            null
        }
        val outcome = if (result == null) Outcome(emptyList(), emptyList(), "closed with the display (helper did not sort them)")
        else Outcome(
            result.getStringArray(AppsAfterTask.KEY_KEPT)?.toList().orEmpty(),
            result.getStringArray(AppsAfterTask.KEY_CLOSED)?.toList().orEmpty(),
            result.getString(AppsAfterTask.KEY_NOTE),
        )
        lastLine = outcome.diagnostics
        return outcome
    }
}
