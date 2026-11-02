package com.wangzan101.mika.cap.phone.setup

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.wangzan101.mika.core.adb.AdbConnectPort
import com.wangzan101.mika.core.adb.AdbConnection
import com.wangzan101.mika.core.adb.ConnectCandidates
import com.wangzan101.mika.core.adb.AdbPairing
import com.wangzan101.mika.core.adb.AdbServiceTypes
import com.wangzan101.mika.core.adb.AdbTls
import com.wangzan101.mika.core.adb.LocalPortScan
import com.wangzan101.mika.core.adb.NioPortScanner
import com.wangzan101.mika.core.adb.PortCache
import com.wangzan101.mika.core.shell.BuiltinShellEngine
import com.wangzan101.mika.core.shell.BuiltinShellPlatform
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.WirelessDebuggingAutoOn
import com.wangzan101.mika.core.shell.ShellCommandRunner
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.phone.shell.adb.AndroidAdb
import com.wangzan101.mika.cap.phone.shell.adb.NsdAdbServiceFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

/**
 * 組み込みアシスタントの実際の実装: ペアリング、ローカル adbd 経由のアシスタント、ウォッチドッグ。 [com.wangzan101.mika.cap.phone.PhoneServices] でコピーを作成し、
 * アプリが起動すると、[start] からアシスタントの参照が開始されます。 [hub] は、アシスタントがバインダーを渡すポイントです。 [consent] は、どの機能をユーザーが選択するかです (「ワイヤレス デバッグを自動的に有効にする」については、こちらを参照してください)。
 */
class BuiltinShellManager(context: Context, private val hub: HelperHub, private val consent: () -> RecoveryConsent) {
    private val app = context.applicationContext
    private var started = false

    private val _scanningPorts = MutableStateFlow(false)
    /** は、ワイヤレス デバッグ ポートを見つけるためにマシンをスキャンしています (ブート ページには「このマシンを検索しています...」と表示されます)。 */
    val scanningPorts: StateFlow<Boolean> = _scanningPorts.asStateFlow()

    private val platform: AndroidPlatform by lazy {
        AndroidPlatform(app, hub, { lastConnectCandidates = it }, { _scanningPorts.value = it })
    }

    /** 前回接続ポートを検索したとき、さまざまなソースからいくつかの候補が提供されました (診断情報用)。今回は、プロセス内で接続が接続されていない場合は null でした。 */
    @Volatile var lastConnectCandidates: ConnectCandidates? = null
        private set

    /** アシスタントを開始するときにユーザーのワイヤレス デバッグをオンにします。アプリ自体のみがシステム設定を書き込みます (アシスタントを開始するときに他の接続は使用できません)。 */
    val wirelessAutoOn: WirelessDebuggingAutoOn by lazy {
        WirelessDebuggingAutoOn(
            wants = { consent().wants(RecoveryCapability.AutoWirelessDebugging) },
            canWrite = { app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED },
            read = platform::wirelessDebugging,
            write = { DeveloperSettings.turnOnWirelessDebugging(app.contentResolver) },
        )
    }

    /** ペアリングクライアント + ウォッチドッグ。 */
    val engine: BuiltinShellEngine by lazy {
        BuiltinShellEngine(
            platform, hub.shell, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO, autoOn = wirelessAutoOn,
        )
    }

    /**
     * 回復および認証スイッチ: アシスタントを実行できます (アシスタントは、ペアリングされた接続なしで USB 経由でコンピューター上で使用できます)。
     * はコマンドごとに個別にペアリングされた adbd に接続されず、実行後に切断されます。
     */
    val commandRunner = ShellCommandRunner { command ->
        withContext(Dispatchers.IO) {
            hub.shell.value?.let { it.runCommand(command)?.getString("output").orEmpty() }
                ?: platform.connect().use { it.exec(command).readText() }
        }
    }

    /** 「コンピューターで開始」スクリプト: アシスタントの存在に応じて書き換えます。 */
    val usbScript = UsbStartScript(app, hub)

    /** アシスタントを使いたいと思っていても、アシスタントを持っていない人もいます。ウォッチドッグをセットアップする必要があり (ペアリングする必要があります)、スクリプトもコンピューター用に準備されています。 */
    fun need() {
        engine.need()
        usbScript.refresh()
    }

    /** ユーザーはブート ページでワイヤレス デバッグ ポートを入力します。次回接続するときにそれを持参し (これは 1 回だけ使用し、接続時にキャッシュとして記録されます)、再試行します。 */
    suspend fun connectWithPort(port: Int) {
        platform.manualPort.set(port)
        engine.retry()
    }

    @Synchronized
    fun start() {
        if (started || Build.VERSION.SDK_INT < MIN_SDK) return
        started = true
        usbScript.start()
        val e = engine
        // ワイヤレス デバッグがオンになったらすぐに再接続してみてください。オフにすると、ステータスがすぐに「ワイヤレス デバッグがオフです」に変わります。
        app.contentResolver.registerContentObserver(
            DeveloperSettings.wirelessDebuggingUri, false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = e.wirelessDebuggingChanged()
            },
        )
        // が「Wi-Fi なし」のままになっている場合、またはワイヤレス デバッグがオフになっている場合は、Wi-Fi に接続したらすぐに再試行してください (インターネットにアクセスできる必要はありません)。
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = e.wifiAvailable()
                },
            )
        }
        e.start()
    }

    private class AndroidPlatform(
        private val app: Context,
        private val hub: HelperHub,
        private val candidates: (ConnectCandidates) -> Unit,
        private val scanning: (Boolean) -> Unit,
    ) : BuiltinShellPlatform {
        /** ユーザーが入力したポートは、次回の接続から削除されます。 */
        val manualPort = AtomicReference<Int?>(null)
        private val scan = LocalPortScan(NioPortScanner(), ::localHosts, { LocalPortScan.parseRange(portRangeText()) })
        private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val finder = NsdAdbServiceFinder(app)
        private val portCache = object : PortCache {
            override fun load() = prefs.getInt("port", 0).takeIf { it > 0 }
            override fun save(port: Int) { prefs.edit().putInt("port", port).apply() }
        }

        override fun paired() = Build.VERSION.SDK_INT >= MIN_SDK && prefs.getBoolean("paired", false)

        override fun setPaired(paired: Boolean) {
            prefs.edit().putBoolean("paired", paired).apply()
        }

        override fun usbStarted() = prefs.getBoolean("usbStarted", false)

        override fun setUsbStarted(on: Boolean) {
            prefs.edit().putBoolean("usbStarted", on).apply()
        }

        // この項目が書かれていない場合は読み取ります。 null: はっきり見えない場合は、やめないでください。
        override fun wirelessDebugging(): Boolean? =
            Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }

        override fun wifiConnected(): Boolean = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(true)

        override suspend fun findPairingPort(timeoutMs: Long): Int? = finder.findPorts(AdbServiceTypes.PAIRING, timeoutMs).firstOrNull()

        override fun pair(port: Int, code: String) {
            AdbPairing(AndroidAdb.tls(AndroidAdb.pairingIdentity(app))).pair(LOCALHOST, port, code)
        }

        override suspend fun launchHelper() {
            val tls = AndroidAdb.tls(AndroidAdb.identity(app))
            // アシスタントは引き継ぎの前にミカの署名をチェックします
            val command = HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), ownCertPin(app))
            val connection = connect(tls)
            // sh ストリームが開いている場合は待機します。ハンドオーバーされる (またはタイムアウトになる) と、再びオフになります。ハンドオーバー結果は引き続き HelperHub からのエンジンによって判断されます。
            val before = hub.shell.value
            connection.use {
                val stream = it.exec(command)
                stream.write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
                withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
            }
        }

        suspend fun connect(tls: AdbTls = AndroidAdb.tls(AndroidAdb.identity(app))): AdbConnection =
            AdbConnectPort(
                ::tlsPort, finder, portCache, report = candidates, scan = scan, manual = { manualPort.getAndSet(null) }, scanning = scanning,
            ).connect { host, port -> AdbConnection.open(host, port, tls) }

        /** がポートをスキャンするときは、マシン自体 (127.0.0.1 と Wi-Fi 上の IPv4 アドレス) のみをスキャンします。 */
        private fun localHosts(): List<String> = listOf(LOCALHOST) + runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
                .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        }.getOrDefault(emptyList())

        /** システムは一時ポートの範囲を割り当てます。一部のシステムではアプリによる読み取りが許可されていないため、読み取りできない場合はデフォルトのセクションがスキャンされます。 */
        private fun portRangeText(): String? = runCatching { File(PORT_RANGE_FILE).readText() }.getOrNull()

        override fun stopHelper() {
            // destroy により、ヘルパー プロセスがその場で終了し、この呼び出しは DeadObjectException で終了する可能性が高くなります。
            runCatching { hub.shell.value?.destroy() }
        }

        override fun forget() {
            hub.forget()
            AndroidAdb.forget(app)
            prefs.edit().remove("port").apply()
        }

        @SuppressLint("PrivateApi")
        private fun tlsPort(): Int? = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, TLS_PORT_PROPERTY) as String?
        }.getOrNull()?.trim()?.toIntOrNull()
    }

    companion object {
        /** 組み込みアシスタントは Android 12 以降で有効になります。古いシステムでは、新しい画面フラグのない互換性パスが使用されます。 */
        const val MIN_SDK = Build.VERSION_CODES.S
        private const val PREFS = "builtin_shell"
        /** Android オリジナルの adbd は、このシステム プロパティにワイヤレス デバッグ接続ポートを書き込みます。読み取れない場合は、キャッシュ、mDNS を利用し、ローカル ポートとユーザーが入力したポートをスキャンします。 */
        private const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        private const val LOCALHOST = AdbConnectPort.LOCALHOST
        private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"
        private const val HANDOFF_WAIT_MS = 15_000L
    }
}
