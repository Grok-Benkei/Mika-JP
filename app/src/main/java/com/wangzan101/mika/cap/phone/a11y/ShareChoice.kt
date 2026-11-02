package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.settings.AppAccessStore

/**
 * ユーザーが無効化されたアプリケーションの画面をアクティブに共有する場合、フローティング レイヤーの 3 つのオプションは何に対応しますか。
 * 1 = 画面共有のみを許可します (将来的に積極的に共有できますが、自動読み取りと操作は依然として禁止されています)、2 = このアプリケーションを許可します (通常の通話を復元します)、0/その他 = 共有をキャンセルします。
 * この共有を継続するかどうかを返します。キャンセルしても権限は変更されません。 「共有のみを許可する」を選択しても無効化は解除されず、「このアプリを許可する」を選択すると独立した共有許可がクリアされます。
 */
internal fun applyShareChoice(choice: Int, pkg: String, store: AppAccessStore): Boolean = when (choice) {
    1 -> { store.setShareAllowed(pkg, true); true }
    2 -> { store.setBlocked(pkg, false); true }
    else -> false
}
