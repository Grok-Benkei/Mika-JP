package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.testTasks
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the installed app's settings, TaskRun, recording and timeline with a local Responses endpoint. */
@RunWith(AndroidJUnit4::class)
class RunTraceDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun releaseIgnoresAPreviouslyEnabledRecordingPreference() {
        org.junit.Assume.assumeFalse(com.wangzan101.mika.record.DetailedTraces.supportedBuild)
        val app = compose.activity.application as MikaApp
        val prefs = app.getSharedPreferences("trace_settings", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getBoolean("enabled", false)
        try {
            prefs.edit().putBoolean("enabled", true).apply()
            assertTrue(!app.detailedTraces.enabled && app.detailedTraces.ticket() == null)
            assertTrue(app.detailedTraces.start(null, "release-check") == null)
        } finally {
            prefs.edit().putBoolean("enabled", previous).apply()
        }
    }

    @Test fun recordsAndViewsARequestFromTheSettingsSwitch() {
        org.junit.Assume.assumeTrue(com.wangzan101.mika.record.DetailedTraces.supportedBuild)
        val app = compose.activity.application as MikaApp
        val previous = app.detailedTraces.enabled
        val previousLimit = app.detailedTraces.limitMiB
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"完成"}]}],"usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":512},"output_tokens":2}}""")
                }
            }
            server.start()
            val model = ResponsesModel(server.url("/v1").toString(), BearerToken.fixed(null), "trace-device",
                Capabilities(true, false, 128_000, false), OkHttpClient())
            try {
                app.detailedTraces.setEnabled(false)
                // One local request exercises clipping without a long task or a large chat reply.
                val fixture = object : ChatModel by model {
                    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                        model.complete(system + " fixture".repeat(5_000), messages, tools)
                }
                testTasks.modelFactory = { _, _ -> fixture }
                testTasks.lightModelFactory = { _, _ -> null }
                testTasks.visionModelFactory = { _, _ -> null }
                // A fresh install starts on the optional setup page above the chat.
                compose.waitForIdle()
                if (compose.onAllNodesWithContentDescription(appString(R.string.chat_menu)).fetchSemanticsNodes().isEmpty()) {
                    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                }
                compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performClick()
                compose.onNodeWithText(appString(R.string.settings_title)).performClick()
                compose.onNodeWithText(appString(R.string.trace_switch)).assertDoesNotExist()
                compose.onNodeWithText(appString(R.string.settings_developer)).performScrollTo().performClick()
                compose.onNodeWithText(appString(R.string.trace_storage_limit)).performScrollTo().performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("0")
                compose.onNode(hasText(appString(R.string.save)) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
                compose.onNode(hasSetTextAction()).performTextReplacement("37")
                compose.clickInDialog(appString(R.string.save))
                compose.awaitOne(appString(R.string.trace_storage_value, 37)).assertExists()
                assertTrue(com.wangzan101.mika.record.DetailedTraces(app).limitMiB == 37)
                compose.onNodeWithText(appString(R.string.trace_switch)).performScrollTo().performClick()
                assertTrue(app.detailedTraces.enabled)
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.awaitOne(appString(R.string.settings_title)).assertExists()
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.runOnUiThread {
                    app.chat.newConversation()
                    assertTrue(app.chat.send("请回复完成，TRACE_FIXTURE", Prerequisites.Check(emptyList())))
                }
                compose.waitUntil(30_000) { compose.onAllNodesWithText(appString(R.string.chat_view_process)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(appString(R.string.chat_view_process)).performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(15_000) { compose.onAllNodesWithText(appString(R.string.timeline_title)).fetchSemanticsNodes().isNotEmpty() }
                // 実行中のプロセス ページには、最初にタイトルが表示され、次にこの実行のヘッダーが読み取られます。ヘッダー行が表示されるまで待ちます。
                compose.waitUntil(10_000) { compose.onAllNodesWithText(appString(R.string.timeline_export)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(appString(R.string.trace_title)))
                compose.onNodeWithText(appString(R.string.trace_title)).performScrollTo().performClick()
                // ページはタスクの開始時に開かれました。要求された行は、要求が記録され、ページが再度読み取られるまで表示されません。
                val usage = appString(R.string.trace_counts, "1200", "512", "688", "2").substringBeforeLast(" · ")
                compose.waitUntil(30_000) { compose.onAllNodesWithText(usage, substring = true).fetchSemanticsNodes().isNotEmpty() }
                val run = com.wangzan101.mika.record.RunStore(app).list().first { "TRACE_FIXTURE" in it.goal }
                compose.waitUntil(30_000) { !app.detailedTraces.store.recording(run.id) }
                // リクエスト本文が切り詰められ、記録後のトレース全体が「部分レコード」としてマークされたままになります。
                compose.waitUntil(5_000) { compose.onAllNodesWithText(appString(R.string.trace_partial)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(usage, substring = true).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText(appString(R.string.trace_save)).assertExists()
                val bytes = ByteArrayOutputStream().also { app.detailedTraces.store.archive(run.id)!!.save(it, false) }.toByteArray()
                assertTrue(bytes.isNotEmpty() && "TRACE_FIXTURE" !in bytes.decodeToString())
            } finally {
                app.detailedTraces.setEnabled(previous)
                app.detailedTraces.setLimitMiB(previousLimit)
                testTasks.modelFactory = TaskEnvironment.defaultModelFactory
                testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
                testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            }
        }
    }
}
