package com.wangzan101.mika.core.shell

import kotlinx.coroutines.flow.MutableStateFlow

/** テストするとき、まだ実際の実装に接続していないときに使用します。呼び出しを書き留めます。ステータスは呼び出し元によって設定されます。 [pairingResult] は [startPairing] 後の状態です。 */
class FakeBuiltinShellControl(
    initial: BuiltinShellStatus = BuiltinShellStatus.NotPaired,
    var pairingResult: BuiltinShellStatus = BuiltinShellStatus.Running,
    var retryResult: BuiltinShellStatus = BuiltinShellStatus.Running,
) : BuiltinShellControl {
    override val status = MutableStateFlow(initial)
    override val pairingDiscovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    val calls = mutableListOf<String>()

    override fun startDiscovery() {
        calls += "discover"
        if (pairingDiscovery.value == PairingDiscovery.Idle) pairingDiscovery.value = PairingDiscovery.Searching
    }

    override fun stopDiscovery() {
        calls += "stopDiscovery"
        pairingDiscovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        calls += "pair $code" + (port?.let { " $it" } ?: "")
        status.value = BuiltinShellStatus.Connecting
        status.value = pairingResult
    }

    override suspend fun retry() {
        calls += "retry"
        status.value = BuiltinShellStatus.Connecting
        status.value = retryResult
    }

    override suspend fun unpair() {
        calls += "unpair"
        status.value = BuiltinShellStatus.NotPaired
    }
}
