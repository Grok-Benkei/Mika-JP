package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.recordedTools
import com.wangzan101.mika.core.cap.sandbox.SANDBOX
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/** テスト者：ミカ 全てのツール宣言。 */
internal val TOOLS: ToolIndex get() = knownTools()

/** テスト用: 実行レコードを読み取るためのツール ステートメント (名前変更前の古い名前も認識されます)。 */
internal val RECORDED_TOOLS: ToolIndex get() = recordedTools()

/** テストの場合: ツール名を押して、実行されるアクションを確認します。 */
internal fun verb(name: String): Verb? = TOOLS.verb(name)

/** テスト用: do_steps に入れることができるツール名 (定義内の順序)。 */
internal val BATCHABLE: List<String> get() = TOOLS.names.filter { TOOLS[it]!!.batchable }

/** テスト用: Web ページとサンドボックスのツール名のセット。 */
internal val WEB_TOOLS: Set<String> get() = TOOLS.names.filter { TOOLS[it]!!.capability == WEB }.toSet()
internal val SANDBOX_TOOLS: Set<String> get() = TOOLS.names.filter { TOOLS[it]!!.capability == SANDBOX }.toSet()

internal const val WEB_SCRIPT_TOOL = "web_script"
internal const val INTERACT_TOOL = "web_interact"

/** テストの場合: 電話なしの応答がユーザーに与えられる最終応答です。 */
internal fun answerReply(text: String) =
    com.wangzan101.mika.core.model.ModelReply(listOf(com.wangzan101.mika.core.model.Part.Text(text)), com.wangzan101.mika.core.model.StopReason.EndTurn, null)

/** テストの場合: タスクは完了し、答えは [answer] で、検証上の問題はありません (検証済み、または検証できるものが何もありません。ソースは個別にテストされます)。 */
internal fun assertAnswered(answer: String, end: com.wangzan101.mika.core.agent.TaskState) {
    val s = end as? com.wangzan101.mika.core.agent.TaskState.Succeeded ?: throw AssertionError("not succeeded: $end")
    org.junit.Assert.assertEquals(answer, s.answer)
    org.junit.Assert.assertTrue("$end", !s.doubtful && s.unconfirmed.isEmpty())
}
