package com.wangzan101.mika.core.guard

import com.wangzan101.mika.core.model.Part
import kotlinx.serialization.json.JsonObject

/** 画面を移動しないツール (サンドボックスなど) のガード (タスクごとに 1 つ)。スクリーン ガードはこれらのツールを参照しません。 */
interface OffScreenGuard {
    /** やめる、という結論。停止していない場合は null です。 */
    val stop: GuardVerdict?

    /** このタイプのツールのクォータが使い果たされた場合の置換実行の結果。使い果たされていない場合は null になります。 */
    fun exhausted(tool: String): JsonObject?

    /** が呼び出されます。調整せずにそのまま戻します。 */
    fun capTimeout(call: Part.ToolCall, args: JsonObject): Part.ToolCall

    /** 実行後の結論。 */
    fun result(tool: String, args: JsonObject, content: JsonObject): List<GuardVerdict>
}
