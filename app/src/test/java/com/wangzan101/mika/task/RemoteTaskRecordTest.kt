package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTaskRecordTest {
    @Test fun terminalStatesRoundTrip() {
        val states = listOf(
            TaskState.Succeeded("answer"),
            TaskState.Succeeded("answer", verified = false, note = "unchecked", unconfirmed = listOf("a", "b"), reviewed = true),
            TaskState.Succeeded("answer", verified = false, reviewed = false),
            TaskState.Cancelled,
        ) + FailKind.entries.map { TaskState.Failed("why $it", it) }
        for (s in states) assertEquals(s, RemoteTaskRecord.decode(RemoteTaskRecord.encode(s)))
    }

    // ディスク上の単語は自分で決定したものであり、列挙名に従いません。保存形式が変更された場合は、ここで変更する必要があります。
    @Test fun storedFormatIsIndependentOfDomainNames() {
        assertEquals("""{"type":"failed","reason":"x","kind":"step_limit"}""",
            RemoteTaskRecord.encode(TaskState.Failed("x", FailKind.StepLimit)))
        assertEquals("""{"type":"cancelled"}""", RemoteTaskRecord.encode(TaskState.Cancelled))
    }

    // によって応答された証拠台帳をリモートで受信することはできません。
    @Test fun sourcesAreNotStored() {
        val s = TaskState.Succeeded("a", sources = listOf(com.wangzan101.mika.core.verify.AnswerSource(1, "screen", null, null, 1)))
        assertEquals(s.copy(sources = emptyList()), RemoteTaskRecord.decode(RemoteTaskRecord.encode(s)))
    }

    @Test fun legacyAndUnknownValuesDecodeSafely() {
        // 記述どおりにマージする前に、フィールド タイプを直接シリアル化します。カテゴリは列挙名です。
        assertEquals(TaskState.Failed("stopped", FailKind.Interrupted),
            RemoteTaskRecord.decode("""{"type":"failed","reason":"stopped","kind":"Interrupted"}"""))
        assertEquals(TaskState.Succeeded("a", verified = false, note = "n", unconfirmed = listOf("x")),
            RemoteTaskRecord.decode("""{"type":"done","answer":"a","verified":false,"note":"n","sources":[],"unconfirmed":["x"]}"""))
        // 更新バージョンで記述されたカテゴリと追加フィールド
        assertEquals(TaskState.Failed("r", FailKind.Environment),
            RemoteTaskRecord.decode("""{"type":"failed","reason":"r","kind":"something_new","extra":1}"""))
        for (bad in listOf("", "not json", "[]", "{}", """{"type":"paused"}""", """{"type":"done"}""", """{"type":"done","answer":3}"""))
            assertEquals(bad, RemoteTaskRecord.UNREADABLE, RemoteTaskRecord.decode(bad))
    }

    @Test fun steersRoundTripAndSkipBrokenEntries() {
        val steers = listOf(RemoteSteer("m1", "joined"), RemoteSteer("m2", "not_applied"))
        assertEquals(steers, RemoteTaskRecord.decodeSteers(RemoteTaskRecord.encodeSteers(steers)))
        assertEquals(listOf(RemoteSteer("m1", "joined")),
            RemoteTaskRecord.decodeSteers("""[{"messageId":"m1","state":"joined","x":1},{"messageId":"m2"},3]"""))
        assertEquals(emptyList<RemoteSteer>(), RemoteTaskRecord.decodeSteers("{"))
    }
}
