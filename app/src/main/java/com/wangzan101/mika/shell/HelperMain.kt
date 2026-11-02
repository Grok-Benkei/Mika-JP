package com.wangzan101.mika.shell

import com.wangzan101.mika.cap.phone.shell.HelperProcess

/** 組み込みヘルパー プロセスへの入り口: フルネームは起動コマンドに書き込まれます (HelperHandoff.HELPER_CLASS を参照)。それは携帯電話の機能に実装されています。 */
object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) = HelperProcess.main(args)
}
