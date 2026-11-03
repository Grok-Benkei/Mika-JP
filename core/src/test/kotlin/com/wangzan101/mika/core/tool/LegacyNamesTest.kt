package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.MemoryContext
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.withCurrentToolNames
import com.wangzan101.mika.core.skills.InMemoryPersonalSkills
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.SkillStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 名称変更前の物を読み込むと、旧名称対照表に従い現在の名称に置き換えられます。古い名前を呼び出すとプロンプトが表示されるだけで、モデルは実行されません。 */
class LegacyNamesTest {
    @Test fun unknownLegacyCallNamesTheReplacementAndDoesNothing() = runTest {
        val screen = FakeScreen()
        val d = toolDispatcher(screen, web = FakeWebPort())
        val r = d.execute(Part.ToolCall("c", "tap", """{"x":1,"y":1}""")).content
        assertEquals("unknown_tool", r["error"]!!.jsonPrimitive.content)
        val m = r["message"]!!.jsonPrimitive.content
        assertTrue(m, "phone_tap または web_tap" in m && "名前が変更された" in m && "何も行われなかった" in m)
        assertTrue(screen.calls.isEmpty())
        // 携帯電話のみを提供する場合は、携帯電話のみを記載します。
        val phoneOnly = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "browser_navigate", "{}")).content
        assertFalse(phoneOnly["message"]!!.jsonPrimitive.content.contains("名前が変更された"))
        assertTrue(phoneOnly["message"]!!.jsonPrimitive.content.contains("不明な手段"))
        val launch = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "launch_app", "{}")).content["message"]!!.jsonPrimitive.content
        assertTrue(launch, "phone_launch_app" in launch)
    }

    // 仕上げの削除: 保存されたスキルと練習で呼び出される場所は、現在の練習に置き換えられます。モデルが調整したら、すぐに終了する方法を説明します。
    @Test fun retiredFinishIsRewrittenAndExplained() = runTest {
        val legacy = legacyNames()
        val zh = legacy.rewrite("查完后调用 finish 交结果")
        assertFalse(zh, "finish" in zh)
        assertTrue(zh, "ツールを呼び出さず、回答を通常の文章でユーザーへ返してください" in zh)
        val en = legacy.rewrite("When done, call `finish` with the price. The page may finish loading slowly.")
        assertTrue(en, "When done, ツールを呼び出さず、回答を通常の文章でユーザーへ返してください" in en)
        // は、移動するためにツールの終了 (英語の動詞) を呼び出していません。
        assertTrue(en, "may finish loading" in en)
        val r = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "finish", """{"answer":"x"}""")).content
        val m = r["message"]!!.jsonPrimitive.content
        assertEquals("unknown_tool", r["error"]!!.jsonPrimitive.content)
        assertTrue(m, "手段 'finish' はもうない" in m && "ツールを呼び出さず" in m && "何も行われなかった" in m)
    }

    // Phone_ask_app_ai を削除: 古いレコードと古いスキルで呼び出された場所が現在のメソッドに置き換えられました。モデルがそれを調整したとき、それはもう存在しないと明確に述べられました。
    @Test fun retiredAskAppAiIsRewrittenAndExplained() = runTest {
        val zh = legacyNames().rewrite("先 `phone_ask_app_ai` 问一下")
        assertFalse(zh, "phone_ask_app_ai" in zh)
        assertTrue(zh, "アプリの画面を自分で読み取ってください（アプリ内AIへの質問機能は利用できません）" in zh)
        val m = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "phone_ask_app_ai", "{}")).content["message"]!!.jsonPrimitive.content
        assertTrue(m, "手段 'phone_ask_app_ai' はもうない" in m && "アプリ内AIへの質問機能は利用できません" in m)
    }

    @Test fun storedRoutesGetTheRoutableName() {
        val none = Anchors(null, emptyList())
        val r = Route("r", "t", 0, listOf("com.a"), emptyMap(), listOf(
            RouteStep("launch_app", "{}", null, none, none), RouteStep("tap", "{}", null, none, none), RouteStep("phone_back", "{}", null, none, none),
        ), lastUsedMs = 1)
        assertEquals(listOf("phone_launch_app", "phone_tap", "phone_back"), r.withCurrentToolNames(legacyNames(), knownTools()).steps.map { it.tool })
    }

    @Test fun personalSkillsAndProceduresAreRewrittenWhenLoaded() {
        val md = "---\nname: notes\ndescription: d\n---\n## 步骤\n1. 用 `launch_app` 打开,`tap` 新建"
        val catalog = GuideCatalog.withPersonal(com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.catalog(), InMemoryPersonalSkills(PersonalSkill("notes", md, SkillStatus.active, null, null, 0, emptyList())), legacyNames()::rewrite)
        val text = catalog.fullText(catalog.items.first { it.name == "notes" })!!
        assertTrue(text, "`phone_launch_app`" in text && "`phone_tap` / `web_tap`" in text)
        val item = MemoryItem("m1", MemoryKind.procedure, "打开备忘录先 launch_app 再搜索", listOf("备忘录"), MemorySource("manual", atMs = 0), MemoryStatus.active, pinned = true, createdAt = 0, updatedAt = 0)
        val block = MemoryContext.build(listOf(item), "打开备忘录", null, legacyNames()::rewrite)
        assertTrue(block.message.orEmpty(), "phone_launch_app" in block.message.orEmpty())
    }

    // 2 番目の名前変更: 変更された名前全体を使用して古い名前を呼び出すと、新しい名前を求めるプロンプトが表示され、テキスト内の単語が新しい名前に置き換えられます。
    @Test fun movedNamesAreHintedAndRewritten() = runTest {
        val d = toolDispatcher(FakeScreen(), web = FakeWebPort())
        fun hint(name: String) = d.legacy.replacements(name)
        assertEquals(listOf("end_not_done"), hint("report_failure"))
        assertEquals(listOf("hand_to_user"), hint("user_takeover"))
        assertEquals(listOf("ask_user"), hint("request_input"))
        assertEquals(listOf("handle_obstacle"), hint("report_barrier"))
        assertEquals(listOf("view_past_screens"), hint("recall_screenshot"))
        assertEquals(listOf("run_bundled_script"), hint("run_script"))
        assertEquals(listOf("phone_list_elements"), hint("phone_dump_elements"))
        assertEquals(listOf("web_diagnose"), hint("web_check_screen"))
        // 最初の世代のいくつかの画面で共有されている名前は、現在の画面に直接関連しています。
        assertEquals(setOf("phone_find_visually", "web_find_visually"), hint("locate_on_screen").toSet())
        assertEquals(setOf("phone_describe", "web_describe"), hint("screen_description").toSet())
        val m = d.execute(Part.ToolCall("c", "report_failure", """{"message":"x"}""")).content["message"]!!.jsonPrimitive.content
        assertTrue(m, "end_not_done" in m && "名前が変更された" in m)
        val text = legacyNames().rewrite("先 phone_dump_elements,不行就调用 `user_takeover`,再 phone_check_screen")
        assertEquals("先 phone_list_elements,不行就调用 `hand_to_user`,再 phone_diagnose", text)
    }

    // は古い名前で、知識とスキルの 2 つの部分に分かれています。種類または名前の接頭辞で対応するものを選択し、区別できない場合は両方について言及してください。
    @Test fun splitNamesPickByKind() = runTest {
        val d = toolDispatcher(FakeScreen(), skills = com.wangzan101.mika.core.knowledge.OptionalChinaKnowledge.catalog())
        suspend fun message(name: String, args: String) = d.execute(Part.ToolCall("c", name, args)).content["message"]!!.jsonPrimitive.content
        val app = message("find_skills", """{"query":"外卖","kind":"app"}""")
        assertTrue(app, "knowledge_search" in app && "skill_search" !in app)
        val skill = message("find_skills", """{"query":"文档","kind":"skill"}""")
        assertTrue(skill, "skill_search" in skill && "knowledge_search" !in skill)
        val either = message("find_skills", """{"query":"外卖"}""")
        assertTrue(either, "knowledge_search または skill_search" in either)
        val site = message("skill_view", """{"name":"site:jd.com"}""")
        assertTrue(site, "knowledge_read" in site && "skill_read" !in site)
        val personal = message("skill_view", """{"name":"personal:my-trip"}""")
        assertTrue(personal, "skill_read" in personal && "knowledge_read" !in personal)
        val bare = message("skill_view", """{"name":"meituan"}""")
        assertTrue(bare, "knowledge_read または skill_read" in bare)
        // 保存したテキストではどちらなのか分からないので両方書いてください。
        assertEquals("用 `knowledge_read` / `skill_read` 看全文", legacyNames().rewrite("用 `skill_view` 看全文"))
    }

    // 名前変更前に記録された実行記録: 古い名前が現在のツールとして認識されます
    @Test fun recordedOldNamesResolve() {
        val tools = com.wangzan101.mika.core.assembly.recordedTools()
        assertEquals(Verb.EndNotDone, tools.verb("report_failure"))
        assertEquals(Verb.ListElements, tools.verb("phone_dump_elements"))
        assertEquals(Verb.ListElements, tools.verb("dump_elements"))
        assertEquals(Verb.HandToUser, tools.verb("user_takeover"))
    }
}
