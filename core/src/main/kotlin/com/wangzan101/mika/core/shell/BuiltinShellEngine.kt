package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.adb.AdbConnectionException
import com.wangzan101.mika.core.adb.AdbKeyUnreadableException
import com.wangzan101.mika.core.adb.AdbPairingException
import com.wangzan101.mika.core.adb.AdbServiceTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** エンジンに必要なプラットフォーム機能。ブロッキング呼び出しは IO スケジューラで実行されます。 */
interface BuiltinShellPlatform {
    fun paired(): Boolean
    fun setPaired(paired: Boolean)
    /** ワイヤレス デバッグ スイッチ;読み取られない場合は null になります (下向きの動きをブロックしません)。 */
    fun wirelessDebugging(): Boolean?
    fun wifiConnected(): Boolean
    suspend fun findPairingPort(timeoutMs: Long): Int?
    /** はペアリング コードとペアになっています。失敗すると、[AdbPairingException] がスローされます。 */
    fun pair(port: Int, code: String)
    /** はローカル adbd に接続し、アシスタントを開始します (ハンドオーバーを待たずに)。失敗した場合は [AdbConnectionException] をスローし、保存されたキーを読み取れない場合は [AdbKeyUnreadableException] をスローします。 */
    suspend fun launchHelper()
    /** は、実行中のアシスタントに変更を元に戻して終了するように指示します。そうでない場合は、何もしません。 */
    fun stopHelper()
    /** ペアリングキーと引き継ぎコマンドを削除します。 */
    fun forget()
    /** は、ペアリングされていない (USB 経由でコンピューターから起動された) ときにアシスタントを受け入れました。アシスタントが停止すると、セットアップされていなかったかのように扱うのではなく、再度起動する方法をユーザーに指示しました。持続性。 */
    fun usbStarted(): Boolean
    fun setUsbStarted(on: Boolean)
}

/** ウォッチドッグ再起動アシスタント間隔: n 回目の失敗後、base・2^(n-1) を待ちます。上限は [maxMs] です。 [maxAttempts] の連続失敗後に停止し、誰かが変更するかスイッチが変更されるまで待ちます。 */
data class Backoff(val baseMs: Long = 2_000, val maxMs: Long = 60_000, val maxAttempts: Int = 5) {
    fun delayAfter(failures: Int): Long? {
        if (failures >= maxAttempts) return null
        return (baseMs shl (failures - 1).coerceIn(0, 20)).coerceAtMost(maxMs)
    }
}

object BuiltinShellFailures {
    fun ofPairing(reason: AdbPairingException.Reason) = when (reason) {
        AdbPairingException.Reason.WRONG_CODE -> BuiltinShellFailure.WrongCode
        // ペアリング ポートに接続できません: ペアリング ウィンドウが閉じられました (ウィンドウとともにポートが消えます)
        AdbPairingException.Reason.CONNECT_FAILED -> BuiltinShellFailure.PairingWindowClosed
        AdbPairingException.Reason.TLS_FAILED, AdbPairingException.Reason.PROTOCOL -> BuiltinShellFailure.Unknown
    }

    fun ofConnection(reason: AdbConnectionException.Reason) = when (reason) {
        AdbConnectionException.Reason.UNAUTHORIZED -> BuiltinShellFailure.AuthorizationRevoked
        AdbConnectionException.Reason.CONNECT_FAILED,
        AdbConnectionException.Reason.TLS_FAILED,
        AdbConnectionException.Reason.PROTOCOL -> BuiltinShellFailure.ConnectFailed
    }

    /** ウォッチドッグによる再試行は役に立ちますか: キーは取り消されており、Wi-Fi がなく、ユーザーまたはスイッチが変更されるまで待つ必要があります。 */
    fun retryable(f: BuiltinShellFailure) = f == BuiltinShellFailure.ConnectFailed ||
        f == BuiltinShellFailure.HelperStartFailed || f == BuiltinShellFailure.Unknown
}

/**
 * 内蔵アシスタント ステータスとウォッチドッグ。 [helper] 貴重なアシスタントのバインダーが手元にあります (実行中)。
 * が使用できない場合: ペアリング後、まず [graceMs] を待ち (まだ生きているアシスタントは、Mika プロセスの開始後に引き継がれます)、次に、adbd を通じてペアリングを開始します。失敗した場合は、[backoff] を押して再試行してください。
 * がアシスタントを開始しようとすると、ワイヤレス デバッグがオフになります。 [autoOn] がある場合、最初にユーザーに対して有効になります (条件と停止ルールについては、[WirelessDebuggingAutoOn] を参照してください)。
 */
class BuiltinShellEngine(
    private val platform: BuiltinShellPlatform,
    private val helper: StateFlow<Any?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val backoff: Backoff = Backoff(),
    private val graceMs: Long = 3_000,
    private val handoffTimeoutMs: Long = 15_000,
    private val discoveryTimeoutMs: Long = 10 * 60_000L,
    private val pairingPortTimeoutMs: Long = 5_000,
    private val autoOn: WirelessDebuggingAutoOn? = null,
) : BuiltinShellControl {
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<BuiltinShellStatus> = _status.asStateFlow()
    private val _discovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    override val pairingDiscovery: StateFlow<PairingDiscovery> = _discovery.asStateFlow()

    /** 最新のペアリングまたは接続失敗の例外カテゴリと説明 (診断情報のため、元のテキストは感度が解除されていません)。接続後にクリアされます。 */
    @Volatile var lastError: String? = null
        private set

    private val launchLock = Mutex()
    private var failures = 0
    private var retryJob: Job? = null
    private var discoveryJob: Job? = null
    private var started = false

    override val paired: Boolean get() = platform.paired()

    override val restart: HelperRestart get() = when {
        platform.paired() -> HelperRestart.Wireless
        platform.usbStarted() -> HelperRestart.UsbCommand
        else -> HelperRestart.None
    }

    private fun initialStatus(): BuiltinShellStatus = when {
        helper.value != null -> BuiltinShellStatus.Running
        // コンピューター上で作成されたアシスタントがまだ生きていて、引き継がれるのを待っている可能性があります。
        !platform.paired() -> if (platform.usbStarted()) BuiltinShellStatus.Connecting else BuiltinShellStatus.NotPaired
        else -> BuiltinShellStatus.Connecting
    }

    /** がペアリングされておらず、アシスタントが手元にない場合の状態: コンピューター上で起動されているアシスタントが停止している場合は、正直に言ってください。そうでない場合は、セットアップされていません。 */
    private fun unpairedStatus(): BuiltinShellStatus =
        if (platform.usbStarted()) BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) else BuiltinShellStatus.NotPaired

    /** はアシスタントの確認を開始しました。切断された後、アシスタントが再起動されました。機能がオンの場合にのみ呼び出されます。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        var first = true
        scope.launch {
            helper.collect { h ->
                if (h != null) {
                    synchronized(this@BuiltinShellEngine) { failures = 0; retryJob?.cancel() }
                    autoOn?.reset()
                    // はペアリングなしで到着しました。USB 経由でコンピューターに接続されました。それを書き留めてください、それが止まるまでプロンプトの方法がわかりません
                    if (!platform.paired()) platform.setUsbStarted(true)
                    _status.value = BuiltinShellStatus.Running
                } else if (!platform.paired()) {
                    // アプリ 最初に起動したときにまだ生きているアシスタントは、それ自体を引き渡します。使用後に壊れた場合は、新しいものと交換できます。
                    // インターフェースが「停止」と点滅しないように、停止したことを通知する前にしばらく待ちます。
                    if (platform.usbStarted()) {
                        _status.value = BuiltinShellStatus.Connecting
                        schedule(graceMs)
                    } else {
                        _status.value = unpairedStatus()
                    }
                } else {
                    if (_status.value == BuiltinShellStatus.Running) _status.value = BuiltinShellStatus.Connecting
                    schedule(if (first) graceMs else backoff.baseMs)
                }
                first = false
            }
        }
    }

    /** 誰かがアシスタントを使用したいと思っていましたが、それを持っていませんでした。私はアシスタントを起動したり、再試行するためにキューに並んだりすることなく、すぐにアシスタントを開始しました。 */
    fun need() {
        if (helper.value != null || !platform.paired()) return
        synchronized(this) { if (retryJob?.isActive == true) return }
        if (launchLock.isLocked) return
        schedule(0)
    }

    /**
     * ワイヤレス デバッグ スイッチが変更されました。スイッチをオンにして、最初からやり直してください。オフにすると、ステータスが変更されるだけで、ユーザーがすぐにオンになることはありません (ユーザーが自分でオフにすることもできます)。アシスタントを使用するまで待ちます。
     * このスイッチを書き込んでいる間、ミカは動きません。書き込み側によっては、システムがすぐにスイッチを戻す可能性があります。
     */
    fun wirelessDebuggingChanged() {
        if (helper.value != null || !platform.paired()) return
        if (autoOn?.writing == true) return
        if (platform.wirelessDebugging() == false) {
            _status.value = offStatus()
            return
        }
        autoOn?.reset()
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /**
     * は Wi-Fi に接続されています。以前に「Wi-Fi がありません」で止まった場合は、すぐに再試行してください (インターネットにアクセスできる必要はありません)。ユーザーに対してワイヤレス デバッグを有効にできる場合、
     * がスタックし、ワイヤレス デバッグがオフになっているため (以前のネットワークが許可されていないことを含む)、すぐに再試行してください。ネットワークを変更すると、前のネットワークでの拒否はカウントされなくなります。
     */
    fun wifiAvailable() {
        autoOn?.reset()
        val s = _status.value
        val off = s == BuiltinShellStatus.WirelessDebuggingOff || s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        if (s != BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi) && !(autoOn != null && off)) return
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /** ワイヤレス デバッグがオフになっているときに表示されるステータス: Wi-Fi がシステムによってダイヤルバックされたばかりの場合は、正直に伝えます。それ以外の場合はオフになります。 */
    private fun offStatus(): BuiltinShellStatus =
        if (autoOn?.blocked?.value == AutoOnBlock.NotAllowedHere) BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        else BuiltinShellStatus.WirelessDebuggingOff

    /** ワイヤレス デバッグがオフになっています。ユーザーがオンにできる場合は、オンになります。再度オンにすると、true が返されて接続されます。それ以外の場合は、ステータスの書き込み後に false を返します。 */
    private suspend fun turnOnWirelessDebugging(): Boolean {
        val auto = autoOn ?: return false.also { _status.value = offStatus() }
        when (val g = auto.gate(platform.wifiConnected())) {
            AutoOnGate.Act -> {
                _status.value = BuiltinShellStatus.Connecting
                if (withContext(io) { auto.turnOn() }) return true
            }
            // 最後に書いたのが近すぎるので、また書きます
            is AutoOnGate.Wait -> synchronized(this) {
                retryJob = scope.launch {
                    delay(g.ms)
                    launchOnce()
                }
            }
            is AutoOnGate.Skip -> Unit
        }
        _status.value = offStatus()
        return false
    }

    @Synchronized
    private fun schedule(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            launchOnce()
        }
    }

    private suspend fun launchOnce(): Unit = launchLock.withLock {
        if (helper.value != null) return@withLock
        if (!platform.paired()) {
            _status.value = unpairedStatus()
            return@withLock
        }
        if (platform.wirelessDebugging() == false && !turnOnWirelessDebugging()) return@withLock
        _status.value = BuiltinShellStatus.Connecting
        val failure = attempt()
        if (failure == null) {
            lastError = null
            _status.value = BuiltinShellStatus.Running
            return@withLock
        }
        _status.value = BuiltinShellStatus.Failed(failure)
        val next = synchronized(this) { ++failures }
        if (BuiltinShellFailures.retryable(failure)) backoff.delayAfter(next)?.let { d ->
            synchronized(this) {
                retryJob = scope.launch {
                    delay(d)
                    launchOnce()
                }
            }
        }
    }

    private suspend fun attempt(): BuiltinShellFailure? = try {
        withContext(io) { platform.launchHelper() }
        if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) null
        else BuiltinShellFailure.HelperStartFailed.also { lastError = "helper handoff timed out" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbConnectionException) {
        lastError = describe(e)
        val f = BuiltinShellFailures.ofConnection(e.reason)
        if (f == BuiltinShellFailure.ConnectFailed && !platform.wifiConnected()) BuiltinShellFailure.NoWifi else f
    } catch (e: AdbKeyUnreadableException) {
        lastError = describe(e)
        BuiltinShellFailure.KeyUnreadable
    } catch (e: Exception) {
        lastError = describe(e)
        BuiltinShellFailure.Unknown
    }

    private fun describe(e: Exception): String =
        e.javaClass.simpleName + ((e as? AdbConnectionException)?.reason?.let { "($it)" } ?: (e as? AdbPairingException)?.reason?.let { "($it)" } ?: "") +
            (e.message?.let { ": " + it.take(200) } ?: "")

    override fun startDiscovery() {
        synchronized(this) {
            // まだ探している場合は、もう一度開始してください: ユーザーはペアリングの問題に戻り、検索の制限時間はこれから計算されます。
            discoveryJob?.cancel()
            _discovery.value = PairingDiscovery.Searching
            discoveryJob = scope.launch {
                val port = withContext(io) { platform.findPairingPort(discoveryTimeoutMs) }
                _discovery.value = port?.let { PairingDiscovery.Found(it) } ?: PairingDiscovery.Idle
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(this) {
            discoveryJob?.cancel()
            discoveryJob = null
        }
        _discovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        _status.value = BuiltinShellStatus.Connecting
        val target = port ?: (_discovery.value as? PairingDiscovery.Found)?.port
            ?: withContext(io) { platform.findPairingPort(pairingPortTimeoutMs) }
        if (target == null) {
            lastError = "pairing port not found"
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
            return
        }
        val failure = try {
            withContext(io) { platform.pair(target, code) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbPairingException) {
            lastError = describe(e)
            BuiltinShellFailures.ofPairing(e.reason)
        } catch (e: Exception) {
            lastError = describe(e)
            BuiltinShellFailure.Unknown
        }
        if (failure != null) {
            _status.value = BuiltinShellStatus.Failed(failure)
            return
        }
        platform.setPaired(true)
        stopDiscovery()
        retry()
    }

    override suspend fun retry() {
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
        }
        // ユーザーが「再試行」をクリックしました: ユーザーのワイヤレス デバッグを有効にしたときの前回の拒否はカウントされなくなりました (システムは、許可されていないネットワーク上で再度要求します)
        autoOn?.reset()
        if (helper.value != null) {
            _status.value = BuiltinShellStatus.Running
            return
        }
        launchOnce()
    }

    override suspend fun unpair() {
        synchronized(this) { retryJob?.cancel() }
        launchLock.withLock {
            withContext(io) {
                // まず、「コンピューターの起動」について忘れてください。アシスタントが停止すると、ステータスは設定がなく、コマンドを再実行するためのプロンプトは表示されません。
                platform.setUsbStarted(false)
                runCatching { platform.stopHelper() }
                platform.forget()
            }
            platform.setPaired(false)
            _status.value = BuiltinShellStatus.NotPaired
        }
    }
}
