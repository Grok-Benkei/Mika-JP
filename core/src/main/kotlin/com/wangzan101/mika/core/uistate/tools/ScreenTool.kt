package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.uistate.PressKeys
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.sessions

/**
 * は、画面ツールのセットの 1 つです。名前は `<屏>_<动词>` で、画面 [family] でのみ機能します (ディストリビューターは呼び出す前にそれをメモします)。
 * 命令は、共有テンプレートのプロンプト/ツール/<動詞>.md (この画面に独自のコピーがある場合は、<画面>_<動詞>.md を使用します) を使用し、テンプレートで指定されたツールはこのセットの名前に置き換えられます。
 */
internal abstract class ScreenTool(val family: ScreenFamily, val base: String) : AgentTool {
    final override val name: String = family.tool(base)
    override val capability: String get() = family.id
    /** この携帯電話セットでは、テキスト結果をサンドボックスから調整できます。画像の結果 (画像の結果にはビジュアル モデルが必要です) は開かれていません。 */
    override val sandboxCallable: Boolean get() = family.sandboxCallable && verb !in IMAGE_VERBS
    override val promptFile: String get() = if (base in family.ownPrompts) name else base

    /** は、画面がポートに接続されている場合にのみ提供されます。 [usable] は各ツールの追加要件です。 */
    final override fun available(ctx: ToolContext): Boolean = family.id in ctx.sessions && usable(ctx)

    protected open fun usable(ctx: ToolContext): Boolean = true

    /** 完全な説明: テンプレート内の {{动词}} は、このセット内のツール名に置き換えられます。 {{keys}} はこの画面で押せるキーです。 */
    protected fun describe(vararg vars: Pair<String, Any?>): String =
        Prompts.tool(promptFile, *(MIRRORED.map { it to family.tool(it) } + ("keys" to keyNames(family)) + vars).toTypedArray())

    override fun toString() = name

    companion object {
        private val IMAGE_VERBS = setOf(Verb.Screenshot, Verb.Describe, Verb.FindVisually)

        /** 各画面には、モデルに送信される順序で動詞が含まれています。 */
        val MIRRORED = listOf(
            "observe", "list_elements", "screenshot", "describe", "find_visually",
            "tap", "scroll", "swipe", "type_text", "clear_text", "back", "press_key", "diagnose",
        )

        /** press_key が画面上で押すことができるキーの名前。 */
        fun keyNames(f: ScreenFamily): String =
            PressKeys.BY_NAME.filterValues { f.keys == null || it in f.keys }.keys.joinToString()

        /** は[MIRRORED]の順で1画面分の画面ツールセットです。 */
        fun of(f: ScreenFamily): List<AgentTool> = listOf(
            Observe(f), ListElements(f), Screenshot(f), Describe(f), FindVisually(f),
            Tap(f), Scroll(f), Swipe(f), TypeText(f), ClearText(f), Back(f), PressKey(f), Diagnose(f),
        ).also { tools -> check(tools.map { it.base } == MIRRORED) }
    }
}
