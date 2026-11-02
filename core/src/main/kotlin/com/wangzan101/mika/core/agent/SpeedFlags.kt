package com.wangzan101.mika.core.agent

/** 高速化スイッチの明示的な完全オフ値。アプリの最初のデフォルト値は、FeatureFlags によって決定されます。 */
data class SpeedFlags(
    val inlineScreen: Boolean = false,
    val batchSteps: Boolean = false,
    val replay: Boolean = false,
    val deepLinks: Boolean = false,
    val lightSteps: Boolean = false,
    val parallelTasks: Boolean = false,
    /** が圧縮されると、概要が 4 つの構造化された段落に書き込まれ、推定値が実際の使用状況に基づいて調整されます (モデルが認識する内容が変化し、評価と比較の後に決定されます)。 */
    val structuredCompaction: Boolean = false,
    /** 要素が優先され、メイン モデル画面は、それが不明瞭な場合、またはモデルによって積極的に要求された場合にのみ表示されます。 */
    val adaptiveVision: Boolean = false,
) {
    private fun on(): Map<String, Boolean> = mapOf(
        "inlineScreen" to inlineScreen, "batchSteps" to batchSteps,
        "replay" to replay, "deepLinks" to deepLinks, "lightSteps" to lightSteps, "parallelTasks" to parallelTasks,
        "structuredCompaction" to structuredCompaction,
        "adaptiveVision" to adaptiveVision,
    )

    /** オンになっているスイッチ名がアルファベット順にカンマで接続されます。オフはすべて「なし」です。 [parse] を読み戻すことができます。 */
    fun describe(): String = on().filterValues { it }.keys.sorted().joinToString(",").ifEmpty { NONE }

    companion object {
        private const val NONE = "none"
        val OFF = SpeedFlags()
        private val KEYS = OFF.on().keys
        /** プラスになった、または削除されたスイッチ: 保存された設定や評価パラメータにまだ残っている可能性がありますので、読んだ場合はスキップしてください。 */
        private val RETIRED = setOf("cacheFriendlyPrune")

        /** "なし" (または空) = すべてオフ。それ以外の場合は、カンマ区切り `键=on|off` または単にキー名 (= on) を書き込みます。不明なキーまたは値が [IllegalArgumentException] をスローします。 */
        fun parse(spec: String): SpeedFlags {
            val s = spec.trim()
            if (s.isEmpty() || s == NONE) return OFF
            val set = mutableMapOf<String, Boolean>()
            for (item in s.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val key = item.substringBefore('=').trim()
                val value = if ('=' in item) item.substringAfter('=').trim() else "on"
                if (key in RETIRED) continue
                require(key in KEYS) { "unknown speed flag: $key" }
                set[key] = when (value) {
                    "on" -> true
                    "off" -> false
                    else -> throw IllegalArgumentException("speed flag $key must be on or off, got $value")
                }
            }
            fun f(k: String) = set[k] ?: false
            return SpeedFlags(f("inlineScreen"), f("batchSteps"), f("replay"), f("deepLinks"), f("lightSteps"), f("parallelTasks"), f("structuredCompaction"), f("adaptiveVision"))
        }
    }
}
