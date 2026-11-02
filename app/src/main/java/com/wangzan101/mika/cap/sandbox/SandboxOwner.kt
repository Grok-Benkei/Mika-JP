package com.wangzan101.mika.cap.sandbox

import android.content.Context
import com.wangzan101.mika.core.workspace.SandboxTree

/** プロセス内のサンドボックスのステータスとツリー ロック: アプリ、設定ページ、インストール、およびエージェントによって提供される同じコピーが表示されます。 */
interface SandboxOwner {
    val sandboxState: SandboxState
    val sandboxTree: SandboxTree

    companion object {
        fun of(context: Context): SandboxOwner = context.applicationContext as? SandboxOwner
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement SandboxOwner to give the sandbox's shared state")
    }
}
