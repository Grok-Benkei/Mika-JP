package com.wangzan101.mika.core.uistate

/** 1画面に1つのバリアフリー窓。ルート ノードを読み取れない場合でも、障害物または読み込みインターフェイスがあることをモデルに知らせるためにウィンドウは保持されます。 */
data class UiWindowTree(
    val id: Int,
    val type: String,
    val layer: Int,
    val title: String?,
    val packageName: String?,
    val active: Boolean,
    val focused: Boolean,
    val nodes: List<UiTreeNode>,
)

/** は表示可能なアクセス可能なノードです。この画面の要素テーブルから解析できるノードのみが [ref] を持ちます。 */
data class UiTreeNode(
    val role: String,
    val bounds: List<Int>,
    val ref: String? = null,
    val text: String? = null,
    val description: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val secret: Boolean = false,
    /** はスクロール ホイール セレクターのように見えます (時間、日付、地域... 1 つずつ回転します)。一度に値に移動するには、スクロール ホイール ツールを使用します。 */
    val wheel: Boolean = false,
    val children: List<UiTreeNode> = emptyList(),
) {
    /** 安定番号はモデル アドレスを置き換えるだけです。ノード自体と Android 側の参照テーブルは変更されません。 */
    fun withRefs(refs: Map<String, String>): UiTreeNode = copy(
        ref = ref?.let { refs[it] ?: it },
        children = children.map { it.withRefs(refs) },
    )
}
