package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.NioFdSys
import com.wangzan101.mika.core.workspace.WorkspaceFileGate

/** ゲート (パスによる近似。携帯電話では AndroidFdSys.gate です)。 */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
