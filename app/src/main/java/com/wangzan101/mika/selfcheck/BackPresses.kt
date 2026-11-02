package com.wangzan101.mika.selfcheck

/**
 * セルフチェック中にリターンを押して、ページが離れるまで待ちます。入力メソッドを押す前に、Android がそれを取り除いて入力メソッドを閉じます (または、最初に画面上にない単語を消去します)。これは 1 回としてカウントされません。
 * 入力メソッドが再生されていないときに [tries] を押した場合、次のページがまだある場合は失敗とみなされます。合計最大押下回数は[maxPresses]です。入力メソッドを閉じたり、押し続けたりすることはできません。
 */
internal object BackPresses {
    suspend fun until(
        tries: Int,
        maxPresses: Int,
        keyboardUp: suspend () -> Boolean,
        press: suspend () -> Unit,
        left: suspend () -> Boolean,
    ): Boolean {
        var counted = 0
        var presses = 0
        while (counted < tries && presses < maxPresses) {
            val keyboard = keyboardUp()
            press()
            presses++
            if (left()) return true
            if (!keyboard) counted++
        }
        return false
    }
}
