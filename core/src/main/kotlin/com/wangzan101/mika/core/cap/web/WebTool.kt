package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.uistate.tools.ScreenTool
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

/** Web 固有のツール: Web ポートが接続されている場合にのみ提供されます。 */
internal interface WebTool : AgentTool {
    override val capability: String get() = WEB
    override fun available(ctx: ToolContext) = ctx.web != null
}

/** Web ページ機能の ID は、Web ページ上のツール セットの名前のプレフィックスでもあります。 */
const val WEB = "web"

/** Web ページでは、press_key は Enter、Backspace、Delete、Tab、方向キー、およびページめくりキーのキーのみを受け入れます。 */
private val KEYS = setOf(66, 160, 67, 112, 61, 19, 20, 21, 22, 92, 93)
private const val KEYS_HINT = "on a web page only Enter, Backspace, Delete, Tab, arrow and page keys are allowed"

/** Web ページ上の一連の画面ツール: 画面読み取り番号は w で始まり、サイトが「どこ」で報告されます。体験ルートは Web ページ上の実践を覚えていません。 */
internal val WEB_FAMILY = ScreenFamily(
    WEB, snapshotPrefix = "w", placeKey = ScreenFamily.SITE_PLACE, keys = KEYS, keysHint = KEYS_HINT,
    ownPrompts = setOf("observe", "back"), emptyHint = "empty_page",
)

/** Web ページのセット: 一連の画面ツールに加えて、Web ページ固有のナビゲーション、ページ読み取り、タグ、ダウンロードとアップロード、スクリプト、待機、およびタスク完了後のこのページからの終了。モデルに発行された順序で。 */
internal object WebGroup {
    val tools: List<AgentTool> = ScreenTool.of(WEB_FAMILY) + listOf(
        Navigate, ReadPage, EvaluateJs, RunSiteCommand, Tabs, Downloads, Upload, SiteMode, WebScriptTool, Interact, Capture, WebWaitFor, KeepOpen,
    )
}
