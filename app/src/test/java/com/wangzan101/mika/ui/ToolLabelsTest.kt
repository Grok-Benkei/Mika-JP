package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.ToolInfo
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.i18n.JapaneseTexts
import com.wangzan101.mika.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolLabelsTest {
    @get:Rule val texts = JapaneseTexts()

    @Test fun everyDeclaredHumanLabelHasAResourceForItsStableIdentity() {
        val tools = knownTools()
        val labels = ToolLabels(tools)
        val declared = tools.names.mapNotNull { tools[it] }.filter { it.label != null }
        assertTrue(declared.size > 40)
        for (tool in declared) {
            val resource = ToolLabels.resourceFor(tool)
            assertNotNull("${tool.name}: ${tool.capability}/${tool.verb}", resource)
            assertEquals(tool.name, tr(resource!!), labels.label(tool.name))
        }
        assertEquals("タップ", labels.label("phone_tap"))
        // core の宣言とは異なる文言でも、日本語リソースを優先する。
        assertEquals("インストール済みアプリを一覧表示", labels.label("phone_list_apps"))
    }

    @Test fun changingOrRemovingDeclaredProseDoesNotChangeLocalizedLabels() {
        val original = knownTools()
        for (prose in listOf("別の宣言文", "旧声明文本", null)) {
            val changed = ToolIndex(original.names.map { name ->
                val tool = original[name]!!
                object : ToolInfo by tool { override val label: String? = prose }
            })
            val labels = ToolLabels(changed)
            for (name in original.names) {
                ToolLabels.resourceFor(original[name]!!)?.let { resource ->
                    assertEquals("$name ($prose)", tr(resource), labels.label(name))
                }
            }
        }
    }

    @Test fun waitsUseTheResourceForTheirCapability() {
        val labels = ToolLabels(knownTools())
        assertEquals(tr(R.string.tool_wait_condition), labels.label("phone_wait"))
        assertEquals(tr(R.string.tool_wait_page), labels.label("web_wait_for"))
        assertEquals(tr(R.string.tool_wait_page), labels.label("browser_wait"))
    }

    @Test fun savedLegacyNamesUseTheCurrentToolResource() {
        val tools = knownTools()
        val legacy = legacyNames()
        val labels = ToolLabels(tools, legacy)
        assertTrue(legacy.names.isNotEmpty())
        for (old in legacy.names) {
            val current = legacy.resolve(old)!!
            assertEquals("$old -> $current", labels.label(current), labels.label(old))
        }
        assertEquals(tr(R.string.tool_tap), labels.label("tap"))
        assertEquals(tr(R.string.tool_open_app), labels.label("launch_app"))
        assertEquals(tr(R.string.tool_open_page), labels.label("browser_navigate"))
    }

    @Test fun legacyResolutionSkipsReplacementsAbsentFromThisIndex() {
        val all = knownTools()
        val webOnly = ToolIndex(listOf(all["web_tap"]!!))
        assertEquals(tr(R.string.tool_tap), ToolLabels(webOnly).label("tap"))
    }

    @Test fun unknownToolsAndUnmappedDeclarationsKeepTheirFallbacks() {
        val labeled = object : ToolInfo {
            override val name = "custom_memory"
            override val verb = Verb.MemorySearch
            override val capability: String? = null
            override val batchable = false
            override val routable = false
            override val label: String? = "カスタム表示"
        }
        val unlabeled = object : ToolInfo by labeled {
            override val name = "custom_without_label"
            override val label: String? = null
        }
        val labels = ToolLabels(ToolIndex(listOf(labeled, unlabeled)))
        assertEquals("custom_unknown", labels.label("custom_unknown"))
        assertEquals("カスタム表示", labels.label(labeled.name))
        assertEquals(unlabeled.name, labels.label(unlabeled.name))
    }
}
