package com.wangzan101.mika.cap.phone.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.launch

/**
 * ブート ブロードキャスト。ユーザーが「再起動後の自動回復」をオンにしており、ミカがシステム設定を変更する権限をまだ持っている場合にのみ、システム設定を変更します。
 * は何もしません（判定は [BuiltinShellRecovery.restoreAfterBoot]）。
 */
open class BootRestoreHandler : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val phone = PhoneServices.of(context)
        phone.pairing.scope.launch {
            try {
                runCatching { phone.recovery.restoreAfterBoot() }
            } finally {
                pending.finish()
            }
        }
    }
}
