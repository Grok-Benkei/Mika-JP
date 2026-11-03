package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.PlacePace
import kotlin.random.Random

/**
 * 設定の「操作リズム」：アプリの操作は速いですか、人間っぽいですか？ジェスチャ自体 (指を落とすポーズ、保持期間、着地点の偏差、円弧) はすべて人間に似ています ([GesturePacing] を参照)。
 *
 * - [FAST]: アクション間の停止なし、全文を書き込むために 1 回の入力、画面数の上限なし (以前の慣例)。
 * - [STANDARD] (デフォルト): 一度に 1 つの段落を入力し、アクションの間に短時間停止します (同じ画面の場合は約 0.5 ～ 1.2 秒、新しい画面の場合は約 1 ～ 2 秒)。
 *   アプリの知識によると、ブラウジング速度を制限するアプリは遅いアプリです (停止時間が長く、単語数で読み上げ、画面数に上限があり、入力が遅くなります)。
 * - [HUMAN]: すべてのアプリが停止し、遅いアプリに応じて入力されます。画面数の上限は依然としてブラウジング速度を制限するアプリのみに適用されます。
 */
enum class OperatingPace {
    FAST, STANDARD, HUMAN;

    /** このタスクはアプリで段階的に実行されます。 */
    fun appPacing(random: Random = Random.Default): AppPacing = when (this) {
        FAST -> AppPacing.Off
        STANDARD -> AppPacing.human(random)
        HUMAN -> AppPacing.human(random, limits = AppPacing.Limits.CAREFUL)
    }

    /** [pace]のアプリでのタイピングのリズム。 */
    fun typing(pace: PlacePace, random: Random = Random.Default): TypingPacing = when {
        this == FAST -> TypingPacing.Instant
        this == HUMAN || pace == PlacePace.CAREFUL -> TypingPacing.human(random, TypingPacing.Limits.CAREFUL)
        else -> TypingPacing.human(random)
    }

    companion object {
        val DEFAULT = STANDARD

        /** 保存された名前 (大文字と小文字は関係ありません)。認識されない場合は null です。 */
        fun parse(value: String?): OperatingPace? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
