package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.capability.PromptSections
import com.wangzan101.mika.core.prompt.Prompts

/**
 * システム プロンプト ワード: P0 アイデンティティ (prompts/system/identity.md)、P1 一般原則 (system/principles.md、命名ツールなし)、
 * 一般ツールの一般規則 (capabilities/general.md、一般ツールは常に存在します) に接続し、コア自体と各機能で指定された段落に接続します。
 * 段落は、このタスクのモデルに送信されるツール リストにそのツール セットが表示される場合にのみ存在します。指示とツールは同じ判断に基づいています。
 * はタスクの開始時に一度綴られますが、タスク内のすべての単語は変更されません。
 */
object SystemPrompt {
    fun identity(): String = listOf(Prompts.system("identity"), Prompts.system("principles"), read(GENERAL)).joinToString("\n\n")

    fun assemble(identity: String, sections: List<String>): String = (listOf(identity) + sections).joinToString("\n\n")

    private const val GENERAL = "general"

    /** 機能の説明。 */
    internal fun read(name: String): String = Prompts.capability(name)
}

/**
 * コア自身の段落は、各機能の前にランク付けされます。使用前に展開されるグループに入るためにどの入り口が使用されるか (グループ、このタスクによって与えられる入り口のみがリストされます。拡張後も保持され、システム プロンプト ワードはほとんど変更されません)。
 * 画面が 3 つ以上ある場合の選択方法、2 つの画面 (チャンネル) 間で連携する方法;画面を展開してユーザーに引き渡した後、注文して発送する前（引き渡し）。
 * 独自の画面 (インライン画面) がある場合のアクション結果の読み取り方法。一度に複数のステップ (バッチ)。
 */
internal object CoreSections : PromptSections {
    override fun sections(env: PromptEnv): List<String> = listOfNotNull(
        env.gate(SystemPrompt.read("groups")).takeIf { it.lines().size > 1 },
        SystemPrompt.read("channels").takeIf { env.screens >= 2 },
        SystemPrompt.read("handover").takeIf { env.openScreens >= 1 },
        SystemPrompt.read("inline-screen").takeIf { env.inlineScreen && env.openScreens >= 1 },
        SystemPrompt.read("batch").takeIf { DO_STEPS_TOOL in env.offered },
    ).map(env::gate)
}
