package com.wangzan101.mika.core.cap.sandbox

/**
 * サンドボックス コマンドの結果。 [output] は、標準出力と標準エラー (最大 1 MB) を組み合わせたものです。
 * はさらに失われ、[droppedBytes] に含まれます。 [killedBySystem]: システムによって強制終了されました (主にメモリ不足が原因です)。 [network]: 実行時にインターネットに接続されているかどうか。
 */
data class CommandResult(
    val exitCode: Int, val timedOut: Boolean, val killedBySystem: Boolean, val durationMs: Long,
    val output: String, val droppedBytes: Long, val network: Boolean,
)
