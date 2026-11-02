package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part

/** は、リクエストの各ステップの終了時にランタイム ステータスを削除し、ツールの結果とプロンプトを古い場所で簡単に表示できるようにします。 */
internal fun List<Message>.withoutRuntime(): List<Message> = map(ContextBuilder::withoutRuntimeState).filter { it.parts.isNotEmpty() }

/** メッセージのテキスト (実行時ステータスを除く): コンテキスト シェルを削除し、テキストのみを残し、残りはそのままにします。 */
internal fun Message.plainText(): String = ContextBuilder.withoutRuntimeState(this).let { m ->
    ContextBuilder.bodyOf(m) ?: m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
}

/** 特定のコンテキストのテキストを出現順に表示します。 */
internal fun List<Message>.contextBodies(vararg kinds: ContextKind): List<String> =
    withoutRuntime().filter { ContextBuilder.kindOf(it) in kinds }.mapNotNull { ContextBuilder.bodyOf(it) }

/** リクエスト内のランタイム ステータス テキスト (出現順)。 */
internal fun List<Message>.runtimeStates(): List<String> = flatMap { it.parts }.filterIsInstance<Part.Text>()
    .filter { ContextBuilder.kindOf(it.text) == ContextKind.RuntimeState }
    .map { ContextBuilder.bodyOf(com.wangzan101.mika.core.model.Message(com.wangzan101.mika.core.model.Role.User, listOf(it), com.wangzan101.mika.core.model.Author.Mika))!! }
