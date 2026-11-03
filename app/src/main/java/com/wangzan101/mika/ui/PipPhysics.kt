package com.wangzan101.mika.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * ピクチャー・イン・ピクチャーの小ウィンドウのジェスチャー物理学、単位はピクセルと秒で、テスト用のインターフェースとは関係ありません。
 * 手を放すと、速度に応じて着地点を投影し（リストの慣性スクロールのように、停止するまで減速します）、最も近いコーナーを見つけます。
 * 戻り用臨界減衰スプリング（減衰比1、応答0.4秒）：リバウンドがなく、現在の速度から歩行を継続でき、いつでも中断できます。
 */
object PipPhysics {
    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    /**
     * 小ウィンドウを駐車できる領域: [width]×[height] は画面全体、[pipWidth]×[pipHeight] は小ウィンドウの現在のサイズです。
     * [top]/[bottom] は遠ざけるトップバーと入力ボックスの高さ、[margin] は端からの距離です。
     */
    data class Bounds(
        val width: Float,
        val height: Float,
        val pipWidth: Float,
        val pipHeight: Float,
        val margin: Float,
        val top: Float,
        val bottom: Float,
    ) {
        /** 十分なスペースがない場合 (キーボードがポップアップする場合など、ウィンドウが狭すぎるか短すぎる場合)、トップバーの下にドリルで穴を開けるよりも、左上に固定する方が良いでしょう。 */
        fun position(c: Corner): Pair<Float, Float> {
            val left = margin
            val right = maxOf(left, width - pipWidth - margin)
            val upper = top + margin
            val lower = maxOf(upper, height - pipHeight - bottom - margin)
            return when (c) {
                Corner.TopStart -> left to upper
                Corner.TopEnd -> right to upper
                Corner.BottomStart -> left to lower
                Corner.BottomEnd -> right to lower
            }
        }
    }

    private const val DECELERATION = 0.998f

    /** が [velocity] (ピクセル/秒) でスローされた後、1 ミリ秒あたり 0.998 回まで減速して、最終的にどこまでスライドできるかを決定します。 */
    fun project(velocity: Float): Float = (velocity / 1000f) * DECELERATION / (1f - DECELERATION)

    /** 速度 ([vx]、[vy]) で ([x]、[y]) から手を放した後にスナップするはずのコーナー。 */
    fun snapCorner(x: Float, y: Float, vx: Float, vy: Float, bounds: Bounds): Corner {
        val px = x + project(vx)
        val py = y + project(vy)
        return Corner.entries.minBy { c ->
            val (cx, cy) = bounds.position(c)
            hypot(cx - px, cy - py)
        }
    }

    /** 一次元ばねの状態: 位置と速度。 */
    data class Motion(val position: Float, val velocity: Float)

    class Spring(response: Float = 0.4f, dampingRatio: Float = 1f) {
        private val stiffness = (2 * PI / response).pow(2).toFloat()
        private val damping = (4 * PI * dampingRatio / response).toFloat()

        /** [dt] 秒転送します (半暗黙的オイラー。最長の単一フレームは 32 ミリ秒として計算され、フレームがドロップされても発散しません)。 */
        fun step(m: Motion, target: Float, dt: Float): Motion {
            val t = dt.coerceIn(0f, MAX_DT)
            val v = m.velocity + (-stiffness * (m.position - target) - damping * m.velocity) * t
            return Motion(m.position + v * t, v)
        }

        fun settled(m: Motion, target: Float) = abs(m.position - target) < REST_DISTANCE && abs(m.velocity) < REST_VELOCITY
    }

    private const val MAX_DT = 0.032f
    private const val REST_DISTANCE = 0.5f
    private const val REST_VELOCITY = 5f
}
