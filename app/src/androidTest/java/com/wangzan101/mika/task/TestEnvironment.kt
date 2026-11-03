package com.wangzan101.mika.task

import androidx.test.platform.app.InstrumentationRegistry

/** 実機テストの場合: テスト対象のアプリ プロセス内のタスク環境 (モデル ソースの変更、フックの評価、画面の表示)。 */
internal val testTasks: TaskEnvironment
    get() = TaskEnvironment.of(InstrumentationRegistry.getInstrumentation().targetContext)
