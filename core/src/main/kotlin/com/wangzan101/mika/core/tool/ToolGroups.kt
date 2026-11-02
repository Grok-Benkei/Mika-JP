package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.ToolSpec

/**
 * ツールは 2 つの層でモデルに送信されます。1 つは常に存在するもの (コアの共通ツール、収集されていない能力)、もう 1 つは能力によって分割されたグループ (入り口のツールのみ、収集時に短い説明付き) です。
 * を初めて使用するときは、それをグループ全体に展開し、フルテキストに戻します。これはこのタスクに保持されます)。画面のあるグループが拡張された場合にのみ提供される一般ツール (引き継ぎ、障害の報告、画面の確認、一度に複数のステップ)
 * は [SCREENS] のグループとしてカウントされます。画面が展開されている場合は、それも展開されます。
 *
 * 固定順：常に存在→[SCREENS]→各能力のグループ（登録順）。グループを展開し、そのグループから始まる部分のみを変更し、
 * の前のバイトは変更されません。リクエストプレフィックスは拡張により最大 1 回無効になります。
 */
internal object ToolGroups {
    /** は、複数の画面グループで共有されるグループです。 */
    const val SCREENS = "screens"

    /** 凍結されたツール: フルテキスト定義、閉じたときのエントリ定義 (エントリが null ではない)、およびそれが属するグループ (常に null)。 */
    class Slot(val full: ToolSpec, val entry: ToolSpec?, val group: String?)

    /**
     * は、現時点でモデルに送信されるツールです。 [expanded] は、拡張されたグループ ([SCREENS] を含む) です。 [extra] はループ自体によって与えられる定義であり、特定のグループに属します
     * (一度に複数のステップが [SCREENS] に属します)。そのグループの最後にランクされ、そのグループが展開された場合にのみ与えられます。
     */
    fun layout(slots: List<Slot>, expanded: Set<String>, extra: Map<String, List<ToolSpec>> = emptyMap()): List<ToolSpec> =
        order(slots).flatMap { g ->
            val members = slots.filter { it.group == g }
            if (g == null || g in expanded) members.map { it.full } + extra[g].orEmpty()
            else members.mapNotNull { it.entry }
        }

    /** 各グループの順序: 以前から存在するグループ、[SCREENS]、その後に能力が最初に出現する順序。 */
    fun order(slots: List<Slot>): List<String?> = listOf(null, SCREENS) + slots.mapNotNull { it.group }.filter { it != SCREENS }.distinct()
}
