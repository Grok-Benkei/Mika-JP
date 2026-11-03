package com.wangzan101.mika.cap.phone.a11y

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.a11y.WebEditorTestActivity
import java.io.File
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機テスト: Web ページのリッチテキスト編集領域。アクセス可能な SET_TEXT は Web ページ内のテキストを変更するだけであり、エディターは入力イベントを受け取ることができません。
 * アプリは入力がないと判断します (たとえば、[完了] ボタンが点灯せず、終了時に保存されません)。入力では、エディターが実際の入力を受信できるようにする必要があります。
 */
@RunWith(AndroidJUnit4::class)
class WebEditorInputTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private fun snapUntil(timeoutMs: Long = 8_000, test: (ScreenSnapshot) -> Boolean): ScreenSnapshot = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.elements.map { it.name }}", test(s))
        s
    }

    private fun status(s: ScreenSnapshot) = s.elements.firstOrNull { it.name.startsWith("inputs=") }?.name

    @Test
    fun typingReachesTheWebEditor() = runBlocking<Unit> {
        DeviceSupport.startTestActivity(agent.display.displayId, WebEditorTestActivity::class.java)
        val s = snapUntil { snap -> snap.elements.any { it.editable } }
        val field = s.elements.first { it.editable }

        val r = screen.typeText(field.ref, s.snapshotId, "你好 Mika")
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
        val after = snapUntil { snap -> status(snap)?.endsWith("text=你好 Mika") == true }
        assertTrue(status(after)!!, !status(after)!!.startsWith("inputs=0"))

        // 再度電話をかけると、接続ではなく交換になります。
        val again = screen.typeText(after.elements.first { it.editable }.ref, after.snapshotId, "第二次")
        assertEquals(Verification.MATCH, (again as ActionResult.Ok).verification)
        val replaced = snapUntil { snap -> status(snap)?.endsWith("text=第二次") == true }

        val cleared = screen.clearText(replaced.elements.first { it.editable }.ref, replaced.snapshotId)
        assertTrue("$cleared", cleared is ActionResult.Ok)
        snapUntil { snap -> status(snap)?.endsWith("text=") == true }
    }

    /**
     * ユーザーが最初にコピーしたコンテンツ (独自のタグ付き) は、入力後にそのまま返されます。ミカのインターフェースはホーム画面で開かれ、テスト プロセスがクリップボードを読み取ることができます。
     * が復元されたら、ホーム画面 (files/shots/clip-restored.png) のスクリーンショットを撮り、「コピーされた」オーバーレイがポップアップしていないことを手動で確認します。
     */
    @Test
    fun pastingRestoresTheUsersClipboard() = runBlocking<Unit> {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            instr.runOnMainSync { cm.setPrimaryClip(ClipData.newPlainText("user-label", "用户原来复制的 123")) }

            DeviceSupport.startTestActivity(agent.display.displayId, WebEditorTestActivity::class.java)
            val s = snapUntil { snap -> snap.elements.any { it.editable } }
            val r = screen.typeText(s.elements.first { it.editable }.ref, s.snapshotId, "粘贴测试")
            // 復元が発生しました: 「コピーされた」オーバーレイがポップアップ表示される場合、その時点ではホーム画面に表示されます。
            val shot = instr.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
            File(ctx.getExternalFilesDir(null), "shots").mkdirs()
            File(ctx.getExternalFilesDir(null), "shots/clip-restored.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 80, it) }
            assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
            snapUntil { snap -> status(snap)?.endsWith("text=粘贴测试") == true }

            Thread.sleep(1500)
            var clip: ClipData? = null
            instr.runOnMainSync { clip = cm.primaryClip }
            assertEquals("user-label", clip?.description?.label?.toString())
            assertEquals("用户原来复制的 123", clip?.getItemAt(0)?.text?.toString())

        }
    }
}
