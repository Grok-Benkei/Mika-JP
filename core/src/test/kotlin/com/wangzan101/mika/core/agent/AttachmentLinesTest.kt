package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** 添付ファイル行の書き方: 単語が最初に来て、添付ファイルごとに 1 行が後に来て、その後に分割されます。古いダイアログでの書き方は、添付ファイルの行の言葉と同じように見えます。 */
class AttachmentLinesTest {
    @Test fun composeWritesWhatParseReads() {
        val files = listOf("/workspace/inbox/a.png" to "3 KB", "/workspace/inbox/b.docx" to "1.0 MB")
        val text = AttachmentLines.compose("查一下", files)
        assertEquals("查一下\n添付:/workspace/inbox/a.png(3 KB)\n添付:/workspace/inbox/b.docx(1.0 MB)", text)
        assertEquals(AttachmentLines.Parsed(files, "查一下"), AttachmentLines.parse(text))
        assertEquals("添付:/workspace/inbox/a.png(3 KB)", AttachmentLines.compose("", listOf("/workspace/inbox/a.png" to "3 KB")))
    }

    // 古い会話に保存された書き込み方法: 括弧付きの名前、単語なしの添付ファイルのみ、バイト単位のサイズ
    @Test fun oldMessagesParseTheSameWay() {
        assertEquals(
            AttachmentLines.Parsed(listOf("/workspace/inbox/报表 (1).csv" to "2 KB", "/workspace/inbox/x" to "512 B"), ""),
            AttachmentLines.parse("附件:/workspace/inbox/报表 (1).csv(2 KB)\n附件:/workspace/inbox/x(512 B)"),
        )
    }

    @Test fun linesThatOnlyLookLikeAttachmentsStayInTheWords() {
        for (t in listOf("附件:/etc/passwd(1 KB)", "附件:/workspace/inbox/a.pdf", "附件:/workspace/inbox/a.pdf(很大)", "看看附件:/workspace/inbox/a.pdf(1 KB)")) {
            assertEquals(t, AttachmentLines.Parsed(emptyList(), t), AttachmentLines.parse(t))
        }
        assertEquals(AttachmentLines.Parsed(emptyList(), "普通的一句话"), AttachmentLines.parse("  普通的一句话 "))
    }
}
