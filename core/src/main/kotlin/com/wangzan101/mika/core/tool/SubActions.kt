package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part

/**
 * インターフェイスを移動しないツール (ページ スクリプト) 内のアクションは、実行のためにループに返されます。ガード、画面ロック レベル、元に戻せない決定、および実行レコードはすべて、モデルがこのツールを単独で呼び出した場合と同じです。
 * が停止していて実行されていない場合、結果のエラーはconfirmation_required / Guard_stopped / target_unresolvedであり、実行された場合はfalseとなります。
 */
fun interface SubActions {
    suspend fun run(call: Part.ToolCall): ToolExecution
}
