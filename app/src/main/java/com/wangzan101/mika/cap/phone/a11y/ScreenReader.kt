package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.cap.phone.WheelMath
import com.wangzan101.mika.core.uistate.ElementLocator
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import com.wangzan101.mika.core.uistate.UiTreeNode
import com.wangzan101.mika.core.uistate.UiWindowTree
import com.wangzan101.mika.core.uistate.normalize

/**
 * 画面を1回読み込んだ結果。 [hash] は、テキスト、タイプ、位置、チェック、および
 * 入力フォーカスとカーソル/選択は、インターフェイスが変更されたかどうかを判断するために使用されます。入力ボックスをクリックしてフォーカスを変更した場合のみ、変更とみなされます。
 */
internal class ScreenRead(
    val entries: List<NodeEntry>,
    val hash: Long,
    val texts: List<String>,
    val passwordField: Boolean,
    val hasAppWindow: Boolean,
    val topPackage: String?,
    val windows: List<UiWindowTree> = emptyList(),
    /** にはアプリケーション ウィンドウがあります (ルート ノードが読み取り可能かどうかに関係ありません)。 */
    val anyAppWindow: Boolean = hasAppWindow,
)

/** は、特定の画面のウィンドウから要素リスト、インターフェイス フィンガープリント、診断テキストを読み取ります。 */
internal object ScreenReader {
    private const val MIN_SIZE = 5
    private const val MAX_DEPTH = 80
    private const val AGGREGATE_MAX = 60

    // 入力メソッドとアクセシビリティ フローティング レイヤーは、操作されるアプリに属しません
    private val SKIPPED_TYPES = setOf(
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
    )

    /** ウィンドウは上から下に配置され、インターフェイス コンテンツに属します。 */
    fun contentWindows(windows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> =
        windows.filter { it.type !in SKIPPED_TYPES }.sortedByDescending { it.layer }

    // 最上位のアプリケーション ウィンドウは、読み取れないコンテンツ (ポップアップ メニュー、入力ボックスの単語選択フローティング ウィンドウなど) を含むフローティング レイヤーである可能性があります。それらはスキップしてください。
    fun topPackage(windows: List<AccessibilityWindowInfo>): String? =
        contentWindows(windows).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.packageName?.toString() }

    fun read(windows: List<AccessibilityWindowInfo>, width: Int, height: Int): ScreenRead {
        val content = contentWindows(windows)
        val entries = mutableListOf<NodeEntry>()
        val texts = mutableListOf<String>()
        val seen = HashSet<String>()
        var hash = FNV_OFFSET
        var password = false
        var lastLabel: String? = null
        val rect = Rect()

        fun visit(n: AccessibilityNodeInfo, depth: Int, inList: Boolean): List<UiTreeNode> {
            if (depth > MAX_DEPTH) return emptyList()
            val visible = n.isVisibleToUser
            var ref: String? = null
            var clippedBounds = listOf(0, 0, 0, 0)
            if (visible) {
                n.getBoundsInScreen(rect)
                hash = mix(hash, n.className, n.text, n.contentDescription, rect.flattenToString(),
                    states(n))
                if (n.isPassword) password = true
                val own = if (n.isPassword) n.hintText?.toString()?.trim()?.ifEmpty { null } else ownLabel(n)
                own?.let(texts::add)
                clip(rect, width, height)
                clippedBounds = listOf(normalize(rect.left, width), normalize(rect.top, height),
                    normalize(rect.right, width), normalize(rect.bottom, height))
                val usable = rect.width() >= MIN_SIZE && rect.height() >= MIN_SIZE
                val interactive = n.isClickable || n.isEditable || n.isScrollable
                if (usable && (own != null || interactive)) {
                    val label = labelOf(n, own)
                    val role = roleOf(n)
                    if (seen.add("$role|$label|${rect.flattenToString()}")) {
                        val id = "@e${entries.size + 1}"
                        ref = id
                        val rid = n.viewIdResourceName
                        val element = UiElement(
                            ref = id, role = role, name = label,
                            centerX = rect.centerX(), centerY = rect.centerY(),
                            bounds = listOf(
                                normalize(rect.left, width), normalize(rect.top, height),
                                normalize(rect.right, width), normalize(rect.bottom, height),
                            ),
                            // 入力ボックスには常にリソース ID が含まれます。Enter キーを押す前に、そのリソース ID に基づいて検索ボックスを認識する必要があります (多くの場合、名前は単に推奨される単語です)。
                            resourceId = if (label.isEmpty() || n.isEditable) readableResourceId(rid) else null,
                            clickable = n.isClickable, editable = n.isEditable, scrollable = n.isScrollable,
                            hint = own != null && showingHint(n),
                            focused = n.isFocused,
                            secret = n.isPassword,
                            placeholder = if (n.isEditable) n.hintText?.toString()?.trim()?.ifEmpty { null } else null,
                            // Android の検索ボックス コントロール (SearchView の入力ボックス) はクラス名で識別されます
                            searchField = n.isEditable && n.className?.toString()?.contains("Search", ignoreCase = true) == true,
                            states = uiStates(n),
                        )
                        val locator = ElementLocator(role, label, rid, rect.centerX(), rect.centerY(), lastLabel, element.hint, inList)
                        entries += NodeEntry(element, locator, n)
                        if (label.isNotEmpty()) lastLabel = label
                    }
                }
            }
            val childrenInList = inList || isList(n)
            val children = buildList {
                for (i in 0 until n.childCount) n.getChild(i)?.let { addAll(visit(it, depth + 1, childrenInList)) }
            }
            if (!visible) return children
            return listOf(UiTreeNode(
                role = roleOf(n), bounds = clippedBounds, ref = ref,
                text = n.text?.toString()?.takeUnless { n.isPassword || it.isBlank() },
                description = n.contentDescription?.toString()?.takeUnless { n.isPassword || it.isBlank() },
                hintText = n.hintText?.toString()?.takeUnless { it.isBlank() },
                resourceId = n.viewIdResourceName,
                clickable = n.isClickable, editable = n.isEditable, scrollable = n.isScrollable,
                checkable = n.isCheckable, checked = n.isChecked, selected = n.isSelected,
                enabled = n.isEnabled, focused = n.isFocused, secret = n.isPassword,
                wheel = isWheel(n),
                children = children,
            ))
        }

        val trees = content.map { w ->
            val root = w.root
            UiWindowTree(
                id = w.id,
                type = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                    else -> "other"
                },
                layer = w.layer, title = w.title?.toString(), packageName = root?.packageName?.toString(),
                active = w.isActive, focused = w.isFocused,
                nodes = root?.let { visit(it, 0, false) }.orEmpty(),
            )
        }
        return ScreenRead(
            entries = entries,
            hash = hash,
            texts = texts,
            passwordField = password,
            hasAppWindow = content.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null },
            anyAppWindow = content.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION },
            topPackage = topPackage(windows),
            windows = trees,
        )
    }

    /** 単一ノードの現在の特性 (コンテキストなし)。ノードが表示されないか、小さすぎる場合は null。 */
    fun locatorOf(n: AccessibilityNodeInfo, width: Int, height: Int): ElementLocator? {
        if (!n.isVisibleToUser) return null
        val rect = Rect().also { n.getBoundsInScreen(it) }
        clip(rect, width, height)
        if (rect.width() < MIN_SIZE || rect.height() < MIN_SIZE) return null
        val own = ownLabel(n)
        return ElementLocator(
            roleOf(n), labelOf(n, own), n.viewIdResourceName, rect.centerX(), rect.centerY(), null,
            hint = own != null && showingHint(n), inList = inList(n),
        )
    }

    /**
     * はスクロール ホイール セレクターのように見えます。オリジナルのデジタル スクロール ホイール コントロール、またはスクロール可能で、中央に垂直方向に等距離に配置されたいくつかの短い値があります (ルールについては、[WheelMath.looksLikeWheel] を参照してください)。
     * はむしろ認識を見逃してしまいます)。 1 つの項目の単語を発音できない場合はカウントされません。
     */
    fun isWheel(n: AccessibilityNodeInfo): Boolean {
        if (n.className?.toString()?.endsWith("NumberPicker") == true) return true
        if (!n.isScrollable || n.childCount !in 3..12) return false
        val box = Rect().also(n::getBoundsInScreen)
        val r = Rect()
        val items = mutableListOf<WheelMath.Item>()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            val label = ownLabel(c) ?: c.takeIf { it.childCount == 1 }?.getChild(0)?.let(::ownLabel) ?: return false
            c.getBoundsInScreen(r)
            items += WheelMath.Item(label, r.centerX(), r.centerY())
        }
        return WheelMath.looksLikeWheel(items.sortedBy { it.centerY }, box.left, box.right)
    }

    /** リスト コンテナ: サブアイテムは再利用され、同じ位置が他のアイテムに置き換えられる可能性があります。 */
    private fun isList(n: AccessibilityNodeInfo): Boolean {
        if (n.collectionInfo != null) return true
        val cls = n.className?.toString() ?: return false
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView")
    }

    private fun inList(n: AccessibilityNodeInfo): Boolean {
        var p = n.parent
        var depth = 0
        while (p != null && depth++ < MAX_DEPTH) {
            if (isList(p)) return true
            p = p.parent
        }
        return false
    }

    private fun clip(rect: Rect, width: Int, height: Int) {
        if (!rect.intersect(0, 0, width, height)) rect.setEmpty()
    }

    private fun roleOf(n: AccessibilityNodeInfo) = n.className?.toString()?.substringAfterLast('.')?.ifEmpty { null } ?: "View"

    private fun labelOf(n: AccessibilityNodeInfo, own: String?) = own ?: if (n.isClickable) descendantLabel(n) else ""

    /** はインターフェイスのフィンガープリントのみをカウントし、要素テーブル (アクションの前後の比較用) を構築しません。 */
    fun hash(windows: List<AccessibilityWindowInfo>): Long {
        var hash = FNV_OFFSET
        val rect = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                hash = mix(hash, n.className, n.text, n.contentDescription, rect.flattenToString(),
                    states(n))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        for (w in contentWindows(windows)) w.root?.let { visit(it, 0) }
        return hash
    }

    /** はアクションの効果を判定するための制御ステータスです([hash]と同じバッチ)。 */
    @Suppress("DEPRECATION")
    private fun uiStates(n: AccessibilityNodeInfo): Set<String> = buildSet {
        if (n.isChecked) add(UiStates.CHECKED)
        if (n.isSelected) add(UiStates.SELECTED)
        if (!n.isEnabled) add(UiStates.DISABLED)
    }

    // isChecked は、API 36 以降、スリーステート チェックに置き換えられました。 minSdk 30 はまだ使用する必要があります
    @Suppress("DEPRECATION")
    private fun states(n: AccessibilityNodeInfo) =
        "${n.isChecked}${n.isSelected}${n.isEnabled}${n.isFocused}${n.textSelectionStart}:${n.textSelectionEnd}"

    /** ノード自体の名前: テキスト (プロンプトもカウントされ、入力ボックスはそれに依存して目的を説明します)。そうでない場合は、コンテンツの説明を使用します。 プロンプトは */
    fun ownLabel(n: AccessibilityNodeInfo): String? {
        val text = n.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = n.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return n.hintText?.toString()?.trim()?.ifEmpty { null }
    }

    /**
     * ノードに表示されます。システムは ShowingHintText とマークされているか、プロンプトに実際のテキストまたは名前がありません。
     * または報告されたテキストはプロンプトそのものです (一部の入力ボックスにはそのマークがありません)。
     */
    private fun showingHint(n: AccessibilityNodeInfo): Boolean {
        if (n.isShowingHintText) return true
        val hint = n.hintText?.toString()?.trim()
        if (hint.isNullOrEmpty()) return false
        val text = n.text?.toString()?.trim()
        return if (text.isNullOrEmpty()) n.contentDescription.isNullOrBlank() else text == hint
    }

    /** 入力ボックスに表示されているプロンプト。プロンプトが表示されない場合は null です。 */
    fun placeholderOf(n: AccessibilityNodeInfo): String? =
        if (showingHint(n)) n.text?.toString()?.trim()?.ifEmpty { null } ?: n.hintText?.toString()?.trim() else null

    /**
     * 入力ボックスでユーザーに表示される実際のテキスト。プロンプトが表示される場合は空の文字列になり、パスワード ボックスを読み取れない場合は null になります。
     * [placeholder] は、入力前に表示されるプロンプトです。一部の入力ボックスは、入力後に「プロンプトを表示」とマークされなくなりましたが、テキストとして報告され、現時点では空とみなされます。
     * ただし、入力する[typing]自体がプロンプトと等しい場合は、「表示プロンプト」マークのない同じテキストが入力されたものとみなされます。
     */
    fun fieldText(n: AccessibilityNodeInfo, placeholder: String? = null, typing: String? = null): String? {
        if (n.isPassword) return null
        if (n.isShowingHintText) return ""
        val text = n.text?.toString() ?: return ""
        val shown = text.trim()
        if (typing != null && shown == typing.trim()) return text
        if (showingHint(n) || (placeholder != null && shown == placeholder)) return ""
        return text
    }

    /** 名前のないクリック可能なコンテナは、子ノードのテキストを名前として使用します。 */
    private fun descendantLabel(n: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun walk(c: AccessibilityNodeInfo, depth: Int) {
            if (depth > 6 || parts.joinToString(" ").length > AGGREGATE_MAX) return
            for (i in 0 until c.childCount) {
                val child = c.getChild(i) ?: continue
                if (!child.isVisibleToUser) continue
                ownLabel(child)?.let { if (it !in parts) parts += it }
                walk(child, depth + 1)
            }
        }
        walk(n, 0)
        val joined = parts.joinToString(" ")
        return if (joined.length > AGGREGATE_MAX) joined.take(AGGREGATE_MAX) + "…" else joined
    }

    /** `com.x:id/cart_icon` → `cart_icon`;紛らわしい短縮名はその目的を特定するために使用できないため、指定されません。 */
    private fun readableResourceId(id: String?): String? {
        val short = id?.substringAfterLast('/') ?: return null
        return short.takeIf { Regex("^[A-Za-z0-9_]{4,}$").matches(it) && Regex("[A-Za-z]{3}").containsMatchIn(it) }
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    private fun mix(h0: Long, vararg parts: Any?): Long {
        var h = h0
        for (p in parts) {
            val s = p?.toString() ?: "\u0000"
            for (c in s) h = (h xor c.code.toLong()) * FNV_PRIME
            h = (h xor 0x1f) * FNV_PRIME
        }
        return h
    }
}
