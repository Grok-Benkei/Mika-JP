package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun persistsAcrossInstances() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        a.append(id, Turn("订票", "验证码\n\"过不去\"", "failed"))
        val b = ConversationStore(dir)
        assertEquals(listOf(Turn("查天气", "晴", "succeeded"), Turn("订票", "验证码\n\"过不去\"", "failed")), b.turns(id))
        assertEquals(emptyList<Turn>(), b.turns(b.newConversation()))
        assertNotEquals(id, b.newConversation())
    }

    /** バックグラウンドで実行されたままになっているページ (サイト) とその後のシャットダウンの理由は、そのラウンドとともに保存されます。これら 2 つのフィールドは、ページが残らないラウンドでは書き込まれません。 */
    @Test fun persistsKeptPage() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("播放课程,挂着别关", "开始播放了", "succeeded", runId = "r1", keptPage = "course.example.com"))
        a.append(id, Turn("谢谢", "不客气", "succeeded", runId = "r2"))
        assertEquals(listOf("course.example.com", null), ConversationStore(dir).turns(id).map { it.keptPage })
        a.updateTurn(id, "r1") { it.copy(keptPageClosed = "expired") }
        assertEquals("expired", ConversationStore(dir).turns(id).first().keptPageClosed)
        assertFalse("keptPage" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** このラウンドで使用された能力グループは保存されます (次のラウンドはそれに応じて事前拡張されます)。使用されていない場合、このフィールドは書き込まれません。 */
    @Test fun persistsUsedGroups() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("打开设置", "好了", "succeeded", groups = listOf("phone", "web")))
        a.append(id, Turn("谢谢", "不客气", "succeeded"))
        assertEquals(listOf(listOf("phone", "web"), emptyList()), ConversationStore(dir).turns(id).map { it.groups })
        assertFalse("groups" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** 割り込みにより中断された応答は、割り込みと 1 対 1 対応で保存されます。このフィールドは中断されていないラウンドでは書き込まれず、古いファイルは読み取られたときに空になります。 */
    @Test fun persistsInterruptedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        val read = ConversationStore(dir).turns(id)
        assertEquals(listOf(listOf("今天晴", ""), emptyList()), read.map { it.interruptedReplies })
        assertEquals(listOf("改成查明天", "顺便看看后天"), read.first().steers)
        assertFalse("interruptedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** 中断前に終了した応答も 1 対 1 対応で保存されます。何もない場合、このフィールドは書き込まれません。 */
    @Test fun persistsFinishedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天"), finishedReplies = listOf("今天晴,最高 25 度。")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        assertEquals(listOf(listOf("今天晴,最高 25 度。"), emptyList()), ConversationStore(dir).turns(id).map { it.finishedReplies })
        assertFalse("finishedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** ダイアログの削除: ダイアログ ファイル、タイトル、脇に移動された破損したファイル、および一時ファイルがすべて削除されます。これらはリストには表示されなくなり、他のダイアログは変更されません。削除後、変更内容が掲載されます。 */
    @Test fun deleteRemovesTheConversationAndItsSidecars() = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        val other = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.setTitle(id, "天气")
        store.setPinned(id, true, at = 5L)
        java.io.File(dir, "$id.json.corrupt-1").writeText("x")
        java.io.File(dir, "$id.json.tmp").writeText("x")
        store.append(other, Turn("订票", "好了", "succeeded"))
        val changes = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.changes.collect { changes += it } }
        val gone = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.deletions.collect { deletions += it } }
        store.delete(id)
        kotlinx.coroutines.yield()
        job.cancel()
        gone.cancel()
        assertEquals(listOf(id), changes)
        assertEquals(listOf(id), deletions)
        assertEquals(listOf("$other.json"), dir.list()!!.sorted())
        assertEquals(listOf(other), store.list().map { it.id })
        assertEquals(emptyList<Turn>(), store.turns(id))
        assertEquals(null, store.title(id))
        assertEquals(null, store.pinnedAt(id))
    }

    /** ユーザーが変更した名前が最終決定権を持ちます。タイトルはバックグラウンドで遅れても上書きされません。ユーザーは作成されたタイトルを変更できます。空のタイトルは変更されません。 ピン */
    @Test fun renameByUserWinsOverAutoTitle() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查一下明天上海的天气", "晴", "succeeded"))
        assertTrue(store.rename(id, "  天气  "))
        assertFalse(store.setTitle(id, "上海天气"))
        assertEquals("天气", ConversationStore(dir).title(id))

        val other = store.newConversation()
        store.append(other, Turn("订票", "好了", "succeeded"))
        assertTrue(store.setTitle(other, "订高铁票"))
        assertTrue(store.rename(other, "回家的票"))
        assertFalse(store.rename(other, "   "))
        assertEquals("回家的票", store.title(other))
        assertEquals(setOf("天气", "回家的票"), store.list().map { it.title }.toSet())

        store.delete(other)
        assertFalse(store.rename(other, "再改"))
    }

    /** は `.pin` (ピンが固定された時刻) に記録され、再起動後もそのまま残ります。ピンの時間はリストに含まれ、会話の順序は変更されません。ピンを解除すると消えます。 */
    @Test fun pinPersistsAndShowsInTheList() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("一", "好", "succeeded"))
        store.append("b", Turn("二", "好", "succeeded"))
        assertTrue(store.setPinned("a", true, at = 123L))
        val again = ConversationStore(dir)
        assertEquals(123L, again.pinnedAt("a"))
        assertEquals(null, again.pinnedAt("b"))
        assertEquals(mapOf("a" to 123L, "b" to null), again.list().associate { it.id to it.pinnedAt })
        assertTrue(again.setPinned("a", false))
        assertEquals(null, again.pinnedAt("a"))
        assertFalse(java.io.File(dir, "a.pin").exists())
        // 削除された会話が上部に固定されなくなりました
        again.delete("b")
        assertFalse(again.setPinned("b", true))
    }

    /** 削除後の書き換え（バックグラウンドで作成されたタイトル、エンディングの後に追加されたエンディング）では、ダイアログは書き戻されません。再度保存すると、この番号を再度使用することになります。 */
    @Test fun lateWritesDoNotReviveADeletedConversation() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r1"))
        store.delete(id)
        assertFalse(store.setTitle(id, "天气"))
        store.updateTurn(id, "r1") { it.copy(outcome = "补上的") }
        assertTrue(dir.list()!!.isEmpty())

        store.append(id, Turn("再来", "好", "succeeded"))
        assertTrue(store.setTitle(id, "再来"))
        assertEquals(listOf("再来"), store.list().map { it.title })
    }

    @Test fun corruptFileReadsAsEmpty() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        assertEquals(emptyList<Turn>(), s.turns("x"))
        s.append("x", Turn("a", "b", "cancelled"))
        assertEquals(1, s.turns("x").size)
    }

    @Test fun corruptFileIsMovedAsideNotOverwritten() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        s.append("x", Turn("a", "b", "cancelled"))
        val aside = dir.listFiles().orEmpty().filter { it.name.startsWith("x.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals("{not json", aside.single().readText())
        assertEquals(listOf(Turn("a", "b", "cancelled")), s.turns("x"))
        assertEquals(listOf("x"), s.list().map { it.id })
    }

    @Test fun listsConversationsNewestFirst() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        val a = s.newConversation()
        val b = s.newConversation()
        s.append(a, Turn("查天气", "晴", "succeeded"))
        s.append(b, Turn("订票", "好了", "succeeded"))
        s.append(b, Turn("改签", "好了", "succeeded"))
        java.io.File(dir, "$a.json").setLastModified(1_000)
        java.io.File(dir, "$b.json").setLastModified(2_000)
        java.io.File(dir, "broken.json").writeText("{")
        assertEquals(listOf(b to "订票", a to "查天气"), s.list().map { it.id to it.title })
    }

    @Test fun unverifiedTurnKeepsItsNote() {
        val dir = tmp.newFolder()
        val id = ConversationStore(dir).let { s -> s.newConversation().also { s.append(it, Turn("查余额", "余额 10 元", "unverified", note = "防截屏")) } }
        assertEquals(listOf(Turn("查余额", "余额 10 元", "unverified", note = "防截屏")), ConversationStore(dir).turns(id))
    }

    @Test fun turnKeepsItsRunId() {
        val dir = tmp.newFolder()
        val s = ConversationStore(dir)
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded", runId = "20261023-120000-abc123"))
        s.append(id, Turn("旧的", "好", "succeeded"))
        assertEquals(listOf("20261023-120000-abc123", null), ConversationStore(dir).turns(id).map { it.runId })
    }

    @Test fun memoryNotesStoredWithoutContentAndStripped() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val note = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.added, "m1", "t1", null)
        val other = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.pending, "m2", "t2", "m2")
        store.append("c1", Turn("点外卖", "ok", "succeeded", runId = "r1"))
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note, other), usedMemory = listOf("m1", "m3")) }
        val raw = java.io.File(dir, "c1.json").readText()
        assertTrue(raw.contains("\"memoryId\":\"m1\""))
        assertFalse(raw.contains("text"))
        assertEquals(listOf(note, other), ConversationStore(dir).turns("c1").single().memoryNotes)
        store.stripMemory(setOf("m1"))
        val t = ConversationStore(dir).turns("c1").single()
        assertEquals(listOf(other), t.memoryNotes)
        assertEquals(listOf("m3"), t.usedMemory)
        store.stripMemory(null)
        assertTrue(ConversationStore(dir).turns("c1").single().memoryNotes.isEmpty())
    }

    @Test fun filesKeptPerTurn() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        val f = SharedFile("图.png", 1234, "Download/Mika/图.png", "content://media/external/downloads/7")
        a.append(id, Turn("画图", "好了", "succeeded"), listOf(f))
        a.append(id, Turn("再来", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(listOf(listOf(f), emptyList()), b.records(id).map { it.second })
        assertEquals(2, b.turns(id).size)
    }

    // タスク後にメモリ プロンプトを追加したりメモリを削除したりする場合、このラウンドのファイル カードが失われることはありません。
    @Test fun filesSurviveNoteUpdatesAndStrip() {
        val dir = tmp.newFolder("d")
        val store = ConversationStore(dir)
        val f = SharedFile("表.csv", 10, "Download/Mika/表.csv", "content://media/external/downloads/8")
        store.append("c1", Turn("导出", "好了", "succeeded", runId = "r1"), listOf(f))
        val note = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.added, "m1", "t1", null)
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note)) }
        store.stripMemory(null)
        val r = ConversationStore(dir).records("c1").single()
        assertEquals(listOf(f), r.second)
        assertTrue(r.first.memoryNotes.isEmpty())
    }

    // は、書き込まれた各ダイアログの番号を送信します。1 ラウンドを保存し、1 ラウンドを変更し、メモリープロンプトを削除します。
    @Test fun announcesChangedConversations() = kotlinx.coroutines.test.runTest {
        val s = ConversationStore(tmp.newFolder("c"))
        val seen = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.changes.collect { seen += it } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.deletions.collect { deleted += it } }
        s.append("a", Turn("查天气", "晴", "succeeded", runId = "r1", usedMemory = listOf("m1")))
        s.append("b", Turn("订票", "好了", "succeeded"))
        s.updateTurn("a", "r1") { it.copy(outcome = "晴转多云") }
        s.updateTurn("a", "missing") { it }
        s.stripMemory(setOf("m1"))
        assertEquals(listOf("a", "b", "a", "a"), seen)
        // 会話が削除された場合にのみ削除を送信します
        assertEquals(emptyList<String>(), deleted)
    }

    /** 会話はどのページから始まりますか?その隣の .page ファイルにそれを書き留めると、リストに含まれます。古い会話は、それなしでも通常どおり読み上げられます。会話を削除して一緒に削除します。 */
    @Test fun pageOriginPersistsAndIsOptional() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val plain = a.newConversation()
        a.append(plain, Turn("查天气", "晴", "succeeded"))
        val paged = a.newConversation()
        assertTrue(a.setPageOrigin(paged, PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索")))
        a.append(paged, Turn("挑一个", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索"), b.pageOrigin(paged))
        assertEquals(null, b.pageOrigin(plain))
        val listed = b.list().associateBy { it.id }
        assertEquals("shop.example.com", listed.getValue(paged).page?.host)
        assertEquals(null, listed.getValue(plain).page)
        // 会話ファイルの形式は変更されません。追加のフィールドはありません。
        assertFalse(java.io.File(dir, "$paged.json").readText().contains("shop.example.com"))
        // 壊れた .page は存在しないものとして扱われます。
        java.io.File(dir, "$plain.page").writeText("{not json")
        assertEquals(null, b.pageOrigin(plain))
        b.delete(paged)
        assertFalse(java.io.File(dir, "$paged.page").exists())
        assertFalse(b.setPageOrigin(paged, PageOrigin("https://a.example/", "a.example")))
    }

    /**
     * 「ユーザーが現在どの会話に参加しているか」も記録されます。プロセスが再起動された後、カプセルはファイル時間に基づいて最新の会話を推測するのではなく、それに応じて会話を継続します。
     * 新しく開かれた、まだ話されていない会話は、[CurrentChat.Fresh] として記録されます。削除すると、一緒にクリアされます。このファイルは会話リストには追加されません。
     */
    @Test fun remembersTheConversationOnScreen() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        assertEquals(null, a.currentChat())

        a.setCurrent(CurrentChat.Fresh)
        assertEquals(CurrentChat.Fresh, a.currentChat())
        // 新しく開かれた会話にはまだ会話ファイルがありません。再起動後も読み込むことができます。
        assertEquals(CurrentChat.Fresh, ConversationStore(dir).currentChat())
        assertEquals(listOf(id), ConversationStore(dir).list().map { it.id })

        a.setCurrent(CurrentChat.Open(id))
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).currentChat())
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).current.value)
        // は現在のファイルの隣に記録され、ダイアログ ファイルやリストには移動しません。
        assertEquals(setOf("$id.json", "current"), dir.list()!!.toSet())
        assertEquals(listOf(id), a.list().map { it.id })

        // この会話を削除します。この会話を指すレコードは一緒にクリアされ、カプセルは最新の会話に戻ります。
        a.delete(id)
        assertEquals(null, ConversationStore(dir).currentChat())
    }

    /** ブランチは選択したラウンド (両端を含む) にコピーされ、後続のブランチはコピーされません。ソースダイアログは変更されません。 */
    @Test fun branchCopiesThroughTheChosenTurn() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        val target = store.newConversation()
        assertTrue(store.branch(source, target, throughTurn = 1))
        assertEquals(
            listOf(Turn("一", "甲", "succeeded"), Turn("二", "乙", "succeeded")),
            ConversationStore(dir).turns(target),
        )
        // ソースダイアログはまだ 3 ラウンドです
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
    }

    /** ブランチは、タイトル、Web ページのソース、およびブランチのソースを取り除きますが、トップは取り除きません。 */
    @Test fun branchCopiesTitlePageAndOriginButNotPin() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("查天气", "晴", "succeeded"))
        store.setTitle(source, "原对话")
        store.setPinned(source, true, at = 1L)
        val page = PageOrigin("https://a.example/", "a.example", "示例")
        store.setPageOrigin(source, page)
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = true, at = 123L)
        assertTrue(store.branch(source, target, 0, title = "原对话 · 分支", origin = origin))
        val again = ConversationStore(dir)
        assertEquals("原对话 · 分支", again.title(target))
        assertEquals(page, again.pageOrigin(target))
        assertEquals(origin, again.branchOrigin(target))
        assertEquals(null, again.pinnedAt(target))
    }

    /** ソースが存在せず、ソース ファイルが破損しており、ラウンドは範囲外で、ターゲットにはすでにダイアログ ファイルがあります。すべて false を返し、何も書き込みません。 */
    @Test fun branchRejectsBadInput() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        val target = store.newConversation()

        assertFalse(store.branch("missing", target, 0))
        assertFalse(java.io.File(dir, "$target.json").exists())

        java.io.File(dir, "broken.json").writeText("{not json")
        assertFalse(store.branch("broken", target, 0))

        assertFalse(store.branch(source, target, throughTurn = 2))
        assertFalse(store.branch(source, target, throughTurn = -1))
        assertFalse(java.io.File(dir, "$target.json").exists())
        assertFalse(java.io.File(dir, "$target.branch").exists())

        store.append(target, Turn("已有", "好", "succeeded"))
        assertFalse(store.branch(source, target, 0))
        assertEquals(listOf("已有"), store.turns(target).map { it.userText })
    }

    /** ブランチは再度分岐することができ、ラウンド数は上位レベルのブランチの内容に従って計算され、ソースは前の会話を指します。 */
    @Test fun branchOfBranchWorks() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        val middle = store.newConversation()
        assertTrue(store.branch(source, middle, 2, origin = BranchOrigin(source, 2, afterTurn = true, at = 1L)))
        val leaf = store.newConversation()
        assertTrue(store.branch(middle, leaf, 0, origin = BranchOrigin(middle, 0, afterTurn = true, at = 2L)))
        assertEquals(listOf("一"), store.turns(leaf).map { it.userText })
        assertEquals(middle, store.branchOrigin(leaf)?.parentId)
        assertEquals(source, store.branchOrigin(middle)?.parentId)
    }

    /** 会話を削除すると、ブランチソースも削除されます。 */
    @Test fun deleteRemovesBranchSidecar() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        assertTrue(store.branch(source, target, 0, origin = BranchOrigin(source, 0, afterTurn = true, at = 1L)))
        val origin = java.io.File(dir, "$target.branch")
        assertTrue(origin.isFile)
        store.delete(target)
        assertFalse(origin.exists())
        assertEquals(null, store.branchOrigin(target))
    }

    /** ブランチ ソースを含むリスト。通常の会話、.branch のない古い会話は null です。 */
    @Test fun listExposesBranchOrigin() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = true, at = 9L)
        assertTrue(store.branch(source, target, 0, origin = origin))
        val listed = ConversationStore(dir).list().associateBy { it.id }
        assertEquals(origin, listed.getValue(target).branchOf)
        assertEquals(null, listed.getValue(source).branchOf)
    }

    /** はユーザー メッセージから再度尋ねます: このラウンドの前のラウンドのみをコピーします。ソースは after=false として記録されます。 */
    @Test fun branchBeforeCopiesOnlyEarlierTurns() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        val page = PageOrigin("https://a.example/", "a.example", "示例")
        store.setPageOrigin(source, page)
        val target = store.newConversation()
        val origin = BranchOrigin(source, 1, afterTurn = false, at = 2L)

        assertTrue(store.branchBefore(source, target, beforeTurn = 1, title = "原对话 · 分支", origin = origin))

        assertEquals(listOf("一"), ConversationStore(dir).turns(target).map { it.userText })
        assertEquals(origin, store.branchOrigin(target))
        assertEquals(page, store.pageOrigin(target))
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
    }

    /** 最初のラウンドの前にラウンドはありません。ブランチは空であり、サイドカラムに入りません。タイトルとソースが書き込まれており、最初の文は保存されて後で使用されます。 */
    @Test fun branchBeforeTheFirstTurnMakesAnEmptyBranch() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = false, at = 3L)

        assertTrue(store.branchBefore(source, target, beforeTurn = 0, title = "一 · 分支", origin = origin))

        assertEquals(emptyList<Turn>(), store.turns(target))
        assertTrue(java.io.File(dir, "$target.json").isFile)
        assertTrue(store.list().none { it.id == target })
        assertEquals("一 · 分支", store.title(target))
        assertEquals(origin, store.branchOrigin(target))
        store.append(target, Turn("换个问法", "另一个答", "succeeded"))
        assertEquals(listOf("换个问法"), store.turns(target).map { it.userText })
        assertTrue(store.list().any { it.id == target })
    }

    /** リクエストは不正な入力も拒否します。ソースが存在せず、ラウンドが範囲外で、ターゲットにすでにファイルがあります。 */
    @Test fun branchBeforeRejectsBadInput() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()

        assertFalse(store.branchBefore("missing", target, 0))
        assertFalse(store.branchBefore(source, target, 1))
        assertFalse(store.branchBefore(source, target, -1))
        assertFalse(java.io.File(dir, "$target.json").exists())

        store.append(target, Turn("已有", "好", "succeeded"))
        assertFalse(store.branchBefore(source, target, 0))
        assertEquals(listOf("已有"), store.turns(target).map { it.userText })
    }
}
