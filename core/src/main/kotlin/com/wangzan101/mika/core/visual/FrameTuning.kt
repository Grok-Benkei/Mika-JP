package com.wangzan101.mika.core.visual

/** アクションの前後の 2 つのフレームを大まかに比較するために使用されるグリッドとしきい値 (アクションの効果を判断するための状況証拠)。 */
object FrameTuning {
    /** スクリーン グリッドの列と行の数。 */
    const val GRID_COLS = 24
    const val GRID_ROWS = 48
    /** 1 つのグリッドの明るさの差がこれを超えると、グリッドが変更されました。 */
    const val CELL_DELTA = 16
    /** では、目に見える変化とみなされるためには、画面全体の少なくともこの数のフレーム (約 1%) が変化する必要があります。 */
    const val FRAME_VISIBLE = 0.01
    /** ターゲット エリア内の少なくともこの数のグリッドの変更は、エリア内の目に見える変更とみなされます。 */
    const val REGION_VISIBLE = 0.15
    /** 画面全体のグリッドの変更が非常に多く、大きな領域の変更になります。 */
    const val FRAME_LARGE = 0.25
}
