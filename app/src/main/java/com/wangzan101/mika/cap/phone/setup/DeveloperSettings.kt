package com.wangzan101.mika.cap.phone.setup

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * は、参照するシステム スイッチと、対応する設定ページにジャンプするインテントをガイドします。
 * 「USB デバッグ」と「ワイヤレス デバッグ」には一般的なジャンプ入り口がありません。開発者向けオプションに移動し、元の設定に従ってその行を強調表示します。このパラメータを認識しない携帯電話では、開発者向けオプションの上部で停止します。
 */
object DeveloperSettings {
    /** オリジナルのグローバル設定項目。非公開の定数。名前で読み取られます。 */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    /** 元の開発者向けオプションの「ワイヤレス デバッグ」行のキーは、強調表示に使用されます。 */
    private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    /** は、元の開発者向けオプションの「USB デバッグ」行のキーです。 */
    private const val USB_DEBUGGING_KEY = "enable_adb"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    val developerOptionsUri: Uri = Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
    val wirelessDebuggingUri: Uri = Settings.Global.getUriFor(ADB_WIFI_ENABLED)
    val usbDebuggingUri: Uri = Settings.Global.getUriFor(Settings.Global.ADB_ENABLED)

    fun developerOptions(cr: ContentResolver): Boolean =
        Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** この項目が書き込まれていない（一度もオンになっていない）場合は、オフとしてカウントされます。正確に読み取ることができない携帯電話の場合は、ブート ページの「オンにしました」が使用されます。 */
    fun wirelessDebugging(cr: ContentResolver): Boolean = Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1

    /**
     * システムにはワイヤレス デバッグはありません。元のバージョン (Android 11 以降) では、この設定がオンになったときに、現在の状態に応じて常に 0 または 1 として書き込まれるため、オンかオフかに関係なく読み取ることができます。
     * にはこの項目がありませんでした。これは、システムにワイヤレス デバッグがないことを示しています。読み取り中にエラーが発生した場合 (システムが読み取りを許可しません)、結論を導き出さない場合は、それが正しいと仮定してください。
     */
    fun wirelessDebuggingMissing(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.getString(cr, ADB_WIFI_ENABLED) == null }.getOrDefault(false)

    /**
     * システムでは「開発者向けオプション」ページを開くことができません。スキップされたインテントへのページ接続がありません (一部のオペレーティング環境には Android システム設定がありません)。
     * 質問できない場合 (システムが質問できない場合)、結論を引き出すことはできません。ただ、それが真実であると仮定してください。
     */
    fun developerOptionsPageMissing(pm: PackageManager): Boolean =
        runCatching { pm.resolveActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0) == null }.getOrDefault(false)

    /**
     * ワイヤレス デバッグ スイッチを開くように書き込みます (システム設定を変更する権限が必要です)。書き込みが拒否され (例外がスローされるか false が返され)、false が返されます。
     * が書き込まれると、システムはすぐにダイヤルバックし ([com.wangzan101.mika.core.shell.WirelessDebuggingAutoOn] を参照)、発信者は再度それを読み取ることができます。
     */
    fun turnOnWirelessDebugging(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1) }.getOrDefault(false)

    fun usbDebugging(cr: ContentResolver): Boolean = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1

    /** 「携帯電話について」のバージョン番号はこちらです（一部の携帯電話ではその下の「ソフトウェア情報」に記載されております）。 */
    fun aboutPhone(): List<Intent> = listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun developerOptionsPage(): List<Intent> = listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun usbDebuggingPage(): List<Intent> = highlighted(USB_DEBUGGING_KEY)

    fun wirelessDebuggingPage(): List<Intent> = highlighted(WIRELESS_DEBUGGING_KEY)

    private fun highlighted(key: String): List<Intent> {
        val highlight = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        return listOf(highlight) + developerOptionsPage()
    }

    fun wifiPage(): List<Intent> = listOf(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
}
