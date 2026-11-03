package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalPairingTest {
    private fun t(id: String, vararg apps: String) =
        EvalTask(id = id, goal = "g", apps = apps.toList(), category = EvalCategory.Read, expect = Expect.Succeeded, checks = emptyList())

    // が使用するアプリは、2 つの素な質問のみに一致します。質問の順番に貪欲に
    @Test fun pairsDisjointAppsGreedily() {
        val tasks = listOf(t("a", "settings"), t("b", "settings"), t("c", "clock"), t("d", "clock", "settings"), t("e", "calc"))
        assertEquals(listOf(listOf("a", "c"), listOf("b", "e"), listOf("d")), EvalPairing.groups(tasks, 2).map { g -> g.map { it.id } })
    }

    // は、どのアプリが使用されたかを記録していませんでした。衝突が発生するかどうかはわからないので、別々に実行しました。 n=1 はシリアルを意味します。
    @Test fun unknownAppsRunAlone() {
        val tasks = listOf(t("a"), t("b", "clock"), t("c", "calc"))
        assertEquals(listOf(listOf("a"), listOf("b", "c")), EvalPairing.groups(tasks, 2).map { g -> g.map { it.id } })
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), EvalPairing.groups(tasks, 1).map { g -> g.map { it.id } })
    }
}
