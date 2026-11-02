package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.assembly.toolDispatcher

/** テストはディストリビュータを直接駆動します (実行シーケンスを除いてツール自体をテストします)。プロダクト コードは実行入口を通じてのみ証明書を取得できます。 */
internal suspend fun ToolDispatcher.execute(call: Part.ToolCall, step: Int? = null, sub: SubActions? = null): ToolExecution =
    execute(call, ActionPass(), step, sub)

/** テストではツールの宣言を名前で尋ねます（製品コードはこのタスクの配布元から尋ねられます）。 */
internal fun isWritingTool(name: String, @Suppress("UNUSED_PARAMETER") args: JsonObject): Boolean = toolDispatcher(FakeScreen()).writesScreen(name)

internal fun isSandboxTool(name: String): Boolean = toolDispatcher(FakeScreen()).index[name]?.capability == "sandbox"
