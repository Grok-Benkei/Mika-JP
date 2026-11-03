package com.wangzan101.mika.settings

import com.wangzan101.mika.core.mcp.McpUsb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

/**
 * USB 経由でコンピュータ接続するためのスイッチとトークン (コンピュータが adb で開くタイプ)。ローカル エリア ネットワーク ([McpSettings]) と同様に、それぞれに独自のビジネスがあり、それぞれに独自のトークンがあります。
 *
 * - 開かれたときに新しいトークンが生成されます。元のコマンドは、それを開いて再度開くときに使用されます (コンピューターはコマンドを再実行しますが、構成されたコマンドは無効になりません)。
 * - [publish] を聞いた後、接続情報を [infoFile] (外部ファイル ディレクトリ。Android 11 以降はミカとシェルのみが読み取ることができます) に書き込みます。
 * - シャットダウン時にトークンを忘れて（次回新しいものに置き換えて）、接続情報ファイルを削除します。
 * - クロスリブートなし: スイッチはオンになった時間を記憶します ([bootId])。再度起動するとオフになり、前回残したファイルは簡単にクリアできます。
 *
 * トークンは、接続情報ファイル (コンピューターによって読み取られる) にもプレーン テキストで書き込まれるため、アプリ独自のプライベート ディレクトリにも追加の暗号化なしでプレーン テキストが保存されます。
 */
class McpUsbSettings(
    private val dir: File,
    private val bootId: () -> String,
    private val infoFile: () -> File?,
    private val random: SecureRandom = SecureRandom(),
) {
    private val file = File(dir, "mcp-usb.json")
    private val _enabled = MutableStateFlow(false)
    @Volatile private var token: String? = null

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        val boot = (o?.get("boot") as? JsonPrimitive)?.content
        val t = (o?.get("token") as? JsonPrimitive)?.content
        if (boot != null && boot == bootId() && t != null && t.length >= MIN_TOKEN) {
            token = t
            _enabled.value = true
        } else {
            file.delete()
            runCatching { infoFile()?.delete() }
        }
    }

    /** トークンがオンになっている場合。オフの場合は null。 */
    fun token(): String? = token

    /** はオープンしています。すでにオープンされている場合は、元のトークンが使用されます。現時点でのトークンを返します。 */
    @Synchronized fun enable(): String {
        token?.let { return it }
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("boot", bootId()); put("token", t) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        token = t
        _enabled.value = true
        return t
    }

    /** OFF：トークンをその場で無効化し、接続情報ファイルを削除します。 */
    @Synchronized fun disable() {
        token = null
        _enabled.value = false
        file.delete()
        unpublish()
    }

    /** サウンドの監視: 接続情報をコンピューターに書き込みます (最初に一時ファイルを書き込んでから名前を変更します。コンピューターは読み取り中にその半分を読み取りません)。閉じているときは何もしません。 */
    @Synchronized fun publish(port: Int) {
        val t = token ?: return
        val f = infoFile() ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(McpUsb.info(port, t, bootId()))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** は監視していません (オンになっていない、オフになっています): 接続情報ファイルを削除すると、コンピューター上のスクリプトは、オンになっていないことを認識します。 */
    @Synchronized fun unpublish() {
        runCatching { infoFile()?.delete() }
    }

    private companion object {
        const val MIN_TOKEN = 32
    }
}
