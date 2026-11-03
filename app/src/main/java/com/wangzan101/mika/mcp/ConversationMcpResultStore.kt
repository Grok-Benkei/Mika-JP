package com.wangzan101.mika.mcp

import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.cap.mcp.McpResultStore
import com.wangzan101.mika.core.workspace.SandboxPaths
import java.util.UUID

/**
 * 外部 MCP ツールの長い結果は現在の会話ワークスペースに書き込まれ、モデルは read_file で読み取ることができます。
 * は `mcp-results/` のみを書き込み、ファイル名のサーバー/ツールは安全な文字のみを保持します。
 */
class ConversationMcpResultStore(
    private val dirs: SandboxDirs,
    private val conversationId: String,
) : McpResultStore {
    override fun save(serverId: String, toolName: String, content: String): String {
        val ws = dirs.workspace(conversationId)
        dirs.ensureBindSources(ws)
        val server = serverId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val tool = toolName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val rel = "mcp-results/$server-$tool-${UUID.randomUUID()}.txt"
        dirs.gate.createNew(ws, rel).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return "${SandboxPaths.WORKSPACE}/$rel"
    }
}
