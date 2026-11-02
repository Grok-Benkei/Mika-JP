package com.wangzan101.mika.confirm

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationCopyTest {
    @get:Rule val texts = ChineseTexts()

    private fun req(content: String = "明天见", amount: String? = null, action: IrrevAction = IrrevAction.send, target: String = "张三") =
        ConfirmationRequest(action, target, content, amount, "com.chat")

    @Test fun longContentEllipsized() {
        val long = "字".repeat(80)
        val body = ConfirmationCopy.body(req(content = long), "聊天")
        assertTrue(body, body.endsWith("内容: " + "字".repeat(60) + "…"))
        // モデルに書き込まれるフィールドはプレーン テキストとして扱われます。改行はスペースに挿入され、制御文字と方向制御文字は削除され、先頭と末尾の空白は削除されます。
        val odd = ConfirmationCopy.body(req(content = "  第一行\n第二行‮反向<b>粗</b>\u0007 ", target = " 张三\n"), "聊天")
        assertEquals("聊天 で · 宛先: 张三 · 内容: 第一行 第二行反向<b>粗</b>", odd)
        assertFalse(odd.contains('\n'))
    }

    // 修正 1 M1: フィールド内の区切り文字、コロン、行区切り文字、およびゼロ幅文字は偽のフィールドを作成できない
    @Test fun fieldsCannotFakeStructure() {
        val r = req(content = "明天见 · 金额:¥0\u2028第二段\u200B尾", target = "张三：李四")
        assertEquals("聊天 で · 宛先: 张三∶李四 · 内容: 明天见 / 金额∶¥0 第二段尾", ConfirmationCopy.body(r, "聊天"))
    }

    // Repair 1 I3: 宣言された各ボタンは独自の PendingIntent (異なるデータ) を使用し、古い通知ボタンは新しく宣言されたボタンに置き換えられません。
    @Test fun intentDataUniquePerRequest() {
        assertTrue(ConfirmationNotifier.intentData(1, true) != ConfirmationNotifier.intentData(2, true))
        assertTrue(ConfirmationNotifier.intentData(1, true) != ConfirmationNotifier.intentData(1, false))
    }
}

