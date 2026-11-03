package com.wangzan101.mika.capsule

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/** カード上の 1 秒あたりに表示できる小さな画像の最大数。 */
const val PREVIEW_FPS = 5

/**
 * カードの小さな画像にプッシュされるフレーム: [intervalMs] ごとに最大 1 フレーム、中央の最新のもののみが保持されます。前のもの ([same]) と同じものはプッシュされません。
 * はカードを広げた場合のみカードを閉じ、折りたたんだ場合はキャンセルし、回収されない場合はカードを押しません。
 */
fun <T : Any> Flow<T>.previewFrames(intervalMs: Long = 1_000L / PREVIEW_FPS, same: (T, T) -> Boolean = { a, b -> a === b }): Flow<T> = flow {
    var last: T? = null
    conflate().collect { f ->
        val prev = last
        if (prev != null && (prev === f || same(prev, f))) return@collect
        last = f
        emit(f)
        delay(intervalMs)
    }
}

/**
 * 新しいフレームが前のフレームと同じ (同じ画像、同じサイズ、同じピクセル) の場合、そのフレームはプッシュされません。Web ページ画面には毎回新しいコピーが与えられます。
 * 仮想画面上の古いフレームはリサイクルされるため、最初に [Bitmap.isRecycled] を確認し、リサイクルされた画像とピクセルを比較しないでください。
 */
fun sameFrame(a: Bitmap, b: Bitmap): Boolean =
    a === b || !a.isRecycled && !b.isRecycled && a.width == b.width && a.height == b.height && a.sameAs(b)
