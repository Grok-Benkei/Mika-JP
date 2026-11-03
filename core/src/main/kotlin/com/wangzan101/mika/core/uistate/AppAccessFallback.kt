package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.access.AppAccessDenied

/** Screen Reading の最善の努力では、アクセス許可の拒否をコンテンツなしにダウングレードすることはできません。 */
internal fun Exception.rethrowAppAccess() {
    if (this is AppAccessDenied) throw this
}

/** 通常の収集失敗はダウングレードできます。許可の拒否とキャンセルは呼び出し元に返される必要があります。 */
internal suspend inline fun <T> screenAttempt(body: () -> T): T? = try {
    body()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    e.rethrowAppAccess()
    null
}
