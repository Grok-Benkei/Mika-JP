package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.PageScriptProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** web_script の「読み取り不可能な値入力ボックス」と画面読み上げスクリプト page.js には同じルールがあります。つまり、両側のオートコンプリート リストが一貫している必要があります。 */
class PageScriptSecretRuleTest {
    private val pageJs = File("src/main/assets/web/page.js").readText()

    private fun tokens(name: String): Set<String> {
        val body = Regex("var $name = \\{([^}]*)\\}").find(pageJs)!!.groupValues[1]
        return Regex("'([^']+)'").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test fun autocompleteListMatchesPageScript() {
        assertEquals(tokens("PASSWORD_AUTOCOMPLETE") + tokens("SECRET_AUTOCOMPLETE"), PageScriptProtocol.SECRET_AUTOCOMPLETE.toSet())
    }
}
