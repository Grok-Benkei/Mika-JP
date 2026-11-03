package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind

/**
 * 一般規則: SystemUI のパスワード マークが付いた入力ボックスのみが認識されます。編集ボックスでは、入力タイプに応じて PIN (数値) とパスワード (数値以外) が区別されます。
 * オリジナルの Android ロック画面の PIN ボックスは私が描いたものです (`pinEntry` / `PasswordTextView`)。編集可能とは宣言されておらず、元の名前セットに従ってのみリリースされます。
 * 編集ボックスが入力タイプ (TYPE_NULL、キーボードはページ自体によって描画される) を宣言していない場合、同じウィンドウ内に 0 ～ 9 の完全な数字キーがあるかどうかを確認します。存在する場合、それは PIN ページであり、パスワード ページはカウントされません。
 * 他のすべての編集不可能なコントロールと SIM PIN は認識されません。よくわからない場合は、入力しないでください。
 */
internal object KeyguardFields {
    fun matches(kind: CredentialKind, id: String, className: String, password: Boolean, editable: Boolean, numeric: Boolean,
        untyped: Boolean = false, digitPad: Boolean = false): Boolean {
        if (!password || !id.startsWith("com.android.systemui:id/") || "sim" in id.lowercase()) return false
        val pinView = id == "com.android.systemui:id/pinEntry" && className == "com.android.keyguard.PasswordTextView"
        val pinField = numeric || untyped && digitPad
        return when (kind) {
            CredentialKind.Pin -> pinView || editable && pinField
            CredentialKind.Password -> editable && !pinField
        }
    }

    /** コントロールのテキストまたは説明が数字である場合にこの数字を返します (「2 ABC」などの文字の発音表記を持つことができます)。数字キーボードのキーを識別するために使用されます。 */
    fun digitKey(text: String?, description: String?): Char? =
        listOfNotNull(text, description).firstNotNullOfOrNull { s ->
            s.trim().takeIf { it.isNotEmpty() && it[0] in '0'..'9' && (it.length == 1 || !it[1].isLetterOrDigit()) }?.get(0)
        }

    /** 0 ～ 9 は、キーがある場合のみ数字キーボードです。 */
    fun isDigitPad(keys: Set<Char>): Boolean = ('0'..'9').all { it in keys }
}
