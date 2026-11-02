package com.wangzan101.mika.core.uistate

/**
 * この画面のアクセシビリティ ツリーが十分かどうか。そうでない場合は、画面読み取り結果のtreeQualityを書き込みます。
 * [Partial]: ツリーの一部が欠落しています (ほとんどのコントロールに名前がないか、ツリーにない単語が画面上に多数あります)。 [Unusable]: ツリーには読み取るものはほとんどありません。
 * [Protected]: スクリーンショット対策ページ。画面が表示されません (まだ保護されているものとして扱われます)。
 */
internal enum class TreeQuality(val wire: String) {
    Adequate("adequate"), Partial("partial"), Unusable("unusable"), Protected("protected"),
}

/** 一つの判断。 [quality] は null です。ツリーを見ただけで判断するのは正確ではありません。判断するには画面上のテキストを読む必要があります (単語が読めない場合は常に null になります)。 [reason] では不十分です。画像が添付されている場合はimageReasonです。 */
internal data class TreeCheck(val quality: TreeQuality?, val reason: String? = null) {
    /** ツリーに何かが欠けているか、使用できません。モデルは画像または画面上のテキストで補足する必要があります。 */
    val poor: Boolean get() = quality == TreeQuality.Partial || quality == TreeQuality.Unusable
}

/**
 * まずは安い木を使って判断します。判断が正確でない場合は、画面上のテキストを読み取り (OCR) 比較してください。ツリーは「明らかに便利」であるため、読み取ることができません。名前付きの要素は十分にあり、ほとんどのコントロールには名前があり、
 * アプリ ウィンドウには、文字のない大きな領域 (キャンバス、地図、画像、判読できないコンテンツを含む Web ページ) はありません。残りのよくわからない単語については、そのまま読んでください。もう一度読んだ方が良いです。
 */
internal object TreeAdequacy {
    /** 名前を持つ要素の数がこの数より少ない場合は、不確実になります。数語しかない単純なページは、大きな部分が欠けているツリーと同じように見えます。 */
    private const val ENOUGH_NAMED = 6
    /** サブツリー内に単語のないノードが画面 (0 ～ 1) の大部分を占めるため、ブラインド エリアになります。 */
    private const val BLIND_AREA = 0.2
    /** には非常に多くの名前付き要素がありますが、それらはその下のベース プレート (背景画像、コンテナ) にすぎず、死角ではありません。 */
    private const val NAMED_INSIDE = 3
    /** OCR は、ツリーの一部が欠けている場合でも、ツリーにない非常に多くの単語行を埋めることができます。 */
    private const val MISSING_LINES = 3
    private const val APPLICATION = "application"

    fun ofTree(s: ScreenSnapshot): TreeCheck {
        if (s.protected) return TreeCheck(TreeQuality.Protected)
        if (s.unreadableApp != null && s.elements.none { it.meaningful }) return TreeCheck(TreeQuality.Unusable, "unreadable_window")
        if (!s.hasWindow && s.web == null) return TreeCheck(TreeQuality.Adequate)
        if (s.elements.isEmpty()) return TreeCheck(TreeQuality.Unusable, "empty_elements")
        val named = s.elements.filter { it.name.isNotBlank() }
        val controls = s.elements.filter { it.clickable || it.editable }
        // resourceId は開発者の内部番号であることが多く、ユーザーが認識できるコントロール名とは見なされません。
        val namedControls = controls.count { it.name.isNotBlank() || !it.placeholder.isNullOrBlank() }
        if (controls.isNotEmpty() && (namedControls == 0 || (controls.size >= 4 && namedControls * 3 <= controls.size)))
            return TreeCheck(if (named.isEmpty()) TreeQuality.Unusable else TreeQuality.Partial, "unlabeled_controls")
        if (named.isEmpty()) return TreeCheck(TreeQuality.Unusable, "no_text")
        if (named.size < ENOUGH_NAMED || blindArea(s, named)) return TreeCheck(null)
        return TreeCheck(TreeQuality.Adequate)
    }

    /** ツリーが正しく判定されなかった場合は、OCR を押してツリーにない単語を埋めます ([missing]、エラー行は削除されています)。単語が読み取られていない場合 ([missing] が null)、判定は依然として不正確です。 */
    fun withText(tree: TreeCheck, missing: List<VisibleTextLine>?): TreeCheck {
        if (tree.quality != null || missing == null) return tree
        return if (missing.size >= MISSING_LINES) TreeCheck(TreeQuality.Partial, "text_outside_tree") else TreeCheck(TreeQuality.Adequate)
    }

    /** アプリ ウィンドウには盲点があります。サブツリーには単語がなく、十分な大きさであり、名前付き要素の下のベース プレートではありません。 */
    private fun blindArea(s: ScreenSnapshot, named: List<UiElement>): Boolean {
        var found = false
        // は、このサブツリーに単語があるかどうかを返します。 1 回のパスの後、各ノードは 1 回だけ表示されます
        fun visit(node: UiTreeNode): Boolean {
            var text = !node.text.isNullOrBlank() || !node.description.isNullOrBlank() || !node.hintText.isNullOrBlank()
            for (child in node.children) if (visit(child)) text = true
            if (!text && !found && area(node.bounds) >= BLIND_AREA && named.count { inside(it.bounds, node.bounds) } < NAMED_INSIDE) found = true
            return text
        }
        s.windows.filter { it.type == APPLICATION }.forEach { w -> w.nodes.forEach { visit(it) } }
        return found
    }

    private fun area(b: List<Int>): Double =
        if (b.size != 4) 0.0 else (b[2] - b[0]).coerceAtLeast(0).toDouble() * (b[3] - b[1]).coerceAtLeast(0) / (999.0 * 999.0)

    private fun inside(e: List<Int>, b: List<Int>): Boolean {
        if (e.size != 4 || b.size != 4) return false
        val x = (e[0] + e[2]) / 2; val y = (e[1] + e[3]) / 2
        return x in b[0]..b[2] && y in b[1]..b[3]
    }
}
