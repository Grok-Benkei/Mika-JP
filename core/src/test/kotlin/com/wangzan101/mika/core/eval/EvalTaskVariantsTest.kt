package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalTaskVariantsTest {
    private val json = """
        {"id": "calc", "goal": "用计算器算 123×456", "apps": ["calc.app"], "category": "read", "expect": "succeeded",
         "checks": [{"type": "answer_regex", "pattern": "56,?088"}],
         "variants": [
           {"goal": "打开计算器帮我算一下 123×456 等于多少"},
           {"goal": "用计算器算 321×654", "checks": [{"type": "answer_regex", "pattern": "209,?934"}]}
         ]}
    """.trimIndent()

    // バリアント チェック項目を書かない場合は、元の質問を使用してください。チェック項目を記述する場合は、独自のものを使用してください。番号は元の質問の後にあります
    @Test fun parsesVariantsAndInheritsChecks() {
        val t = EvalTasks.parse(json)
        assertEquals(2, t.variants.size)
        val vs = EvalTasks.variantsOf(t)
        assertEquals(listOf("calc~v1", "calc~v2"), vs.map { it.id })
        assertEquals("打开计算器帮我算一下 123×456 等于多少", vs[0].goal)
        assertEquals(t.checks, vs[0].checks)
        assertEquals(listOf<Check>(Check.AnswerRegex("209,?934")), vs[1].checks)
        assertEquals(t.apps, vs[1].apps)
        assertTrue(vs.all { it.variants.isEmpty() })
        assertTrue(EvalTasks.validate(t).isEmpty())
        // バリエーションのない質問はそのままです
        assertEquals(emptyList<EvalTask>(), EvalTasks.variantsOf(t.copy(variants = emptyList())))
    }

    @Test fun blankVariantGoalIsInvalid() {
        val t = EvalTasks.parse(json)
        val bad = t.copy(variants = t.variants + Variant(" ", null))
        assertTrue(EvalTasks.validate(bad).any { "variant" in it })
    }
}
