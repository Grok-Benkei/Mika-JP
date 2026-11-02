package com.wangzan101.mika.about

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class MikaLinksTest {
    @get:Rule val texts = ChineseTexts()

    private val device = MikaLinks.Device("Google", "Pixel 8", "14", 34, "0.1.0", "65fcf92")

    @Test
    fun bodyHasThreePrefilledLines() {
        val lines = MikaLinks.issueBody(device).lines()
        assertEquals("- 端末: Google Pixel 8", lines[0])
        assertEquals("- Android バージョン: 14 (SDK 34)", lines[1])
        assertEquals("- Mika バージョン: 0.1.0 (ビルド 65fcf92)", lines[2])
        assertTrue(lines.contains("### 何が起きたか"))
        assertTrue(lines.contains("### 行った操作"))
        assertTrue(lines.contains("### 期待していた動作"))
        // 診断情報は自動的に取得されません。ユーザーは診断情報をコピーする場所を尋ねられるだけです。 型番
        assertTrue(lines.contains("### 診断情報"))
        assertTrue(lines.contains("(任意) Mika で「概要」›「フィードバックを送信」›「診断情報をコピー」を開き、ここに貼り付けてください"))
    }

    /** に既にメーカー名が記載されている場合は、再度記載しないでください。 */
    @Test
    fun modelNotDoubled() {
        val body = MikaLinks.issueBody(device.copy(manufacturer = "samsung", model = "SAMSUNG SM-S9180"))
        assertTrue(body.startsWith("- 端末: SAMSUNG SM-S9180\n"))
    }

    @Test
    fun issueUrlIsEncoded() {
        val url = MikaLinks.issueUrl(device)
        assertTrue(url.startsWith("https://github.com/yebisu0529-ship-it/Mika-JP/issues/new?title=&body="))
        val query = url.substringAfter('?')
        assertFalse("no raw spaces or newlines", query.any { it == ' ' || it == '\n' || it == '+' })
        assertFalse("no raw non-ASCII", query.any { it.code > 127 })
        // は正当な URI として解析でき、テキストをデコードして元の形式に戻すことができます。
        URI(url)
        val body = URLDecoder.decode(query.substringAfter("body="), "UTF-8")
        assertEquals(MikaLinks.issueBody(device), body)
    }
}
