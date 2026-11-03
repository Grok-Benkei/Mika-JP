package com.wangzan101.mika.cap.phone.shell

import kotlinx.coroutines.flow.StateFlow
import com.wangzan101.mika.shell.IMikaShell

/** シェル ID サービスへの接続。 [SharedShell] 参照カウントによるバインド/アンバインド。呼び出し元にはこれらのみが表示されます。 */
interface ShellBinding {
    /** は、バインド中に接続されている場合はサービス インターフェイスであり、それ以外の場合は null です。 */
    val shell: StateFlow<IMikaShell?>

    /** は接続されており必要でしたが、現在は切断されています。再接続するか、バインドを解除してリセットします。 */
    val dropped: StateFlow<Boolean>

    fun bind()

    /** は、バンドル解除後に仮想画面、ミュート、およびクリップボードの変更を残しません。 */
    fun unbind()

    /** 「再接続」: 切断後に自動接続に失敗した場合は、手動で再接続してください。 */
    fun reconnect()
}
