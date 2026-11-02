package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteAccess
import kotlinx.serialization.json.Json
import com.wangzan101.mika.core.remote.RemoteTaskApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * USB 経由のコンピューター接続: adb でコンピューターを開き、ローカル ループバックでのみ監視します。コンピューターは `adb forward` 経由で接続されています。
 * コンピューターとの合意事項は次のとおりです。接続情報ファイルの内容とコンピューター上で実行されるスクリプトです。オリジナルの Android sh と toybox で利用可能なコマンドのみが使用されます。
 */
object McpUsb {
    /** 接続情報ファイル名。アプリの外部ファイル ディレクトリに配置されます。Android 11 以降、他のアプリはこれを読み取ることができませんが、コンピューターは USB デバッグを通じてシェルとしてそれを読み取ることができます。 */
    const val INFO_FILE = "mcp-usb.json"

    /** コンピュータ上で実行されているスクリプト ファイルの名前は、「コンピュータで起動」スクリプトと一緒に配置されます。 */
    const val SCRIPT = "connect-computer.sh"

    private val pretty = Json { prettyPrint = true }

    /**
     * 接続情報: コンピューター上の MCP クライアントによって入力されるアドレスと要求ヘッダー、および最初に実行されるポート転送。
     * [boot] は、このブートの番号です (シェルで読み取られる `settings get global boot_count` と同じです)。これに基づいて、スクリプトは、前回の起動時に残された古いファイルではないことを認識します。
     */
    fun info(port: Int, token: String, boot: String): String = pretty.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("api_version", 1)
            put("api_url", "http://127.0.0.1:$port${RemoteTaskApi.ROOT}")
            put("transport", "streamable-http")
            put("url", "http://127.0.0.1:$port${McpRpc.PATH}")
            putJsonObject("headers") { put("Authorization", "Bearer $token") }
            put("token", token)
            put("port", port)
            put("forward", forward(port))
            put("boot", boot)
        },
    ) + "\n"

    /** ローカル ポートを携帯電話のループバック ポートに転送するコンピュータ上のコマンド。データ ケーブルを抜き差しした後、再度実行する必要があります。 */
    fun forward(port: Int) = "adb forward tcp:$port tcp:$port"

    /**
     * USB デバッグ経由でコンピューターによって実行されるスクリプト (`adb shell sh <scriptPath>`、シャットダウン時に最後に `off` を追加します)。パスワードやトークンがなければ、永久にそこに残しておくことができます。
     * 開く: まず、「コンピューターで開始」スクリプト [helperScript] を実行します (モバイル アシスタントが存在しないときに開始し、存在するときに何かを言うだけです)。
     * 次に、Mika のフロントエンド サービス [component] を開始し、シェルとして [onAction] をアクションします。この起動用の接続情報ファイル [infoPath] が書き込まれるまで待って、それを入力します。
     * がオフになります。同じサービスとアクション [offAction] が開始され、接続情報ファイルが削除されます (トークンはそれに応じて無効になります)。
     * は、シェル ID ではない場合には実行されません。サービスはシェル レベルのアクセス許可を持つ呼び出し元のみを受け入れ、通常のアプリはサービスを開始できません。
     */
    fun script(scriptPath: String, helperScript: String, infoPath: String, component: String, onAction: String, offAction: String, port: Int): String {
        val again = "adb shell sh $scriptPath"
        return """
            |#!/system/bin/sh
            |# Written by Mika. Lets AI tools on a computer use Mika over USB: $again   (turn off: $again off)
            |if [ "${'$'}(id -u)" != "2000" ]; then
            |  echo "Run this from a computer over USB: $again"
            |  exit 1
            |fi
            |info=${quote(infoPath)}
            |if [ "${'$'}1" = "off" ]; then
            |  out=${'$'}(am start-foreground-service -n ${quote(component)} -a ${quote(offAction)} 2>&1)
            |  case "${'$'}out" in *rror*|*xception*) echo "${'$'}out"; exit 1 ;; esac
            |  i=0
            |  while [ -f "${'$'}info" ]; do
            |    i=${'$'}((i + 1))
            |    if [ ${'$'}i -gt 10 ]; then echo "Mika did not confirm. Open Mika > Settings > Computer access (MCP) and turn off USB there."; exit 1; fi
            |    sleep 1
            |  done
            |  echo "Computer access over USB is off. The old token no longer works."
            |  exit 0
            |fi
            |if [ -f ${quote(helperScript)} ]; then
            |  sh ${quote(helperScript)} || echo "Phone control is not ready yet; tasks that operate apps wait until it is."
            |fi
            |boot=${'$'}(settings get global boot_count 2>/dev/null)
            |out=${'$'}(am start-foreground-service -n ${quote(component)} -a ${quote(onAction)} 2>&1)
            |case "${'$'}out" in *rror*|*xception*) echo "${'$'}out"; echo "Is Mika installed and up to date?"; exit 1 ;; esac
            |i=0
            |until grep -q "\"boot\": *\"${'$'}boot\"" "${'$'}info" 2>/dev/null; do
            |  i=${'$'}((i + 1))
            |  if [ ${'$'}i -gt 15 ]; then echo "Mika did not open the connection. Open Mika > Settings > Computer access (MCP) to see why, then run this again."; exit 1; fi
            |  sleep 1
            |done
            |echo "Computer access over USB is on. On the computer, run: ${forward(port)}"
            |echo "Then add this MCP server (Streamable HTTP) to your AI tool:"
            |cat "${'$'}info"
            |""".trimMargin()
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
