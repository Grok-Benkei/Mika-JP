package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.serialization.json.JsonObject

/** 呼び出し元によって実行エントリに挿入されたいくつかの場所 (モデルのシングルステップの場合のみ): ユーザーの結果と最終処理を待ってからモデルに引き渡します。 */
interface FollowUp {
    /** の実行後、ガード検証前: ユーザーの結果 (確認、質問、引き継ぎ) が最終結果に置き換えられるまで待ちます。 null を返すと、タスクがキャンセルされたことを意味します。 */
    suspend fun resolve(exec: ToolExecution): ToolExecution? = exec

    /** ガードチェック後、記録前: 最終結果とモデルに渡された内容を返します(走行記録にも記録されます)。 */
    suspend fun present(exec: ToolExecution, verdicts: List<GuardVerdict>): Presented = Presented(exec, exec.content)

    /** は、モデルに指示が渡された後、走行記録に記録される前に確認レベルで停止されました。 */
    fun refused(content: JsonObject) {}

    companion object {
        val NONE = object : FollowUp {}
    }
}

data class Presented(val exec: ToolExecution, val content: JsonObject)
