package com.wangzan101.mika.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/** キーボード領域に登録されている入力領域: それがどこにあるか、また現在フォーカスがあるかどうか。 */
private class KeyboardKeeper {
    val bounds = HashMap<Any, Rect>()
    val focused = HashSet<Any>()
    var area: LayoutCoordinates? = null
    fun contains(at: Offset) = bounds.values.any { it.contains(at) }
}

private val LocalKeyboardKeeper = staticCompositionLocalOf<KeyboardKeeper?> { null }

/**
 * 入力領域の外側をクリックまたはドラッグしてフォーカスをクリアし、キーボードを非表示にします。ジェスチャ自体はブロックされず、外側のボタンは通常どおりクリックでき、リストをスライドすることもできます。
 * は、登録された入力エリア ([keepsKeyboard]) にフォーカスがある場合にのみ動作します。文字選択などの操作には影響しません。
 * ダイアログ ボックスは別のウィンドウです。入力ボックスがある場合は、別のレイヤーでラップする必要があります。
 */
@Composable
fun KeyboardDismissArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val keeper = remember { KeyboardKeeper() }
    val focus = LocalFocusManager.current
    Box(
        modifier.onGloballyPositioned { keeper.area = it }.pointerInput(keeper) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (keeper.focused.isEmpty()) return@awaitEachGesture
                val at = keeper.area?.takeIf { it.isAttached }?.localToRoot(down.position) ?: return@awaitEachGesture
                if (keeper.contains(at)) return@awaitEachGesture
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        focus.clearFocus()
                        break
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalKeyboardKeeper provides keeper, content = content)
    }
}

/** 入力領域 (入力ボックス、またはその横にボタンがある入力フィールド全体) をマークします。内側をクリックしてキーボードを閉じます。 */
@Composable
fun Modifier.keepsKeyboard(): Modifier {
    val keeper = LocalKeyboardKeeper.current ?: return this
    val key = remember { Any() }
    DisposableEffect(keeper) {
        onDispose { keeper.bounds.remove(key); keeper.focused.remove(key) }
    }
    return this
        .onGloballyPositioned { keeper.bounds[key] = it.boundsInRoot() }
        .onFocusChanged { if (it.hasFocus) keeper.focused.add(key) else keeper.focused.remove(key) }
}
