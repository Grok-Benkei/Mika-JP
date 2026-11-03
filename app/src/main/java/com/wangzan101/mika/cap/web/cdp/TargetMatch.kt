package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.core.cap.web.cdp.CdpTargetInfo

/** タグと DevTools ターゲットの対応: まずタグにマークを書き込み、次に候補ターゲットを 1 つずつ評価して見つけます。 */
object TargetMatch {
    /** マークされたグローバル変数名をページに配置します。見つけたら削除してください。 */
    const val KEY = "__mikaDevtoolsMark"

    fun markScript(marker: String): String = "window['$KEY']='$marker';1"
    const val READ_SCRIPT = "window['$KEY']||''"
    const val CLEAR_SCRIPT = "delete window['$KEY'];1"

    /**
     * オブジェクトは 1 つずつテストされます。ページが他のクライアント (webSocketDebuggerUrl を使用) によって接続されておらず、他のタグによって要求されていない限り。
     * 最前列の URL とラベルの現在の URL は同じです (おそらく同じです)。
     */
    fun candidates(targets: List<CdpTargetInfo>, claimed: Set<String>, tabUrl: String?): List<CdpTargetInfo> =
        targets.filter { it.type == "page" && it.webSocketDebuggerUrl != null && it.id !in claimed }
            .sortedBy { if (!tabUrl.isNullOrEmpty() && it.url == tabUrl) 0 else 1 }

    fun isMarker(value: String?, marker: String): Boolean = marker.isNotEmpty() && value == marker
}
