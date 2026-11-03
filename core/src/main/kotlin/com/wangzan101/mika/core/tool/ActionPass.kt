package com.wangzan101.mika.core.tool

/**
 * 外の世界を変えるツールを実行するための認証情報: [ToolDispatcher.execute] 要求してください。コンストラクターは内部的なものであり、App モジュールによって作成することはできません。 実行入口 (core.action.ActionExecutor) のみが
 * コアに作成され、SingleEntryTest によって保護されます。読み取り専用呼び出しにより、[ToolDispatcher.executeRead] が削除されます。
 */
class ActionPass internal constructor()
