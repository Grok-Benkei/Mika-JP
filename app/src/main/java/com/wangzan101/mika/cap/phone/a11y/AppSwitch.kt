package com.wangzan101.mika.cap.phone.a11y

/** フロントが別のバッグに交換されていますが、これは「切り取り」に該当しますか？ */
internal object AppSwitch {
    /** [byTarget]: 新しいフロントエンドは、ターゲット アプリによって独自のタスク (別のアプリへの共有など) で開かれ、この文言は中断とみなされるべきではありません。 */
    data class Switch(val packageName: String, val byTarget: Boolean)

    /**
     * は削除されたものとしてカウントされません。システム セレクター、ファイル/写真セレクター、アクセス許可ボックス ([partOfTask])、およびターゲット アプリは独自のタスク内にあります。
     * は、システム設定ページ ([settingsPage]、システム設定によって提供される都市選択ページなど) を開きます。対象タスク内の他のアプリ
     * (プリインストールされたブラウザー、アプリ ストアを含む) は引き続き報告されますが、Target でマークされています。
     * [sameTask] セレクターではなく、パッケージ名が異なる場合にのみチェックします。
     * パッケージ名が同じ場合は、[otherCopy] のみを参照してください。同じアプリが 2 つのコピー (クローン、作業データ) にインストールされており、一番上のものが対象外のものに置き換えられている場合も、切り取られたとみなされます。
     */
    fun switchedTo(
        target: String?,
        current: String?,
        sameTask: () -> Boolean,
        partOfTask: Set<String>,
        settingsPage: (String) -> Boolean,
        otherCopy: () -> Boolean = { false },
    ): Switch? {
        if (target != null && current == target) return if (otherCopy()) Switch(current, byTarget = false) else null
        if (target == null || current == null || current in partOfTask) return null
        if (!sameTask()) return Switch(current, byTarget = false)
        return if (settingsPage(current)) null else Switch(current, byTarget = true)
    }
}
