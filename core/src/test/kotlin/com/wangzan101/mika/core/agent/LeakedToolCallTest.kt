package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeakedToolCallTest {
    // 実際のツールリストと同様に、wait や find などの一般的な単語でもあるツール名が 1 位にランクされます。
    private val tools = listOf("wait", "find", "ask_user", "web_read_page", "phone_observe")

    /** XML 呼び出しはサーバーによって解析されず、そのままのテキストになりました。 */
    @Test fun xmlStyleCallIsFound() {
        val text = "<tool_call>\n<function=ask_user>\n<parameter=question>\n想先确认一下:是要开户,还是了解集合竞价?\n</parameter>\n</function>\n</tool_call>"
        assertEquals("ask_user", LeakedToolCall.find(text, tools))
    }

    @Test fun jsonStyleCallIsFound() {
        val text = "<tool_call>\n{\"name\": \"web_read_page\", \"arguments\": {}}\n</tool_call>"
        assertEquals("web_read_page", LeakedToolCall.find(text, tools))
    }

    @Test fun otherMarkersAreFound() {
        assertEquals("phone_observe", LeakedToolCall.find("[TOOL_CALLS][{\"name\": \"phone_observe\", \"arguments\": {}}]", tools))
        assertEquals("phone_observe", LeakedToolCall.find("<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>phone_observe", tools))
    }

    /** ユーザーはこれらの形式について質問しました。その回答ではタグについて言及されていましたが、今回はツールについては言及されていませんでした。これは通常の回答です。 */
    @Test fun markupWithoutAKnownToolIsNotALeak() {
        assertNull(LeakedToolCall.find("有的模型写工具调用时用 `<tool_call>` 包起来,里面是 `<function=名字>`。", tools))
    }

    /** 回答にツール名が記載されているが (実行内容など)、呼び出しタグがない場合はカウントされません。 */
    @Test fun toolNameWithoutMarkupIsNotALeak() {
        assertNull(LeakedToolCall.find("我用 phone_observe 看过屏幕,安卓版本是 14。", tools))
    }

    /** マークの前の単語に出現するツールと同じ名前の一般的な単語 (待機、検索) はカウントされません。報告されるのは、マークの後に実際に呼び出されるツールです。 */
    @Test fun toolNameIsTakenAfterTheMarker() {
        val text = "Wait, let me find the page first.\n<tool_call>\n<function=web_read_page>\n</function>\n</tool_call>"
        assertEquals("web_read_page", LeakedToolCall.find(text, tools))
    }

    /** ツール名が別の単語の一部にすぎない場合、ツール名はそのツールを指しません。 */
    @Test fun toolNameMustBeAWholeWord() {
        assertNull(LeakedToolCall.find("<tool_call>{\"name\": \"ask_user_later\"}</tool_call>", tools))
        assertNull(LeakedToolCall.find("<tool_call>{\"name\": \"my_ask_user\"}</tool_call>", tools))
    }
}
