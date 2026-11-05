package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.cap.mcp.McpCapability
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.mcpAssemblyReason
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.AgentTool

internal class McpClientProvider(private val connection: McpConnection) : CapabilityProvider {
    override val id: String = "mcp:${connection.endpoint.serverId}"
    override fun open(spec: RunSpec): Capability = try {
        McpCapability(connection, id)
    } catch (e: Exception) {
        McpFailedCapability(id, connection.endpoint.serverId, mcpAssemblyReason(e))
    }
}

/** 構成に失敗したサーバーだけを無効にし、理由を示して他の機能を継続します。 */
private class McpFailedCapability(
    override val id: String,
    private val serverId: String,
    private val reason: String,
) : Capability {
    override fun status() = CapabilityStatus.Unavailable("MCP サーバー '$serverId' を構成できませんでした: $reason")
    override fun tools(): List<AgentTool> = emptyList()
    override fun sections(env: PromptEnv): List<String> = listOf(
        "MCP サーバー '$serverId' を構成できませんでした: $reason。このサーバーのツールは利用できません。他の機能は引き続き利用できます。",
    )
}
