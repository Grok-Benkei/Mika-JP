package com.wangzan101.mika.core.run

/** この実行を開始した人。単一アクションの呼び出し元から完全に分離されます。 */
enum class RunOrigin { Ui, Channel, Trigger, Mcp, Child }

/** ユーザーは、この実行中に質問に対処したり、タイムリーに確認したりできますか。 */
enum class Attendance { Present, Reachable, Unattended }

data class RunProvenance(
    val origin: RunOrigin = RunOrigin.Ui,
    val attendance: Attendance = Attendance.Present,
)
