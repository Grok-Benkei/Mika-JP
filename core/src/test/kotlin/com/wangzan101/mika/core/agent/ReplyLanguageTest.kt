package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.agent.ReplyLanguage.CHINESE
import com.wangzan101.mika.core.agent.ReplyLanguage.JAPANESE
import com.wangzan101.mika.core.agent.ReplyLanguage.Mismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyLanguageTest {
    private val latin = ReplyLanguage.Family("ラテン文字")
    private val japanese = "設定を開いて確認しました。Androidのバージョンは14で、ストレージは250ギガバイト使われています。"
    private val chinese = "已经打开设置看过了：安卓版本是14，存储已使用250 GB，总共512 GB，剩余261 GB，两个页面的数字对得上。"
    private val english = "I opened Settings and checked both pages. The phone runs Android 14, and 250 GB of 512 GB is used."

    @Test fun targetIsJapaneseRegardlessOfTheInputLanguageOrLength() {
        for (user in listOf("", "14", "続けて", "帮我看看设置", "Please answer in English")) {
            assertNull(ReplyLanguage.mismatch(user, japanese))
            assertEquals(Mismatch(CHINESE, JAPANESE), ReplyLanguage.mismatch(user, chinese))
            assertEquals(Mismatch(latin, JAPANESE), ReplyLanguage.mismatch(user, english))
        }
    }

    @Test fun shortForeignSentencesAreAlsoRejected() {
        for (answer in listOf("Bluetooth is on.", "I cannot do that", "Sorry.", "蓝牙已开启。", "已经停止。", "已經完成。", "藍牙已開啟。", "你好",
            "付好了", "好了", "好的", "写好了", "发好了", "点好了", "算好了", "看完了", "做完了", "没发", "知道了", "谢谢")) {
            assertEquals(answer, JAPANESE, ReplyLanguage.mismatch(answer)?.target)
        }
    }

    @Test fun japaneseIntroDoesNotHideAChineseParagraph() {
        assertEquals(Mismatch(CHINESE, JAPANESE), ReplyLanguage.mismatch("確認しました。\n$chinese"))
        assertEquals(Mismatch(latin, JAPANESE), ReplyLanguage.mismatch("確認した内容を日本語で説明します。".repeat(30) + "\nBluetooth is on."))
    }

    @Test fun japaneseCanQuoteForeignNamesLabelsAndOriginalSentences() {
        val answer = "「设置」の「关于手机」で確認しました。表示は「Android 版本:14」です。" +
            "原文は \"The phone runs Android 14 and all updates have already been installed.\" でした。"
        assertNull(ReplyLanguage.mismatch(answer))
    }

    @Test fun bareProperNamesAndProtocolValuesArePreserved() {
        for (answer in listOf("OpenAI", "New York", "Visual Studio Code", "Node.js", "Google 地图", "東京", "微信", "完了", "了解", "友好的", "支払完了", "DISCUSS", "ok", "interaction_failed", "- Google Play\n- LINE\n- 淘宝")) {
            assertNull(answer, ReplyLanguage.mismatch(answer))
        }
    }

    @Test fun codeUrlsAndQuotesAreNotLanguageChecked() {
        val onlySource = "```python\nprint('This is the exact program requested by the user')\n```\n" +
            "`interaction_failed` https://example.com/english-path 「已经停止。没有发出任何消息。」"
        assertNull(ReplyLanguage.mismatch(onlySource))
        for (payload in listOf("「付好了」", "`付好了`", "```text\n付好了\n```", "https://example.com/付好了", "operation_done")) {
            assertNull(payload, ReplyLanguage.mismatch(payload))
        }
        assertNull(ReplyLanguage.mismatch("原文です。\n「The original first line is in English.\nThe second line must also stay unchanged.」"))
        assertNull(ReplyLanguage.mismatch("> The original source says that this is the complete answer.\n> This quoted second line is also source data."))
        assertNull(ReplyLanguage.mismatch("~~~json\n{\"original\":\"This protocol payload must remain unchanged\"}\n~~~"))
        val stripped = ReplyLanguage.stripQuoted("看 `设置页面` 和 https://例子.com/路径 还有 “引号里” \"双引号\" 《书名》 （外（里）层）<标签>")
        assertEquals(setOf("看", "和", "还有"), stripped.split(Regex("\\s+")).filter(String::isNotBlank).toSet())
    }

    @Test fun unitsAreFairAcrossScripts() {
        assertEquals(mapOf(CHINESE to 4), ReplyLanguage.units("打开设置", skipNames = false))
        assertEquals(mapOf(latin to 2), ReplyLanguage.units("Open Settings 512 GB OS", skipNames = false))
        assertEquals(mapOf(ReplyLanguage.KOREAN to 2), ReplyLanguage.units("설정 열기", skipNames = false))
        assertEquals(JAPANESE, ReplyLanguage.units(japanese, skipNames = false).maxBy { it.value }.key)
    }
}
