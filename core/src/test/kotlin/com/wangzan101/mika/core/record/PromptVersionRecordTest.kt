package com.wangzan101.mika.core.record

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.prompt.PromptVersion
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * このタスクのプロンプト ワードのバージョンを実行記録に書き留めます。モデルに送信されるシステム プロンプト ワードと、各グループが展開されるときにツール定義の概要を加えます。
 * は、今回実際にどのグループが展開されるかには関係ありません (携帯電話のみがある場合は、携帯電話のグループを展開した後に表示されるものです)。 OAuth 暗黙的認証により
 */
class PromptVersionRecordTest {
    private suspend fun run(tools: ToolDispatcher): Pair<String, String> {
        val events = mutableListOf<RunEvent>()
        val m = fakeModel("main") { call("phone_observe"); answer("完了しました") }
        AgentLoop(m, tools, SystemPrompt.identity(), sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).run("t")
        val r = m.requests.last()
        return events.filterIsInstance<RunEvent.PromptVersion>().single().hash to PromptVersion.of(r.system, r.tools)
    }

    @Test fun recordsHashOfWhatTheModelSaw() = runTest {
        val (phone, expected) = run(toolDispatcher(FakeScreen()))
        assertEquals(expected, phone)
        val (web, _) = run(toolDispatcher(FakeScreen(), web = FakeWebPort()))
        assertNotEquals(phone, web)
    }
}
