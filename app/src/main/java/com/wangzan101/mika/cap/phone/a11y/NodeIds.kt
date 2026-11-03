package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.core.cap.phone.PHONE_SNAPSHOT_PREFIX
import com.wangzan101.mika.core.uistate.ElementLocator
import com.wangzan101.mika.core.uistate.UiElement
import java.util.UUID

/** 携帯電話画面読み取りの SnapshotId: 携帯電話のプレフィックスとランダムな文字列 (番号を見れば携帯電話からのものであることがわかり、Web ページから分離できます)。 */
internal fun snapshotId(): String = PHONE_SNAPSHOT_PREFIX + UUID.randomUUID()

/** スナップショット内の要素: モデルに渡される [element]、要素の識別に使用される [locator]、および直接操作できる [node]。 */
internal class NodeEntry(val element: UiElement, val locator: ElementLocator, val node: AccessibilityNodeInfo)

internal class NodeSnapshot(val id: String, val entries: List<NodeEntry>) {
    val locators: List<ElementLocator> get() = entries.map { it.locator }
}

/**
 * 番号登録テーブル: 各スナップショットは再番号付けされ (@e1 から開始)、最新のスナップショットの番号のみが認識されます。
 * 最新のスナップショット番号を直接使用できます。以前のスナップショットは、元の要素の特性に従って再配置するためにのみ使用されます。
 */
internal class NodeIds(private val keep: Int = 4) {
    sealed interface Lookup {
        class Current(val entry: NodeEntry, val snapshot: NodeSnapshot) : Lookup
        class Old(val entry: NodeEntry, val snapshot: NodeSnapshot) : Lookup
        data object Unknown : Lookup
    }

    private val history = ArrayDeque<NodeSnapshot>()

    @Synchronized
    fun put(snapshot: NodeSnapshot) {
        history.addFirst(snapshot)
        while (history.size > keep) history.removeLast()
    }

    @Synchronized
    fun clear() { history.clear() }

    @Synchronized
    fun current(): NodeSnapshot? = history.firstOrNull()

    @Synchronized
    fun lookup(ref: String, snapshotId: String): Lookup {
        val index = ref.removePrefix("@e").toIntOrNull()?.minus(1) ?: return Lookup.Unknown
        val i = history.indexOfFirst { it.id == snapshotId }
        if (i < 0) return Lookup.Unknown
        val snap = history[i]
        val entry = snap.entries.getOrNull(index) ?: return Lookup.Unknown
        return if (i == 0) Lookup.Current(entry, snap) else Lookup.Old(entry, snap)
    }
}
