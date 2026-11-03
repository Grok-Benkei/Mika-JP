package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.knowledge.RECOMMEND_MIN_SCORE
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.GuideView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCatalogMergeTest {
    private val bundled = com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.catalog()
    private fun merged(vararg s: PersonalSkill) = GuideCatalog.withPersonal(bundled, InMemoryPersonalSkills(*s))

    @Test fun personalOverridesBundled() {
        val c = merged(personalSkill("xianyu", body = "## 入口\n我的闲鱼做法", display = "我的闲鱼"))
        val xianyus = c.items.filter { it.name == "xianyu" }
        assertEquals(listOf(PERSONAL_KIND), xianyus.map { it.kind })
        val v = c.view("xianyu") as GuideView.Found
        assertEquals(PERSONAL_KIND, v.info.kind)
        assertEquals("## 入口\n我的闲鱼做法", v.content)
        assertTrue(c.view("personal:xianyu") is GuideView.Found)
        assertEquals("skill_not_found", (c.view("app:xianyu") as GuideView.Failed).error)
        // は他の組み込みスキルには影響しません
        assertTrue(c.view("app:wechat") is GuideView.Found)
        assertEquals(bundled.items.size, c.items.size)
    }

    @Test fun invalidPersonalFailsLoudly() {
        val broken = PersonalSkill("xianyu", "没有头部的乱码", SkillStatus.active, null, null, 0L, emptyList())
        val c = merged(broken)
        assertEquals("personal_skill_invalid", (c.view("xianyu") as GuideView.Failed).error)
        assertNull(c.fullText(c.items.first { it.name == "xianyu" }))
        // 名前が一致せず、テキストが空の場合は、不良とみなされます。
        assertEquals("personal_skill_invalid", (merged(personalSkill("xianyu").copy(name = "xianyu2")).view("xianyu2") as GuideView.Failed).error)
        assertEquals("personal_skill_invalid", (merged(personalSkill("my-x", body = "  ")).view("my-x") as GuideView.Failed).error)
    }

    @Test fun pendingAndSuspendedHidden() {
        val store = InMemoryPersonalSkills(personalSkill("xianyu", status = SkillStatus.suspended), personalSkill("my-trip"))
        store.putPending("d1", personalSkill("my-draft"))
        val c = GuideCatalog.withPersonal(bundled, store)
        assertTrue(c.find("my-draft").isEmpty())
        assertEquals("skill_not_found", (c.view("my-draft") as GuideView.Failed).error)
        // 無効化された個人スキルは組み込みのスキルを置き換えません
        assertEquals(listOf("app"), c.items.filter { it.name == "xianyu" }.map { it.kind })
        assertEquals(listOf("my-trip"), c.find("my-trip").map { it.name })
        // 受信後すぐに表示されます
        store.approve("d1")
        assertEquals(listOf("my-draft"), c.find("my-draft").map { it.name })
    }

    @Test fun minScoreFilters() {
        val c = merged(personalSkill("family-msg", description = "给家人发消息"))
        assertEquals(listOf("family-msg"), c.find("家人", kind = PERSONAL_KIND).map { it.name })
        assertTrue(c.find("家人", kind = PERSONAL_KIND, minScore = RECOMMEND_MIN_SCORE).isEmpty())
        // 表示名/エイリアスが対象文全体に含まれている：一部正解 テスト用の
        assertTrue("xianyu" in c.find("帮我用闲鱼查去公司的路线", minScore = RECOMMEND_MIN_SCORE).map { it.name })
        assertFalse(bundled.find("帮我查一下明天的天气怎么样啊", minScore = RECOMMEND_MIN_SCORE).any { it.name == "xianyu" })
    }
}
