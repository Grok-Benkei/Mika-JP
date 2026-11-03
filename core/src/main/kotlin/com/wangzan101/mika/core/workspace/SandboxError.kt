package com.wangzan101.mika.core.workspace

/** は、サンドボックス実装によってツール レイヤに報告される既知のエラーです。 [code]はそのままツール結果となるエラー(sandbox_network_isolation_unavailableなど)です。 */
class SandboxError(val code: String, message: String, val recoverable: Boolean = false) : Exception(message)
