package com.wangzan101.mika.core.uistate

/** は、プロトコルでのキーの記述方法です。 [androidKeyCode] は、DevTools がない場合にネイティブ キーに使用されるキー コードです。 */
data class CdpKey(val key: String, val code: String, val keyCode: Int, val text: String?, val androidKeyCode: Int)

enum class KeyModifier(val bit: Int, val key: CdpKey, val androidMeta: Int) {
    Alt(1, CdpKey("Alt", "AltLeft", 18, null, 57), 0x02 or 0x10),
    Control(2, CdpKey("Control", "ControlLeft", 17, null, 113), 0x1000 or 0x2000),
    Meta(4, CdpKey("Meta", "MetaLeft", 91, null, 117), 0x10000 or 0x20000),
    Shift(8, CdpKey("Shift", "ShiftLeft", 16, null, 59), 0x01 or 0x40),
}

/** キーの組み合わせ (Ctrl+A など): 変更キーは、[modifiers] の順序で押され、逆の順序で上げられます。 */
data class KeyCombo(val modifiers: List<KeyModifier>, val key: CdpKey) {
    val bits: Int get() = modifiers.fold(0) { acc, m -> acc or m.bit }
    val androidMeta: Int get() = modifiers.fold(0) { acc, m -> acc or m.androidMeta }

    /** このキーを押すと生成される実際の単語: Ctrl/Alt/Meta を使用すると単語は生成されず (ショートカット キーです)、Shift を使用すると文字が大文字になります。 */
    val text: String? get() = when {
        modifiers.any { it != KeyModifier.Shift } -> null
        KeyModifier.Shift in modifiers && key.text != null && key.text.length == 1 && key.text[0].isLetter() -> key.text.uppercase()
        else -> key.text
    }

    override fun toString() = (modifiers.map { it.name } + key.key).joinToString("+")
}

object CdpKeys {
    private val NAMED: Map<String, CdpKey> = linkedMapOf(
        "Enter" to CdpKey("Enter", "Enter", 13, "\r", 66),
        "Tab" to CdpKey("Tab", "Tab", 9, null, 61),
        "Backspace" to CdpKey("Backspace", "Backspace", 8, null, 67),
        "Delete" to CdpKey("Delete", "Delete", 46, null, 112),
        "Escape" to CdpKey("Escape", "Escape", 27, null, 111),
        "Space" to CdpKey(" ", "Space", 32, " ", 62),
        "ArrowUp" to CdpKey("ArrowUp", "ArrowUp", 38, null, 19),
        "ArrowDown" to CdpKey("ArrowDown", "ArrowDown", 40, null, 20),
        "ArrowLeft" to CdpKey("ArrowLeft", "ArrowLeft", 37, null, 21),
        "ArrowRight" to CdpKey("ArrowRight", "ArrowRight", 39, null, 22),
        "Home" to CdpKey("Home", "Home", 36, null, 122),
        "End" to CdpKey("End", "End", 35, null, 123),
        "PageUp" to CdpKey("PageUp", "PageUp", 33, null, 92),
        "PageDown" to CdpKey("PageDown", "PageDown", 34, null, 93),
    )
    private val ALIASES = mapOf("return" to "Enter", "esc" to "Escape", "del" to "Delete", "up" to "ArrowUp", "down" to "ArrowDown",
        "left" to "ArrowLeft", "right" to "ArrowRight", " " to "Space")
    private val MODIFIERS = mapOf("control" to KeyModifier.Control, "ctrl" to KeyModifier.Control, "shift" to KeyModifier.Shift,
        "alt" to KeyModifier.Alt, "option" to KeyModifier.Alt, "meta" to KeyModifier.Meta, "cmd" to KeyModifier.Meta, "command" to KeyModifier.Meta)
    private val BY_LOWER = NAMED.mapKeys { it.key.lowercase() }

    /** 小型キーボード Enter と Enter は同義です。 */
    private const val ANDROID_NUMPAD_ENTER = 160

    /** キー名 (大文字と小文字は区別されません): Enter、Tab、Escape、方向キー、Home/End、ページ送り、スペース、単一の文字または数字。 Android キー コード */
    fun named(name: String): CdpKey? {
        val n = if (name == " ") name else name.trim().takeIf { it.isNotEmpty() } ?: return null
        if (n.length == 1) {
            val c = n[0]
            if (c in 'a'..'z' || c in 'A'..'Z') {
                val up = c.uppercaseChar()
                return CdpKey(c.lowercase(), "Key$up", up.code, c.lowercase(), 29 + (up - 'A'))
            }
            if (c in '0'..'9') return CdpKey(n, "Digit$n", c.code, n, 7 + (c - '0'))
        }
        return BY_LOWER[(ALIASES[n.lowercase()] ?: n).lowercase()]
    }

    /** press_key に対応するキー。対応するキー(Webページに与えてはいけないキー)がない場合はnullとなります。 */
    fun forAndroid(keyCode: Int): CdpKey? =
        if (keyCode == ANDROID_NUMPAD_ENTER) NAMED.getValue("Enter").copy(code = "NumpadEnter", androidKeyCode = ANDROID_NUMPAD_ENTER)
        else NAMED.values.firstOrNull { it.androidKeyCode == keyCode }

    /** "Control+A" "Shift+Tab" "Enter";修飾キーが繰り返されている場合、主キーがない場合、または主キーが認識されない場合は null になります。 */
    fun parseCombo(spec: String): KeyCombo? {
        val parts = spec.trim().split('+').map(String::trim)
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
        val mods = parts.dropLast(1).map { MODIFIERS[it.lowercase()] ?: return null }
        if (mods.toSet().size != mods.size) return null
        val key = named(parts.last()) ?: return null
        return KeyCombo(mods, key)
    }
}
