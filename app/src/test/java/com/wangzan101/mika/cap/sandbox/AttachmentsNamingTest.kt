package com.wangzan101.mika.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsNamingTest {
    @Test fun stripsSeparatorsKeepsChinese() {
        assertEquals("报表 (1).csv", Attachments.safeName("报表 (1).csv"))
        assertEquals("etcpasswd", Attachments.safeName("../etc/passwd"))
        assertEquals("ab.txt", Attachments.safeName("a\\b.txt"))
        assertEquals("ab.txt", Attachments.safeName("a\u0000\u0007\nb.txt"))
        // 書き換え方向とゼロ幅文字: 表示される名前が実際の名前と一致しません
        assertEquals("invoicefdp.exe", Attachments.safeName("invoice‮fdp.exe"))
        assertEquals("ab", Attachments.safeName("a​ b"))
        assertEquals("attachment", Attachments.safeName("/../\u0001"))
        assertEquals("hidden", Attachments.safeName(".hidden"))
        // は最大 120 文字で、UTF-8 は 240 バイトを超えず (システムがシリアル番号を追加できる余地を残す)、拡張子は予約されています。
        val long = Attachments.safeName("长".repeat(300) + ".txt")
        assertTrue(long.toByteArray().size <= 240)
        assertTrue(long.endsWith(".txt"))
        assertEquals(120, Attachments.safeName("a".repeat(300)).length)
        val emoji = Attachments.safeName("😀".repeat(200))
        assertFalse(emoji.last().isHighSurrogate())
    }

    @Test fun numberedKeepsExtension() {
        assertEquals("a.csv", Attachments.numbered("a.csv", 1))
        assertEquals("a (2).csv", Attachments.numbered("a.csv", 2))
        assertEquals("README (3)", Attachments.numbered("README", 3))
        assertTrue(Attachments.numbered("x.tar.gz", 2).endsWith(".gz"))
    }

    @Test fun shareQuotaPerTask() {
        val q = ShareQuota(maxFiles = 2, maxBytes = 100)
        q.reserve(10); q.reserve(20)
        val third = runCatching { q.reserve(1) }.exceptionOrNull() as com.wangzan101.mika.core.workspace.SandboxError
        assertEquals("share_limit", third.code)
        // 失敗したエクスポートにはクォータが考慮されていません
        q.release(20)
        q.reserve(60)
        // 実際にコピーされたバイト数に基づく決済: 予約済みバイト数未満、保存済みバイトは引き続き使用可能
        q.settle(60, 10)
        val bytes = ShareQuota(maxBytes = 100)
        bytes.reserve(90); bytes.settle(90, 50)
        bytes.reserve(50)
        assertEquals("share_limit", (runCatching { bytes.reserve(1) }.exceptionOrNull() as com.wangzan101.mika.core.workspace.SandboxError).code)
    }
}
