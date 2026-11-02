package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.mcp.McpRpc
import com.wangzan101.mika.core.mcp.McpTaskTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * 認証された接続によって共有されるプロトコル エントリ。呼び出し元はまず、それぞれの認証 (LAN の場合は RemoteAccess、送信トンネルの場合はペアになっている HTTPS ゲートウェイ) を完了します。
 * は監視やネットワーク要求の送信を行わず、ソースの信頼性を判断しません。どちらの送信もタスクのユースケースと最近のアクティビティを共有します。
 * 携帯電話の画面機能 (ロック画面ステータスの読み取り、ウェイクアップ、ロック解除) は、発信者によってケースバイケースで決定されます。提供されない場合、これらのツールとエンドポイントはまったく表示されません。 現れて拒否するのではなく、
 * 。ゲートウェイ経由で送信されるリクエストはデフォルトでは受け入れられません。docs/dev/remote-mcp.md を参照してください。
 */
class RemoteEndpoints(tasks: RemoteTasks, private val serverVersion: String, private val clock: () -> Long = System::currentTimeMillis,
    device: DeviceAccess? = null) {
    private val service = RemoteTaskService(tasks)
    private val clients = LinkedHashMap<String, Long>()
    private val mutable = MutableStateFlow(RemoteActivity())
    val activity = mutable.asStateFlow()

    /** 一連のプロトコル入口: タスク API と MCP。 2 つのセットの唯一の違いは、携帯電話の画面を表示できること、タスク サービスと最近のアクティビティを共有できることです。 */
    private inner class Surface(device: DeviceAccess?) {
        val api = RemoteTaskApi(service, serverVersion, device)
        val rpc = McpRpc(McpTaskTools(service, device), serverVersion) { remote, name -> seen(remote, name) }
    }
    private val withDevice = Surface(device)
    private val tasksOnly = if (device == null) withDevice else Surface(null)

    /** [deviceAccess] が false の場合、スリープ解除、ロック解除、およびロック画面のステータスはこのリクエストでは確認できません。 */
    fun handle(req: HttpRequest, deviceAccess: Boolean = true): HttpResponse {
        val surface = if (deviceAccess) withDevice else tasksOnly
        if (req.path == McpRpc.PATH) return surface.rpc.handle(req)
        if (req.path == RemoteTaskApi.ROOT || req.path.startsWith("${RemoteTaskApi.ROOT}/")) {
            seen(req.remote, req.header("x-mika-client")?.take(64))
            return surface.api.handle(req)
        }
        return RemoteTaskApi.error(404, "not_found", "No such endpoint")
    }

    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key); clients[key] = now
            clients.entries.removeAll { now - it.value > RemoteHost.RECENT_MS }
            mutable.value = RemoteActivity(clients.size, now)
        }
    }
}
