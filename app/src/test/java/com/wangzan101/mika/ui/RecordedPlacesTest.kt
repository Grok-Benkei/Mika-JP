package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.verify.AnswerSource
import com.wangzan101.mika.i18n.JapaneseTexts
import com.wangzan101.mika.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class RecordedPlacesTest {
    @get:Rule val texts = JapaneseTexts()

    private val prefixes = listOf("ウェブサイト ", "网站 ")
    private val host = "www.example.com"

    @Test fun structuredSourcesReadCurrentAndLegacySitePrefixes() {
        for (prefix in prefixes) {
            val events = listOf(RunEvent.Sources(1, listOf(
                AnswerSource(1, "web", "$prefix$host", "検索結果", 3, listOf(2)),
            )))
            assertEquals(
                EvidenceSource(host, listOf(2), listOf("${tr(R.string.step_number, 3)} · $host · 検索結果")),
                evidenceSource(events) { error("サイトを App として解決しない: $it") },
            )
        }
    }

    @Test fun savedScreensReadCurrentAndLegacySitePrefixes() {
        for (prefix in prefixes) {
            val events = listOf(
                RunEvent.Media(1, 1, 1, "com.example.app"),
                RunEvent.Media(2, 2, 3, "$prefix$host"),
                RunEvent.Media(3, 3, 3, null),
            )
            assertEquals(
                EvidenceSource(host, listOf(2, 3)),
                evidenceSource(events) { error("サイトを App として解決しない: $it") },
            )
        }
    }

    @Test fun stepRowsAndActionDetailsReadBothSitePrefixes() {
        val labels = ToolLabels(knownTools())
        for (prefix in prefixes) {
            val before = Exchange("検索", emptyList(), ExchangeStatus.Working, live = true, activities = listOf(
                RunEvent.Activity(1, 1, "observe", "web_read_page", "$prefix$host", "completed"),
            ))
            val after = ChatViewModel.reduce(before, TaskState.Running(1, "", "web_read_page"),
                appLabel = { error("サイトを App として解決しない: $it") }, toolLabel = labels::label)
            assertEquals(tr(R.string.step_viewed, host), after.steps.single().title)
            assertEquals(host, after.stepActions.getValue(1).single().target)
        }
    }

    @Test fun browserTagsRecognizeBothPrefixesAndPreserveOtherTargetKinds() {
        for (prefix in prefixes) {
            assertEquals(PageAskCopy.WEB, pageStepTag("  $prefix$host  "))
        }
        assertEquals(PageAskCopy.APP, pageStepTag("com.example.app"))
        assertNull(pageStepTag("ls -l"))
        assertNull(pageStepTag("/workspace/result.txt"))
        assertNull(pageStepTag(null))
    }

    @Test fun prefixRecognitionDoesNotRewriteUnprefixedData() {
        assertNull(recordedSiteHost(host))
        assertNull(recordedSiteHost("/workspace/网站 example.com"))
        val events = listOf(RunEvent.Sources(1, listOf(
            AnswerSource(1, "screen", "com.example.app", null, 1, emptyList()),
            AnswerSource(2, "sandbox", "/workspace/result.txt", null, 2, emptyList()),
            AnswerSource(3, "web", "https://$host/results", null, 3, emptyList()),
        )))
        assertEquals(listOf(
            "${tr(R.string.step_number, 1)} · アプリ名",
            "${tr(R.string.step_number, 2)} · /workspace/result.txt",
            "${tr(R.string.step_number, 3)} · $host",
        ), evidenceSource(events) { if (it == "com.example.app") "アプリ名" else error("予期しない App: $it") }.items)
    }
}
