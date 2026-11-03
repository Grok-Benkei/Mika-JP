package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.ScreenState
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/** ポートで検証した事実（変更、切り取り、再配置、リードバック、アンチスクリーンショット、診断）をそのままモデルに引き継ぐ必要があります。 */
class ToolDispatcherAccuracyTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.b(k: String) = this[k]!!.jsonPrimitive.boolean

    private fun JsonObject.effect() = this["effect"]!!.jsonObject

    @Test fun unchangedTapReportsNoEffectAndAnotherWay() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = false) }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"a"}"""))
        assertEquals(false, r.changed)
        assertFalse(r.content.b("changed"))
        assertEquals("no_effect", r.content.effect().s("kind"))
        val h = r.content.s("hint")
        // は、見たとおりの事実のみを述べており、「的外れ」を示唆するものではありません。そのまま繰り返さず、別のアプローチを試してください
        assertFalse(h, "的外れ" in h)
        assertTrue(h, "同じ内容を繰り返さない" in h && "別の方法を試す" in h)
        // 変わらなければ「古い画面」とはみなされません
        assertNull(r.content["interfaceStale"])
    }

    @Test fun portChangeWithoutVisibleTreeChangeIsSmallChange() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true) }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":1,"y":1}"""))
        assertEquals(true, r.changed); assertTrue(r.content.b("changed"))
        assertEquals("small_change", r.content.effect().s("kind"))
        assertTrue(r.content.s("hint"), "意図したとおりになったか確認する" in r.content.s("hint"))
        // はアクション後に画面を元に戻せませんでした: モデルの手の画面は古いです
        assertTrue(r.content.b("interfaceStale"))
    }

    @Test fun portWithoutVerificationGetsChangedFromTheEffect() = runTest {
        val r = toolDispatcher(FakeScreen()).execute(call("phone_back"))
        // ポートは、変更されたかどうかを示しません。前面と背面の画面から判断すると、2 つの画面は同じですが、変更されていません。
        assertEquals(false, r.changed); assertNull(r.content["changed"])
        assertEquals("no_effect", r.content.effect().s("kind"))
    }

    @Test fun unchangedBackHasBackSpecificHint() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = false) }
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertTrue(r.content.s("hint"), "戻る操作を繰り返さない" in r.content.s("hint") && "ページ自体の戻るまたは閉じる操作" in r.content.s("hint"))
    }

    @Test fun appSwitchAndRelocationAreSurfaced() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true, appSwitched = "com.other", relocated = true) }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"a"}"""))
        assertEquals("com.other", r.content.s("app_switched"))
        assertTrue(r.content.b("relocated"))
        assertTrue("com.other" in r.content.s("hint") && "phone_launch_app" in r.content.s("hint"))
    }

    @Test fun relocatedTargetNameIsSurfaced() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true, relocated = true, relocatedTo = "跳过 2") }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"a"}"""))
        assertTrue(r.content.b("relocated")); assertEquals("跳过 2", r.content.s("relocatedTo"))
    }

    @Test fun typeTextCarriesRelocation() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(verification = Verification.MATCH, relocated = true, relocatedTo = "搜索框") }
        val r = toolDispatcher(s).execute(call("phone_type_text", """{"text":"hi","ref":"@e1","snapshotId":"a"}"""))
        assertTrue(r.content.b("relocated")); assertEquals("搜索框", r.content.s("relocatedTo"))
    }

    @Test fun appOpenedByTheTargetIsWordedSoftly() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true, appSwitched = "com.share", switchedByTarget = true) }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"a"}"""))
        assertEquals("com.share", r.content.s("app_switched"))
        assertTrue(r.content.b("opened_by_target"))
        val h = r.content.s("hint")
        assertTrue(h, "この作業が扱っているアプリによって開かれた" in h && "作業の一部であればそこで続ける" in h && "割り込み" !in h)
    }

    @Test fun appInUseByUserHasExplanation() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Failed("app_in_use_by_user") }
        val r = toolDispatcher(s).execute(call("phone_launch_app", """{"package":"com.x"}"""))
        assertEquals("app_in_use_by_user", r.content.s("error"))
        assertTrue("hand_to_user" in r.content.s("message"))
    }

    private class TypingScreen(val v: Verification?) : ScreenPort by FakeScreen() {
        val keys = mutableListOf<Int>()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = ActionResult.Ok(verification = v)
        override suspend fun pressKey(keyCode: Int): ActionResult { keys += keyCode; return ActionResult.Ok() }
    }

    @Test fun enterOnlyAfterVerifiedReadback() = runTest {
        val match = TypingScreen(Verification.MATCH)
        val r = toolDispatcher(match).execute(call("phone_type_text", """{"text":"WLAN","pressEnter":true}"""))
        assertEquals(listOf(66), match.keys)
        assertEquals("match", r.content.s("verification")); assertTrue(r.content.b("pressedEnter"))

        val unknown = TypingScreen(Verification.UNKNOWN)
        val u = toolDispatcher(unknown).execute(call("phone_type_text", """{"text":"WLAN","pressEnter":true}"""))
        assertTrue(unknown.keys.isEmpty())
        assertTrue(u.content.b("ok")); assertEquals("unknown", u.content.s("verification"))
        assertFalse(u.content.b("pressedEnter")); assertTrue(u.content.b("enterSkipped")); assertTrue("Enter" in u.content.s("hint"))

        val mismatch = TypingScreen(Verification.MISMATCH)
        val m = toolDispatcher(mismatch).execute(call("phone_type_text", """{"text":"WLAN","pressEnter":true}"""))
        assertTrue(mismatch.keys.isEmpty())
        assertFalse(m.content.b("ok")); assertEquals("action_not_applied", m.content.s("error"))
        assertEquals("mismatch", m.content.s("verification"))
    }

    private class FlagScreen(val changedDuringRead: Boolean, val prot: Boolean) : ScreenPort by FakeScreen() {
        override suspend fun snapshot() = ScreenSnapshot("p1", "com.x", emptyList(), 1080, 2400, changedDuringRead, prot)
        override suspend fun isProtected() = prot
    }

    @Test fun observeReportsChangedDuringReadAndProtected() = runTest {
        val plain = toolDispatcher(FlagScreen(false, false)).execute(call("phone_observe"))
        assertFalse(plain.content.b("screenChangedDuringRead")); assertNull(plain.content["protected"])

        val moving = toolDispatcher(FlagScreen(true, false)).execute(call("phone_list_elements"))
        assertTrue(moving.content.b("screenChangedDuringRead")); assertTrue("note" in moving.content)

        val prot = toolDispatcher(FlagScreen(false, true), visionEnabled = true).execute(call("phone_observe"))
        assertTrue(prot.content.b("protected")); assertTrue("hand_to_user" in prot.content.s("hint"))
        assertNull(prot.image) // 黒い画面の画像にはモデルがありません
    }

    @Test fun screenshotOfProtectedPage() = runTest {
        val r = toolDispatcher(FlagScreen(false, true), visionEnabled = true).execute(call("phone_screenshot"))
        assertFalse(r.content.b("ok")); assertEquals("protected_content", r.content.s("error"))
        assertTrue(r.content.b("protected")); assertNull(r.image)
    }

    @Test fun checkScreenUsesPortDiagnosis() = runTest {
        val s = object : ScreenPort by FakeScreen() {
            override suspend fun checkScreen() = ScreenCheck(ScreenState.PROTECTED, "com.bank", true, "other", "secure window")
        }
        val r = toolDispatcher(s).execute(call("phone_diagnose"))
        assertEquals("protected", r.content.s("state")); assertTrue(r.content.b("needsHuman"))
        assertEquals("other", r.content.s("takeoverReason")); assertFalse(r.content.b("usable"))
        assertTrue("hand_to_user" in r.content.s("hint"))
        assertEquals("com.bank", r.content.s("currentApp"))

        val normal = object : ScreenPort by FakeScreen() {
            override suspend fun checkScreen() = ScreenCheck(ScreenState.NORMAL, "com.x", false)
        }
        val n = toolDispatcher(normal).execute(call("phone_diagnose"))
        assertTrue(n.content.b("usable")); assertFalse(n.content.b("needsHuman")); assertNull(n.content["hint"])
    }

    @Test fun scrollDescriptionSaysPlainlyWhichWayIsWhich() {
        val spec = toolDispatcher(FakeScreen()).definitions().single { it.name == "phone_scroll" }
        val text = spec.description
        assertTrue(text, "direction=down はさらに下の内容を表示する" in text && "指が上へ移動する" in text)
        assertTrue(text, "up はさらに上の内容を表示する" in text && "指が下へ移動する" in text)
    }

    @Test fun progressNoteMetadataRequiresJapaneseRegardlessOfInputLanguage() {
        val spec = toolDispatcher(FakeScreen()).definitions().single { it.name == "phone_scroll" }
        val reason = Json.parseToJsonElement(spec.parametersJsonSchema).jsonObject["properties"]!!.jsonObject["reason"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(reason, "日本語で短く" in reason && "ステップの説明として表示" in reason)
        assertTrue(reason, "入力言語にかかわらず日本語" in reason)
        assertFalse(reason, "user's language" in reason)
    }

    @Test fun scrollResultSaysFingerDirectionAndEndWhenNothingMoved() = runTest {
        val moved = toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = true) })
            .execute(call("phone_scroll", """{"direction":"down"}""")).content
        assertEquals("up", moved.s("fingerMoved")); assertNull(moved["atEnd"]); assertNull(moved.effect()["kind"]?.takeIf { it.jsonPrimitive.content == "no_effect" })

        val stuck = toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = false) })
            .execute(call("phone_scroll", """{"direction":"up"}""")).content
        assertEquals("down", stuck.s("fingerMoved"))
        assertTrue(stuck.b("atEnd"))
        // 「これで終わり」という結論を出す前に、まず方向性を確認してください。 「クリックしなかった可能性があります」という文をクリックする必要はありません。
        assertTrue(stuck.s("hint"), "方向を確認する" in stuck.s("hint") && "down はさらに下" in stuck.s("hint") && "up はさらに上" in stuck.s("hint") && "的外れ" !in stuck.s("hint"))
    }

    private class EmptyScreen : ScreenPort by FakeScreen() {
        override suspend fun snapshot() = ScreenSnapshot("p1", null, emptyList(), 1080, 2400, hasWindow = false)
        override suspend fun checkScreen() = ScreenCheck(ScreenState.BLANK, null, false, cause = "no window")
    }

    /** にはアプリ ウィンドウがありますが、ルート ノードを読み取ることができません。これは空白の画面ではなく、phone_launch_app という名前にするべきではありません。 */
    private class UnreadableScreen : ScreenPort by FakeScreen() {
        override suspend fun snapshot() = ScreenSnapshot("p1", null, emptyList(), 1080, 2400, hasWindow = true)
        override suspend fun checkScreen() = ScreenCheck(ScreenState.BLANK, null, false, cause = "unreadable window")
    }

    @Test fun unreadableWindowIsNotCalledEmpty() = runTest {
        val d = toolDispatcher(UnreadableScreen())
        for (tool in listOf("phone_observe", "phone_diagnose")) {
            val h = d.execute(call(tool)).content["hint"]?.jsonPrimitive?.content.orEmpty()
            assertFalse("$tool: $h", "表示は空" in h)
        }
    }

    @Test fun emptyVirtualScreenSaysStartWithLaunchApp() = runTest {
        val d = toolDispatcher(EmptyScreen())
        for (tool in listOf("phone_observe", "phone_diagnose")) {
            val h = d.execute(call(tool)).content.s("hint")
            assertTrue("$tool: $h", "表示は空" in h && "phone_launch_app" in h && "障害ではない" in h)
        }
    }
}
