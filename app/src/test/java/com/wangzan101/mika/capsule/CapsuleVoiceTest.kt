package com.wangzan101.mika.capsule

import androidx.compose.ui.geometry.Rect
import com.wangzan101.mika.cap.phone.a11y.HoldGesture
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** カプセルを長押しして話す: 指を離したときに指がどの円に当たるか、長押しとドラッグの見分け方、話した後にカプセル上で点滅する文章。 */
class CapsuleVoiceTest {
    @get:Rule val texts = ChineseTexts()

    private val cancel = Rect(100f, 200f, 162f, 262f)
    private val edit = Rect(300f, 200f, 362f, 262f)

    @Test fun insideCancelCircleIsCancel() {
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun justOutsideCancelCircleStillCancels() {
        // 円の外側 10px、円の外側 14px 以内
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(90f, 230f, cancel, edit, slopPx = 14))
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 272f, cancel, edit, slopPx = 14))
    }

    @Test fun farFromBothCirclesIsSend() {
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(230f, 900f, cancel, edit, slopPx = 14))
        // 2 つの円の中心が十分に近くありません。
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(231f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun insideEditCircleIsEdit() {
        assertEquals(CapsuleVoice.Drop.Edit, CapsuleVoice.drop(330f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun circlesNotLaidOutYetMeanSend() {
        // パネルが未配置（サークルの位置が未報告）の場合、誤ってキャンセルと判断されることはありません。
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(0f, 0f, Rect.Zero, Rect.Zero, slopPx = 14))
    }

    @Test fun smallMoveThenTimeoutIsHold() {
        val g = HoldGesture(slopPx = 8f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Tap, g.move(5f))
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
        // 長押し後、どのように移動しても長押しされたままになり、ドラッグになりません。
        assertEquals(HoldGesture.Kind.Hold, g.move(200f))
    }

    @Test fun movedPastSlopFirstIsDragAndNeverHold() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        assertEquals(HoldGesture.Kind.Drag, g.move(20f))
        assertEquals(HoldGesture.Kind.Drag, g.timeout())
        assertEquals(HoldGesture.Kind.Drag, g.move(2f))
    }

    @Test fun newDownStartsOver() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        g.move(20f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
    }

    @Test fun sentNoteSaysWhatHappenedWithTheFirstWords() {
        assertEquals("タスクに伝えました: 要下午三点以后的，二等座…", CapsuleVoice.sent(CardInputKind.Steer, "要下午三点以后的，二等座靠窗"))
        assertEquals("回答しました: 明天上午", CapsuleVoice.sent(CardInputKind.Answer, "明天上午"))
        assertEquals("送信しました: 查一下天气", CapsuleVoice.sent(CardInputKind.Continue, "查一下天气"))
        assertEquals("送信しました: 查一下天气", CapsuleVoice.sent(CardInputKind.Start, "查一下天气"))
    }
}
