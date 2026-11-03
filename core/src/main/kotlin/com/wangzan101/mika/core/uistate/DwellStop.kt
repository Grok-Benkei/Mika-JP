package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.fail

/** この Web サイト上のこのタスクで表示されたページと、このアプリによって変更された画面が上限に達しました。これ以上ページや画面を変更することはできません ([PageLimitReached] を参照)。 */
const val PAGE_LIMIT = "page_limit"

/** ページ変更または画面変更前の一時停止では、このアクションを実行できません。発信者はアクションを実行せず、[code] を押して報告します ([dwellStopped] を参照)。 Web ページ ([com.wangzan101.mika.core.cap.web.PageDwell]) とアプリ (携帯電話機能はそのまま) で共有されます。 */
internal sealed class DwellStop(message: String) : Exception(message) {
    abstract val code: String
    /** 詳細は説明に添付されています。 no は null です。 */
    open val detail: String? get() = null
}

/** このタスクでは、Web サイトのページと閲覧速度を制限するアプリの画面が上限に達しました。これ以上ページや画面が変更されることはありません。最初にユーザーに答えるか質問してください。 [site] は、Web サイトのホスト名またはアプリのパッケージ名です。 */
internal class PageLimitReached(val site: String, val pages: Int, val cap: Int) :
    DwellStop("Pages or screens opened in $site in this task: $pages (limit $cap).") {
    override val code get() = PAGE_LIMIT
    override val detail get() = message
}

/** ユーザーが滞在中に中断しました。今回はしませんが、最初にその文を読んでください。 */
internal class DwellInterrupted : DwellStop("The pause before this action was cut short by a new message from the user.") {
    override val code get() = SKIPPED_FOR_NEW_MESSAGE
}

/** このアクションを実行しないツールの結果: 停止した理由と次に何をすべきかを説明します (制限に達した場合は、この Web サイトとアプリの数ページを添付してください)。 */
internal fun dwellStopped(e: DwellStop): ToolExecution =
    fail(e.code, listOfNotNull(FAILURE_MESSAGES.getValue(e.code), e.detail).joinToString(" "))
