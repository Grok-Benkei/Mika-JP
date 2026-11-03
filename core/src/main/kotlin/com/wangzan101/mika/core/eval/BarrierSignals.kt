package com.wangzan101.mika.core.eval

/**
 * 画面上に表示される障害物: ログインの壁、人間とマシンの検証用のそれぞれの単語 (ログイン ボックス、検証コードの入力、再ログイン ボタンなど)。
 * レビュー: モデルの言うことをただ聞くのではなく、画面上に実際にそのような単語がある場合にのみ、モデルによって報告された障害物 ([RunFacts.takeoverBarrier]) を認識します。
 */
object BarrierSignals {
    private val SIGNALS = mapOf(
        "login" to listOf(
            "获取验证码", "发送验证码", "短信验证码", "请输入密码", "密码登录", "验证码登录", "手机号登录", "扫码登录",
            "重新登录", "欢迎登录", "登录后查看", "sign in", "log in",
        ),
        "captcha" to listOf("人机验证", "安全验证", "滑块验证", "拖动滑块", "请完成验证", "验证您是真人", "captcha", "verify you are human", "not a robot"),
    )

    /** [texts] (1 つの画面上の要素テキスト) には、障害物を表す単語 [kind] が含まれています。認識されない障害物の種類は false です。 */
    fun shows(kind: String, texts: List<String>): Boolean {
        val words = SIGNALS[kind] ?: return false
        val text = texts.joinToString("\n").lowercase()
        return words.any { it in text }
    }
}
