package com.wangzan101.mika.core.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** は、ユーザーに対してワイヤレス デバッグが停止される理由を説明します。停止後、再度書き込みを行う前に、Wi-Fi が変更されるか、ユーザーが Wi-Fi を開くか、[再試行] をクリックするか、アシスタントが起動するまで待つ必要があります。 */
enum class AutoOnBlock {
    /** はオープンするように書き込まれており、システムはすぐにオフに戻します。この Wi-Fi はまだワイヤレス デバッグに許可されていません (システムはユーザーに許可するかどうかも尋ねます)。 */
    NotAllowedHere,
    /** システムが書き込みを許可しない（書き込みが例外を投げるか失敗を返す）。 */
    Refused,
    /** ユーザーの代わりに [WirelessDebuggingAutoOn.MAX_WRITES] 回続けて開いたのに、ヘルパーがまだ起動しない。 */
    GaveUp,
}

/** この時点でユーザーの代わりにワイヤレスデバッグを開くかどうか。 */
sealed interface AutoOnGate {
    data object Act : AutoOnGate
    /** 前回の書き込みから近すぎる: [ms] 待ってから再試行する。 */
    data class Wait(val ms: Long) : AutoOnGate
    data class Skip(val reason: AutoOnSkip) : AutoOnGate
}

enum class AutoOnSkip {
    /** ユーザーは、Mika の「ワイヤレス デバッグを自動的にオンにする」をオフにしました。 */
    Declined,
    /** ミカにはシステム設定を変更する権限がありません。 */
    NoPermission,
    /** が Wi-Fi に接続されていません: Wi-Fi がない場合、システムはワイヤレス デバッグをオンにしません (書き込みがあればダイヤルバックされます)。 */
    NoWifi,
    /** は停止しました。[AutoOnBlock] を参照してください。 */
    Blocked,
}

/**
 * 内蔵アシスタントを開始したいが、ワイヤレス デバッグがオフになっている場合は、ユーザーに対してオンにします (オンのみでオフではありません)。アシスタントが起動すると、ワイヤレス デバッグは必要なくなります。
 * ただし、(携帯電話を再起動した後、ミカが更新され、アシスタントがなくなった後) 再起動するには、ワイヤレス デバッグが必要です。一方、元の Android バージョンでは、Wi-Fi が切断されたときと再起動後にオフになります。
 *
 * は、ユーザーが「ワイヤレス デバッグを自動的にオンにする」をオフにしていない、ミカにシステム設定を変更する権限があり、Wi-Fi に接続されている場合にのみ書き込まれます。
 * もちろん、アシスタントが起動されたときにはアシスタントは存在しません。コマンドを実行するためにすでにペアリングおよび接続されており、最初にワイヤレス デバッグが必要であるため、アプリのみがこのメソッドを直接記述できます。それができない場合は移動せず、インターフェースは依然として手動で開かれます。
 *
 * が依存する Android 本来の動作 (AdbDebuggingManager による `adb_wifi_enabled` の処理):
 * - 設定が 1 に書き込まれた後、システムは現在の Wi-Fi をチェックします。Wi-Fi が接続されていない場合は、すぐに 0 に書き戻されます。
 *   このネットワーク (BSSID 別) は「常に許可」リストに含まれていないため、すぐに 0 に書き戻されます。同時に、ポップアップ ボックスがユーザーに「このネットワークでワイヤレス デバッグは許可されていますか?」と尋ねます。 （「常に許可」にチェックを入れることもできます）。
 *   ユーザーが [許可] をクリックすると、システムは 1 を書き戻して開きます。拒否された場合は閉じたままになります。
 * - ネットワークがリストにあります: 1 を保持し、ワイヤレス デバッグ サービスを開始します (ポートは接続ステップで通常どおり検出されます)。
 * ということで、書いた後は[settleMs]を読んでください：この間、読んでみたらWi-Fiはダメだと思ったのでやめました（[AutoOnBlock.NotAllowedHere]）。
 * は、Wi-Fi が変更されるか、ユーザーが手動で開くか、[再試行] をクリックするか、アシスタントが起動する ([reset]) まで、繰り返し書き込まれることはなくなります (書き込まれるたびにシステムがボックスをポップアップ表示します)。
 * 一部のシステムではこの項目に関して別の処理（書き込みが拒否される、書き込みが有効にならない）があるため、停止されており、各社の実装については掘り下げていません。
 * [gapAfter] を押して、書き込み間の間隔を広げます。アシスタントが立ち上がることなく、[MAX_WRITES] 回連続で書き込みました。
 */
class WirelessDebuggingAutoOn(
    /** ユーザーはこのオプションを必要としません (Mika で「ワイヤレス デバッグを自動的にオンにする」をオフにしていません)。 */
    private val wants: () -> Boolean,
    /** ミカはシステム設定を変更する権限を持っています。 */
    private val canWrite: () -> Boolean,
    /** システムのワイヤレス デバッグ スイッチ;読み取られない場合は null です。 */
    private val read: () -> Boolean?,
    /** ワイヤレス デバッグ スイッチを開くように書き込みます。システムでは、書き込み時に false を返したり、例外をスローしたりすることはできません。 */
    private val write: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val settleMs: Long = SETTLE_MS,
    private val pollMs: Long = POLL_MS,
) {
    private val _blocked = MutableStateFlow<AutoOnBlock?>(null)
    /** 停止の理由。停止していない場合は null。設定ページとステータスについて何かコメントしてください。 */
    val blocked: StateFlow<AutoOnBlock?> = _blocked.asStateFlow()

    /** は書き込みを行っており、システム処理を待機しています。この期間中のスイッチの変更はここで判断され、他のスイッチはこれに従うべきではありません。 */
    @Volatile var writing = false
        private set

    private var writes = 0
    private var lastWriteAt = 0L

    @Synchronized
    fun gate(wifi: Boolean): AutoOnGate {
        if (_blocked.value == null && writes >= MAX_WRITES) _blocked.value = AutoOnBlock.GaveUp
        return decide(wants(), canWrite(), wifi, _blocked.value, writes, now() - lastWriteAt)
    }

    /** 書き込み用にオープンし、システムが処理を完了するまで待ちます。スイッチが開いたままの場合は true を返します。拒否または差し戻された場合は false を返し、停止の理由を記録します。 */
    suspend fun turnOn(): Boolean {
        synchronized(this) {
            writing = true
            writes++
            lastWriteAt = now()
        }
        try {
            val accepted = runCatching(write).getOrDefault(false)
            if (!accepted || read() == false) return false.also { block(if (accepted) AutoOnBlock.NotAllowedHere else AutoOnBlock.Refused) }
            var waited = 0L
            while (waited < settleMs) {
                delay(pollMs)
                waited += pollMs
                if (read() == false) return false.also { block(AutoOnBlock.NotAllowedHere) }
            }
            return true
        } finally {
            writing = false
        }
    }

    /** が Wi-Fi を変更し、ユーザーがワイヤレス デバッグをオンにするか、[再試行] をクリックすると、アシスタントが表示されます。「最初から計算する」。 */
    @Synchronized
    fun reset() {
        writes = 0
        lastWriteAt = 0L
        _blocked.value = null
    }

    private fun block(reason: AutoOnBlock) {
        _blocked.value = reason
    }

    companion object {
        /** 書き込み後、システムによってダイヤルバックされるかどうかを確認するのにどれくらい時間がかかるかを確認します。元のバージョンは設定変更を受信するとすぐに処理され、数百ミリ秒以内にダイヤルバックされます。 */
        const val SETTLE_MS = 3_000L
        const val POLL_MS = 250L
        const val MAX_WRITES = 3
        const val BASE_GAP_MS = 10_000L
        const val MAX_GAP_MS = 5 * 60_000L

        /** が [writes] 回書き込んだ後、少なくとも次回はどのくらい時間がかかりますか。最初の時間は変わりませんが、その後は 10 秒、20 秒…上限は 5 分です。 */
        fun gapAfter(writes: Int): Long =
            if (writes <= 0) 0L else (BASE_GAP_MS shl (writes - 1).coerceAtMost(20)).coerceAtMost(MAX_GAP_MS)

        fun decide(wants: Boolean, canWrite: Boolean, wifi: Boolean, blocked: AutoOnBlock?, writes: Int, sinceLastMs: Long): AutoOnGate {
            if (!wants) return AutoOnGate.Skip(AutoOnSkip.Declined)
            if (!canWrite) return AutoOnGate.Skip(AutoOnSkip.NoPermission)
            if (blocked != null || writes >= MAX_WRITES) return AutoOnGate.Skip(AutoOnSkip.Blocked)
            if (!wifi) return AutoOnGate.Skip(AutoOnSkip.NoWifi)
            val gap = gapAfter(writes)
            return if (sinceLastMs < gap) AutoOnGate.Wait(gap - sinceLastMs) else AutoOnGate.Act
        }
    }
}
