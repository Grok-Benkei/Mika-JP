package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** アプリのページ。ダイアログはルート ページであり、他のすべてはそこから (または設定から​​) 入力され、戻るときは元のページに戻ります。 */
enum class Route { Chat, Settings, Memory, Plans, Browser, Run, Source, PhoneSetup, Models, Usage, SpeechModel, Welcome, ModelSetup, About, License, Screens }

/**
 * ページ スタック: 一番下は常に会話ページです。 [push] は次のページに進み、[back] は前のページに戻ります (すでにダイアログ ページにある場合は false を返し、終了はシステムに任せます)。
 * リターン キーとページ上のリターン ボタンは同じ [back] を使用するため、この 2 つは常に一貫しています。
 */
@Stable
class NavStack(initial: List<Route> = listOf(Route.Chat), fromDrawer: Boolean = false) {
    var stack: List<Route> by mutableStateOf(normalize(initial))
        private set

    /** この一連のページはサイドバーから入ります。会話ページに戻るときは、開いているサイドバーに戻る必要があります。 */
    private var fromDrawer = fromDrawer && stack.size > 1

    /** サイドバーから入ったばかりのページが会話ページに戻ります。サイドバーを再度開く必要があります。開いたら、[drawerReopened] を呼び出します。 */
    var reopenDrawer: Boolean by mutableStateOf(false)
        private set

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** 次のページに進みます。すでにこのページにいる場合は、スタックを再度プッシュしないでください。 */
    fun push(route: Route) {
        if (route == Route.Chat) return reset()
        if (current != route) stack = stack + route
    }

    /** サイドバーからページに入る: 会話ページまで戻ると、サイドバーが開きます。 */
    fun pushFromDrawer(route: Route) {
        fromDrawer = true
        push(route)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        if (!canGoBack && fromDrawer) {
            fromDrawer = false
            reopenDrawer = true
        }
        return true
    }

    fun drawerReopened() {
        reopenDrawer = false
    }

    /** では、ダイアログ ページと [route] (初めて開始するすべてのステップで使用されます) の 2 つのレイヤーのみが残ります。 */
    fun show(route: Route) {
        fromDrawer = false
        stack = normalize(listOf(route))
    }

    /** 会話ページに戻ります (会話を開始する場合)。 */
    fun reset() {
        fromDrawer = false
        reopenDrawer = false
        stack = listOf(Route.Chat)
    }

    companion object {
        private const val DRAWER_MARK = "fromDrawer"

        private fun normalize(list: List<Route>): List<Route> =
            listOf(Route.Chat) + list.filter { it != Route.Chat }

        val Saver: Saver<NavStack, ArrayList<String>> = Saver(
            save = { ArrayList(it.stack.map(Route::name) + listOfNotNull(DRAWER_MARK.takeIf { _ -> it.fromDrawer })) },
            restore = { names ->
                NavStack(names.mapNotNull { n -> Route.entries.firstOrNull { it.name == n } }, fromDrawer = DRAWER_MARK in names)
            },
        )
    }
}

@Composable
fun rememberNavStack(): NavStack = rememberSaveable(saver = NavStack.Saver) { NavStack() }
