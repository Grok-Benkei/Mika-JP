package com.wangzan101.mika.core.selfcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckReportTest {
    private val facts = DeviceFacts(
        "Acme", "Acme Phone 7", "14", 34, listOf("arm64-v8a", "armeabi-v7a"), 12L * 1024 * 1024 * 1024, 1224, 2700,
        wirelessDebugging = true, appVersion = "0.1.21", appBuild = "abc1234",
    )

    private fun done(vararg rs: CheckResult) = SelfCheckState(
        SelfCheckState.Phase.Done,
        SelfCheck.entries.map { c -> rs.firstOrNull { it.check == c } ?: CheckResult(c, CheckStatus.Pass, "正常です") },
        facts, elapsedMs = 62_400,
    )

    @Test
    fun headerAndOneLinePerCheck() {
        val text = SelfCheckReport.render(done(
            CheckResult(SelfCheck.PERMISSIONS, CheckStatus.Partial, "ユーザー補助が接続済み、通知は不許可", Advice.AllowNotifications),
            CheckResult(SelfCheck.STAYS_ALIVE, CheckStatus.Fail, "6秒後に映像が停止", Advice.FramesStopped),
        ))
        val lines = text.lines()
        assertEquals("Mika 互換性診断 0.1.21（ビルド abc1234）", lines[0])
        assertEquals("端末: Acme Phone 7, Android 14（SDK 34）, arm64-v8a, RAM 12.0 GB, 1224x2700", lines[1])
        assertEquals("ワイヤレスデバッグ: あり", lines[2])
        assertEquals("結果: 正常 10 件、一部利用可能 1 件、失敗 1 件、スキップ 0 件（62 秒）", lines[3])
        assertEquals("", lines[4])
        assertTrue(text.contains("2 権限: 一部利用可能 — ユーザー補助が接続済み、通知は不許可\n"))
        assertTrue(text.contains("8 継続動作: 失敗 — 6秒後に映像が停止\n"))
        assertTrue(text.endsWith("12 アプリの複製: 正常 — 正常です\n"))
        assertEquals(5 + SelfCheck.entries.size, lines.size - 1)
    }

    @Test
    fun manufacturerIsAddedWhenTheModelLacksIt() {
        val text = SelfCheckReport.render(done().copy(facts = facts.copy(model = "Phone 7", wirelessDebugging = false)))
        assertTrue(text.contains("端末: Acme Phone 7,"))
        assertTrue(text.contains("ワイヤレスデバッグ: なし"))
    }

    @Test
    fun environmentWithoutPhoneControlIsWrittenInTheHeader() {
        val f = facts.copy(wirelessDebugging = false, developerOptionsPage = false, phoneControlUnavailable = true)
        val text = SelfCheckReport.render(done().copy(facts = f))
        assertTrue(text.contains("開発者向けオプション: なし"))
        assertTrue(text.contains("スマートフォン操作: この環境では利用できません"))
        assertFalse(SelfCheckReport.render(done()).contains("スマートフォン操作:"))
    }

    @Test
    fun cancelledRunSaysSo() {
        val s = done().copy(phase = SelfCheckState.Phase.Cancelled, elapsedMs = 9_000)
        assertTrue(SelfCheckReport.render(s).contains("結果: 9 秒でキャンセルしました。"))
    }

    @Test
    fun detailsAreFlattenedAndCut() {
        val long = "line one\nline two " + "x".repeat(400)
        val line = SelfCheckReport.line(CheckResult(SelfCheck.BROWSER, CheckStatus.Fail, long, Advice.WebFailed))
        assertFalse(line.contains('\n'))
        assertTrue(line.startsWith("10 ブラウザー: 失敗 — line one line two x"))
        assertTrue(line.length < 260)
    }

    /** レポートには、数行の固定ヘッダー行と項目ごとに 1 行しかありません。アカウント番号、アプリ リスト、ユーザー コンテンツ、シリアル番号を入れる場所はありません。 */
    @Test
    fun onlyKnownLinesAppear() {
        val text = SelfCheckReport.render(done())
        val allowed = listOf("Mika 互換性診断 ", "端末: ", "ワイヤレスデバッグ: ", "開発者向けオプション: ", "スマートフォン操作: ", "結果: ") +
            SelfCheck.entries.map { "${it.number} ${it.displayName}: " }
        for (l in text.lines().filter { it.isNotEmpty() }) assertTrue(l, allowed.any { l.startsWith(it) })
    }

    @Test
    fun theWholeTextGoesThroughSanitize() {
        val s = done(CheckResult(SelfCheck.HELPER, CheckStatus.Pass, "接続先: 192.168.1.20:37000"))
        val text = SelfCheckReport.render(s) { it.replace(Regex("""\d+\.\d+\.\d+\.\d+(:\d+)?"""), "<ip>") }
        assertTrue(text.contains("3 操作アシスタント: 正常 — 接続先: <ip>"))
        assertFalse(text.contains("192.168"))
    }

    @Test
    fun issueLinkPrefillsOnlyTheTitle() {
        assertEquals("[互換性] Android 14 Acme Phone 7", SelfCheckReport.issueTitle(facts))
        val url = SelfCheckReport.issueUrl("https://github.com/wangzan101/Mika/issues/new", facts)
        assertEquals(
            "https://github.com/wangzan101/Mika/issues/new?template=device-report.yml&title=%5B%E4%BA%92%E6%8F%9B%E6%80%A7%5D%20Android%2014%20Acme%20Phone%207",
            url,
        )
        assertFalse(url.contains("body="))
    }
}
