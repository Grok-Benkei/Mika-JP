package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalPolicyTest {
    @Test fun readsAndOrdinaryChangesDoNotAsk() {
        for (level in listOf(ActionRisk.Read, ActionRisk.Change)) {
            assertEquals("$level", Approval.Allow, ApprovalPolicy.decide(Risk(level)))
            assertEquals("$level payment", Approval.Ask, ApprovalPolicy.decide(Risk(level, payment = true)))
        }
    }

    @Test fun outwardActionsAskOnThePhoneAndAreDeniedElsewhere() {
        for (level in listOf(ActionRisk.External, ActionRisk.Irreversible, ActionRisk.Unknown)) {
            assertEquals("$level", Approval.Ask, ApprovalPolicy.decide(Risk(level)))
        }
    }

    @Test fun everyOriginAndAttendance() {
        for (origin in RunOrigin.entries) for (attendance in Attendance.entries) for (level in ActionRisk.entries) {
            val run = RunProvenance(origin, attendance)
            val outward = level == ActionRisk.External || level == ActionRisk.Irreversible || level == ActionRisk.Unknown
            val expected = if (origin == RunOrigin.Ui && attendance != Attendance.Unattended) Approval.Ask else Approval.Deny
            assertEquals("$origin $attendance $level", if (outward) expected else Approval.Allow, ApprovalPolicy.decide(Risk(level), run))
            assertEquals("$origin $attendance $level payment", expected, ApprovalPolicy.decide(Risk(level, payment = true), run))
            val request = ConfirmationRequest(IrrevAction.other, "", "rm /sdcard/x", null, "root")
            assertEquals("$origin $attendance $level root", expected, ApprovalPolicy.decide(Risk(level, ask = request), run))
        }
    }
}
