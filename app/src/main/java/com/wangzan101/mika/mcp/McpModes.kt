package com.wangzan101.mika.mcp

import com.wangzan101.mika.settings.McpSettings
import com.wangzan101.mika.settings.McpUsbSettings
import com.wangzan101.mika.settings.RelaySettings

/**
 * LAN と USB で共有されるローカル監視: LAN がオンの場合はすべてのネットワーク カードをバインドし、USB がオンの場合のみループバックをバインドします。
 * リモート ゲートウェイは、ローカルの監視範囲を変更しない独立した送信チャネルを使用します。 3 つのスイッチはそれぞれ独自のタスクを管理し、「閉じる」通知は一緒に閉じられます。
 * は Android インターフェイスには触れず、コンピューターでテストされます。
 */
object McpModes {
    /** コンピューターは、adb (`am start-foreground-service -a`) 経由で USB モードを開きます。 adb 経由で */
    const val ACTION_USB_ON = "com.wangzan101.mika.mcp.USB_ON"
    /** コンピューターの USB モードをオフにします。 */
    const val ACTION_USB_OFF = "com.wangzan101.mika.mcp.USB_OFF"
    /** 通知の「閉じる」: ローカル モニタリングとリモート チャネルの両方がオフになります。 */
    const val ACTION_TURN_OFF = "com.wangzan101.mika.mcp.TURN_OFF"

    enum class Listen { Off, LoopbackOnly, PrivateNetworks }

    fun listen(network: Boolean, usb: Boolean): Listen = when {
        network -> Listen.PrivateNetworks
        usb -> Listen.LoopbackOnly
        else -> Listen.Off
    }

    /** サービスが受信したアクションに対してどのスイッチが変更されるか。他のアクション (アクションなしを含む) は変更されず、スイッチの現在のステータスのみが変更されます。 */
    fun apply(action: String?, network: McpSettings, usb: McpUsbSettings, relay: RelaySettings? = null) {
        when (action) {
            ACTION_USB_ON -> usb.enable()
            ACTION_USB_OFF -> usb.disable()
            ACTION_TURN_OFF -> {
                try { network.setEnabled(false) }
                finally { try { usb.disable() } finally { relay?.setEnabled(false) } }
            }
        }
    }
}
