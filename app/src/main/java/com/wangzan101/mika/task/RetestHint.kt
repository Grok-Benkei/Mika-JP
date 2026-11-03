package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.Provider

/**
 * タスクが失敗し、メイン モデルがホームでのテストに合格していない場合 ([untested]、[com.wangzan101.mika.settings.ProviderStore.untestedMainProvider] を参照):
 * 理由の後に新しい行を追加し、その会社の「再テスト」を指します ([hint] は、この文にサービス プロバイダーの名前を示します)。他のすべてのエンディングはそのまま返されます。
 */
internal object RetestHint {
    fun apply(end: TaskState, untested: Provider?, hint: (String) -> String): TaskState =
        if (end is TaskState.Failed && end.kind == FailKind.Model && untested != null) {
            end.copy(reason = end.reason + "\n" + hint(untested.name))
        } else end
}
