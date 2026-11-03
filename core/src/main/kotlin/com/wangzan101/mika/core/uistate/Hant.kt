package com.wangzan101.mika.core.uistate

/**
 * 外部画面や入力の中国語を照合するため、辞書で必要な繁体字だけを簡体字へ正規化する。
 * 表示文やプロンプトの翻訳は行わず、日本語の案内とは分けて照合用データに使う。
 * 対応が必要な文字だけを収録し、照合語の追加時に必要な対応を補う。
 */
object Hant {
    internal const val TRADITIONAL ="個買優餘內減刪動單歷發臺後唄塊復寶實對崗應錄總報機溝點現碼確簡紅約紋細結繳羣職聯獲計訂認記論設證評該說請賬貨購費賞車轉輸運進遞錢銀銷鍵預額馬驗帳説傳尋儲註覆佈繫"
    internal const val SIMPLIFIED = "个买优余内减删动单历发台后呗块复宝实对岗应录总报机沟点现码确简红约纹细结缴群职联获计订认记论设证评该说请账货购费赏车转输运进递钱银销键预额马验账说传寻储注复布系"
    private val MAP = TRADITIONAL.zip(SIMPLIFIED).toMap()

    fun fold(text: String): String {
        if (text.none { it in MAP }) return text
        return String(CharArray(text.length) { MAP[text[it]] ?: text[it] })
    }
}
