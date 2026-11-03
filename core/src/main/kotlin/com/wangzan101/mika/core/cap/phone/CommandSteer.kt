package com.wangzan101.mika.core.cap.phone

/**
 * システム コマンドを使用して特別なツールが実行すること (クリック、スライド、入力、キーを押す、アプリを開く) を実行すると、結果によってそのツールに戻るように通知されます。特別なツールはミカの画面で実行されます。
 * は完了後に効果を検証しますが、シェルによって送信された入力は検証されません。初めて通知されたときも、それは実行されます。このタスクがリマインドされると、これらの種類の処理を実行するコマンドは実行されなくなります。
 * はそのリマインダー ([refuses]) にのみ返信します。シェルを使用したテキストの共有は実行されず、テキストを共有するツールが参照されます。ステータスの確認や設定の読み取りなどのコマンドには影響しません。 コマンド
 */
internal object CommandSteer {
    enum class Purpose(val feedbackKey: String) {
        Gesture("system_command.use_tap"), Type("system_command.use_type"), Key("system_command.use_key"), Launch("system_command.use_launch"),
        Share("system_command.use_share"),
    }

    /** [purpose] は今回は実行されません。共有は実行されません。このタスクが通知された後は残りは実行されません ([steered])。 */
    fun refuses(purpose: Purpose?, steered: Boolean): Boolean = purpose == Purpose.Share || (purpose != null && steered)

    /** コマンドの各セクションは、`input …` のサブコマンドです (入力ソースと -d 画面番号を前に付けることができます)。 */
    private val INPUT = Regex("""(?:^|[;&|(\s])input\s+(?:(?:touchscreen|touchpad|keyboard|mouse|stylus|trackball|dpad|gamepad|joystick|rotaryencoder|touchnavigation)\s+)?(?:-d\s+\S+\s+)?([a-z]+)""")
    private val LAUNCH = Regex("""(?:^|[;&|(\s])(?:am\s+(?:start|start-activity)\b|cmd\s+activity\s+start(?:-activity)?\b|monkey\s+(?:.*\s)?-p\s)""")
    private val GESTURES = setOf("tap", "swipe", "draganddrop", "motionevent", "press", "roll")
    /** は共有を開始します: すべてのアクション (android.intent.action.SEND) を書き込むか、-a SEND だけを書きます。 */
    private val SHARE = Regex("""(?:android\.intent\.action\.SEND|-a\s+["']?SEND)(?![A-Za-z_])""")
    /** はファイルを共有しています (STREAM と): テキスト共有ツールは使用できず、通常の起動としてカウントされます。 */
    private const val STREAM = "android.intent.extra.STREAM"

    /**
     * コマンドがエミュレートしている特殊なツールのタイプ。どちらも null ではありません。入力とクリックが同じコマンド内にある場合、それらは入力としてカウントされます (ここで話したいのは、入力時に何を使用するかについてです)。
     * 最初にテキストを共有します: 実行されることはありません。
     */
    fun purpose(command: String): Purpose? {
        val subs = INPUT.findAll(command).map { it.groupValues[1] }.toSet()
        val launches = LAUNCH.containsMatchIn(command)
        return when {
            launches && SHARE.containsMatchIn(command) && STREAM !in command -> Purpose.Share
            "text" in subs -> Purpose.Type
            subs.any { it in GESTURES } -> Purpose.Gesture
            subs.any { it == "keyevent" || it == "keycombination" } -> Purpose.Key
            launches -> Purpose.Launch
            else -> null
        }
    }
}
