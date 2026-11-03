package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.RouteAnchors
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.tool.LOCATED_REF
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * 実行入口の呼び出し対象(不可逆判定、操作記録、経路)：どの画面を判定するか、@vNをどの座標に変更するか、ポート番号は何か、
 * 書き込み操作前のターゲット要素とアンカーポイント。すべては、通話が属する画面の画面読み取りステータス ([session]) に従って表示されます。これは読み取り専用であり、アクションは実行されません。
 */
internal class GateView {
    fun snapshotFor(session: ScreenSession, args: JsonObject): ScreenSnapshot? {
        val id = args.argStr("snapshotId")
        // 古い画面の番号が最新の画面に残っています: アクションが最新の画面に該当する場合、最新の画面に従って判断されます (番号は同じで、snapshotId はモデルによって与えられます)
        val ref = args.argStr("ref")
        val stabilizer = session.stabilizer
        if (stabilizer != null && id != null && ref != null && !LOCATED_REF.matches(ref)) {
            val r = stabilizer.resolve(ref, id)
            if (r is Resolved.Current && r.relocated) stabilizer.latest?.let { return it.screen.copy(snapshotId = id) }
        }
        if (id != null) return session.recentSnapshots.lastOrNull { it.snapshotId == id }
        return session.lastSnapshot
    }

    fun portRecord(session: ScreenSession, args: JsonObject): JsonObject {
        val st = session.stabilizer ?: return JsonObject(emptyMap())
        val ref = args.argStr("ref")
        val id = args.argStr("snapshotId")
        if (ref == null || id == null || LOCATED_REF.matches(ref)) return JsonObject(emptyMap())
        val r = st.resolve(ref, id) as? Resolved.Current ?: return JsonObject(emptyMap())
        return buildJsonObject { put("portRef", r.portRef); put("portSnapshotId", r.portSnapshotId) }
    }

    /**
     * @vNをクリックすると登録された座標に変更され、探しているもの（ターゲット）、元のテキスト（テキスト）、ビジュアルモデルで見られる単語（見られた）とともに、座標をクリックして判断します。
     * @vN を入力してクリアすると、座標にも変更され、他のパラメーター (入力) はそのまま残ります。
     * 残りをそのまま返します（認識しない @vN は実行時に ref_expired となりクリックされません）。
     */
    fun gateArgs(session: ScreenSession, verb: Verb?, args: JsonObject): JsonObject {
        val ref = locatedRef(verb, args) ?: return args
        val (p, _) = session.located.get(ref.first, ref.second) ?: return args
        val info = session.locatedInfo[ref.first]
        return buildJsonObject {
            if (verb != Verb.Tap) args.forEach { (k, v) -> if (k != "ref" && k != "snapshotId") put(k, v) }
            put("x", p.x); put("y", p.y)
            (info?.target ?: (args["target"] as? JsonPrimitive)?.content)?.let { put("target", it) }
            info?.text?.let { put("text", it) }
            info?.seen?.let { put("seen", it) }
        }
    }

    /**
     * @vN を登録、または説明を表示した後、ターゲットのある座標をクリックします (ターゲットのない座標はパラメータ検証によって返されます)。
     * このタイプのクリックは、大小のモデルとメイン モデルの分業によって行われます ([com.wangzan101.mika.core.action.ConfirmDecision.mayBeIrreversible] を参照)。
     */
    fun visionSourced(session: ScreenSession, verb: Verb?, args: JsonObject): Boolean {
        if (verb != Verb.Tap) return false
        locatedRef(verb, args)?.let { return session.located.get(it.first, it.second) != null }
        val target = args.argStr("target")
        return session.described && args["ref"] == null && args["x"] != null && !target.isNullOrBlank()
    }

    fun confirmedSeen(session: ScreenSession, verb: Verb?, args: JsonObject): String? {
        val ref = locatedRef(verb, args) ?: return null
        if (session.located.get(ref.first, ref.second) == null) return null
        return session.locatedInfo[ref.first]?.seen?.takeIf { it.isNotBlank() }
    }

    /** クリックして入力し、クリアして @vN を受け入れます。 */
    private fun locatedRef(verb: Verb?, args: JsonObject): Pair<String, String>? {
        if (verb != Verb.Tap && verb != Verb.TypeText && verb != Verb.ClearText) return null
        val ref = args.argStr("ref")?.takeIf(LOCATED_REF::matches) ?: return null
        val sid = args.argStr("snapshotId") ?: return null
        return ref to sid
    }

    /** ルートは、ターゲット要素、アンカー ポイント、および書き込み操作が実行される前に現在の読み取り画面から取得された画面を使用します。 */
    class Capture(val target: Selector?, val before: Anchors, val snapshot: ScreenSnapshot?)
    var capture: Capture? = null

    fun anchorsAfter(after: ScreenSnapshot): Anchors? = capture?.let { RouteAnchors.after(it.snapshot, after) }

    fun captureTarget(session: ScreenSession, verb: Verb, o: JsonObject): Capture {
        // @vN 登録された座標による識別
        val checked = gateArgs(session, verb, o)
        val ref = checked.argStr("ref")
        val own = snapshotFor(session, checked)
        val s = own ?: session.lastSnapshot
        fun coord(k: String): Int? = when (val v = checked[k]) {
            is JsonPrimitive -> v.content.toDoubleOrNull()?.roundToInt()
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }.takeIf { it.size == 2 }?.average()?.roundToInt()
            else -> null
        }
        val x = coord("x"); val y = coord("y")
        val el = when {
            ref != null -> own?.elements?.firstOrNull { it.ref == ref }
            verb == Verb.Tap && x != null && y != null -> s?.let { RouteAnchors.elementAt(it, x, y) }
            // 入力とフォーカスされた入力ボックスの効果をクリア
            verb == Verb.TypeText || verb == Verb.ClearText -> s?.elements?.firstOrNull { it.focused && it.editable }
            else -> null
        }
        return Capture(el?.let { RouteAnchors.selector(s!!, it) }, RouteAnchors.before(s, el), s)
    }
}
