package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

/**
 * コンピューター接続 (MCP) 設定: スイッチとポートは小さな JSON ファイルに保存されます (理解できない場合は閉じるを押してください)。
 * アクセス トークンは、[KeyCipher] によって暗号化され、サービス プロバイダーのキーと同様に別のファイルに保存されます。初めて使用するときに生成されます。
 * はデフォルトではオフになっています。
 */
class McpSettings(private val dir: File, private val cipher: KeyCipher, private val random: SecureRandom = SecureRandom()) {
    private val file = File(dir, "mcp.json")
    private val tokenFile = File(dir, "mcp-token.bin")
    private val _enabled: MutableStateFlow<Boolean>
    @Volatile private var cachedToken: String? = null

    /** はリスニング ポートです。ファイルに記述されていない場合は、[DEFAULT_PORT] を使用してください。 */
    val port: Int

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        _enabled = MutableStateFlow((o?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: false)
        port = (o?.get("port") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT
    }

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Synchronized fun setEnabled(on: Boolean) {
        _enabled.value = on
        save()
    }

    /** 現在のアクセス トークン。利用できない (または読み取れない) 場合は、生成して保存します。 */
    @Synchronized fun token(): String {
        cachedToken?.let { return it }
        val stored = runCatching { if (tokenFile.isFile) String(cipher.decrypt(tokenFile.readBytes()), Charsets.UTF_8) else null }.getOrNull()
        return stored?.takeIf { it.length >= MIN_TOKEN }?.also { cachedToken = it } ?: regenerate()
    }

    /** 新しいトークンに変更します。古いトークンはその場で無効になり、接続されているコンピューターを再度使用するには、新しいトークンに置き換える必要があります。 */
    @Synchronized fun regenerate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        dir.mkdirs()
        val tmp = File(dir, tokenFile.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), tokenFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cachedToken = token
        return token
    }

    @Synchronized private fun save() {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("enabled", _enabled.value); put("port", port) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MIN_TOKEN = 32
    }
}
