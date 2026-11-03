package com.wangzan101.mika.cap.phone

import android.content.Context
import com.wangzan101.mika.cap.phone.setup.BuiltinShellManager
import com.wangzan101.mika.cap.phone.setup.BuiltinShellRecovery
import com.wangzan101.mika.cap.phone.setup.PairingCoordinator
import com.wangzan101.mika.cap.phone.setup.PhoneSetupPrompt
import com.wangzan101.mika.cap.phone.shell.BuiltinShellConnection
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.phone.shell.SharedShell
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.settings.RecoverySettings

/**
 * スマホ機能の Mika プロセス内サービスで、App が1つ作成して [Owner] 経由で提供する: 内蔵ヘルパー（引き渡しの受け口、ペアリングとウォッチドッグ、
 * 自動回復と認証）、タスクと起動回復によって共有されるシェル接続、ペアリング プロセス、および「設定に移動」プロンプト。
 * ヘルパー プロセス (HelperMain、ShellService) はシェル ID で実行され、App オブジェクトがないため、ここでは渡されません。
 */
class PhoneServices(context: Context) {
    private val app = context.applicationContext

    /** アシスタントはバインダー配置ポイントを引き渡しました。アシスタントは 1 つのプロセスで 1 つの配置ポイントのみを認識します。 */
    val hub = HelperHub(app)

    /** ペアリング、adbd ブート アシスタント、ウォッチドッグ。 */
    val shellManager = BuiltinShellManager(app, hub) { recoverySettings.consent.value }

    /** 内蔵アシスタント自体 (ステータス、ペアリング、再試行、ペアリング解除)。 */
    val shellControl: BuiltinShellControl get() = shellManager.engine

    /** 自動回復と認証スイッチの設定。 */
    val recoverySettings: RecoverySettings by lazy { RecoverySettings(RecoverySettings.prefs(app)) }

    /** は自動的に回復し、それらの認証が切り替わります。 */
    val recovery: BuiltinShellRecovery by lazy { BuiltinShellRecovery(app, shellManager.commandRunner, recoverySettings, shellControl) }

    /** タスクと起動リカバリによって共有されるシェル接続。 */
    val sharedShell = SharedShell { BuiltinShellConnection(hub) { shellManager.need() } }

    /** ペアリングの全プロセスを一度に完了します。 */
    val pairing: PairingCoordinator by lazy { PairingCoordinator(app, shellControl) }

    /** タスクで携帯電話とアシスタントの使用が必要であるが、実行されていない場合にプロンプ​​トが表示されます。 */
    val setupPrompt = PhoneSetupPrompt(app)

    /** はプロセス中のアプリケーションを示します。 */
    interface Owner {
        val phone: PhoneServices
    }

    companion object {
        fun of(context: Context): PhoneServices = (context.applicationContext as? Owner)?.phone
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement PhoneServices.Owner to give the phone's shared services")
    }
}
