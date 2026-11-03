package com.wangzan101.mika.core.uistate

/** 要素自体のコンテキスト。動作を判断するときは、画面全体ではなく、同じ行のテキスト (添付されたラベル、行内の説明) だけを見てください。 */
object ElementContext {
    /** 同行: 中心が要素の上限と下限の範囲に収まるか、要素の中心が上限と下限の範囲に収まります。 */
    fun row(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.bounds.size != 4) return emptyList()
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return s.elements.filter { o ->
            o !== e && o.bounds.size == 4 && ((o.bounds[1] + o.bounds[3]) / 2 in e.bounds[1]..e.bounds[3] || cy in o.bounds[1]..o.bounds[3])
        }
    }
}
