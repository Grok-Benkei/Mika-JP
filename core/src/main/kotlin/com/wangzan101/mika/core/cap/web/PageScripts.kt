package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.script.JsSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * ページ スクリプト (web_script) 内の `mika.*` への呼び出しは、Mika のエントリに渡されます。ポートは呼び出しのみを送信します。
 * の各呼び出しはそのまま[call]に渡され、戻り値(`{ok, value}`または`{ok:false, error, message}`)がそのままスクリプトに渡されます。
 * ページを読み取るための呼び出し (query、queryAll、text) は `{ok:true, read:{…}}` を返します。パラメーターは検証およびカウントされており、ポートは読み取りワールドでそれらを読み取ります。
 */
interface ScriptBridge {
    /** は [ScriptAbort] をスローしてスクリプト全体を終了します (上限を超え、確認が必要で、警備員によって停止され、ページがユーザーに渡されました)。 */
    suspend fun call(op: String, args: JsonObject): JsonObject
    /** `mika.log`: 応答を待っています。 */
    fun log(text: String)
}

class ScriptAbort(val error: String, message: String) : Exception(message)

sealed interface ScriptRun {
    /** は実行を終了しました。 [valueJson] は戻り値の JSON です (戻り値は null ではありません)。 */
    data class Done(val valueJson: String?) : ScriptRun
    /** ページ全体が飛び、スクリプトはここで停止します。 */
    data object Navigated : ScriptRun
    /** [error]:スクリプトエラー |タイムアウト | devtools_利用不可 | no_tab_open、または [ScriptAbort] エラー コード。 [line] はスクリプト内の行番号です (1 から始まります)。 */
    data class Failed(val error: String, val message: String, val line: Int? = null) : ScriptRun
}

/** ページ スクリプト内の `mika.*` 呼び出し。 [id] は 0 で、不等な応答 (ログ) を示します。 */
data class ScriptCall(val id: Int, val op: String, val args: JsonObject)

/**
 * ページ スクリプト アプローチ: モデル コードはページ オブジェクトを取得できません (リストを無効にすることではなく、構造によって)。
 * - スクリプト ワールド: 新しい孤立した世界。事前スクリプトは 2 つのことだけを実行します。事前に保存された AsyncFunction を使用してモデル コードをコンパイルし、ワールドのグローバル オブジェクトをアップロードします。
 *   (プロトタイプ チェーンを含む) のすべての名前はパラメーターとしてカバーされ、未定義として渡されます。また、[ALLOWED_GLOBALS] (純粋な ECMAScript 組み込み、DOM に触れることができません) のみが許可されます。
 *   次に、文字列をコードとして実行し、グローバル オブジェクト (関数コンストラクター ファミリ、eval、コール スタック API) を取得する方法をブロックします。使用できるモデルコードは`mika`のみです。
 *   `mika` の各呼び出しは、Mika に送信される JSON メッセージであり、返されるのは JSON のみです。
 * - 世界を読む: 別の隔離された世界。修正された [READER] (Mika 自身が作成) のみをインストールし、検証されたパラメーターに従って DOM を読み取り、純粋なデータ (パスワードなどの値コーディング) を返します。
 *   モデル コードはそれと同じ世界に存在しないため、オブジェクトを手に入れることはできません。
 */
object PageScriptProtocol {
    const val SOURCE_URL = "mika-script.js"
    const val PRELUDE_URL = "mika-prelude.js"
    /** 1 回の mika.wait の上限 (ミリ秒)。 */
    const val MAX_WAIT_MS = 10_000
    const val WORLD_PREFIX = "mika-script-"
    const val READER_WORLD_PREFIX = "mika-reader-"
    /** スクリプトがパスワードなどの入力ボックスの値を読み取るときに取得するもの (実行中のレコードのコーディングと一致)。 */
    const val SECRET_MASK = "***"

    /**
     * 読み取り不可能な値を含む入力ボックス: ページ画面読み上げスクリプト (page.js) と同じルール - パスワード ボックス、隠しフィールド、
     * とオートコンプリートには、パスワード、銀行カード、ワンタイム認証コードがマークされています。
     */
    val SECRET_AUTOCOMPLETE = listOf("current-password", "new-password", "cc-number", "cc-csc", "cc-exp", "cc-exp-month", "cc-exp-year", "one-time-code")
    val SECRET_INPUT_TYPES = listOf("password", "hidden")

    /** モデル コードで使用できるグローバル名: ECMAScript 自体の値と関数のみ。DOM やグローバル オブジェクトに渡すことはできません。 */
    val ALLOWED_GLOBALS = listOf(
        "undefined", "NaN", "Infinity", "Object", "Array", "Number", "Boolean", "String", "Symbol", "BigInt", "Date", "RegExp", "Promise",
        "Map", "Set", "WeakMap", "WeakSet", "WeakRef", "JSON", "Math", "Intl", "Reflect", "Proxy",
        "Error", "AggregateError", "EvalError", "RangeError", "ReferenceError", "SyntaxError", "TypeError", "URIError",
        "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array",
        "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array",
        "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURI", "encodeURIComponent", "decodeURI", "decodeURIComponent", "Iterator",
    )

    /** はパラメータ名 (厳密モードの予約語) として使用できません。 eval もブロックされます。 */
    internal val RESERVED = listOf(
        "eval", "arguments", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum",
        "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super",
        "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static", "implements",
        "interface", "package", "private", "protected", "public", "await", "async", "mika",
    )

    /** モデル コードの最初の行の前に、コンパイルされたソース コード (`(async function anonymous(…`、`) {`、`'use strict';`、`return (async () => {`) の数行があります。 */
    private const val LINE_OFFSET = 4

    private val json = Json { ignoreUnknownKeys = true }

    private fun js(v: List<String>): String = v.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    /**
     * は、スクリプトの世界にインストールされる事前スクリプトです。使用するオリジナルを保存し、文字列をコードとして実行する方法をブロックし、マスクされる名前を特定し、`__mikaRun` を定義します。
     * 各 mika 通話は番号付きで送信ボックスに入れられます。 Kotlin は常に [TAKE] をハングし (次のイベントまで返されます)、結果は `__mikaSettle(id, r)` で返されます。
     * `__mikaEnd()` により後続の呼び出しが失敗し、保留中の [TAKE] は null を返します。 DevTools を使用しないバインド: バインドにはランタイムを開く必要があり、デバッガーを開くとページにデバッガーが表示されます。
     */
    val PRELUDE: String = """
(() => {
  'use strict';
  const G = globalThis, O = Object;
  if (O.prototype.hasOwnProperty.call(G, '__mikaRun')) return;
  const AsyncFunction = O.getPrototypeOf(async function () {}).constructor;
  const stringify = JSON.stringify, apply = Reflect.apply, timer = G.setTimeout, freeze = O.freeze;
  const fixed = (o, k, v) => { try { O.defineProperty(o, k, { value: v, writable: false, enumerable: false, configurable: false }); } catch (e) {} };
  const unavailable = function () { throw new TypeError('not available in web_script: the script has no access to the page; use mika.*'); };
  // 文字列をコードとして実行する経路（グローバルスコープで動き、グローバルオブジェクトに到達できる）と、呼び出しスタックのAPI（関数やthisに到達できる）。
  for (const f of [function () {}, async function () {}, function* () {}, async function* () {}]) fixed(O.getPrototypeOf(f), 'constructor', unavailable);
  fixed(G, 'eval', unavailable);
  fixed(G, 'Function', unavailable);
  fixed(G.Error, 'prepareStackTrace', undefined);

  const pending = new Map();
  let seq = 0, ended = false;
  // 送信キュー。Kotlinが登録した__mikaTakeなどをtakersに保持し、届いたデータを順に渡す。
  const outbox = [], takers = [];
  const post = (s) => { const t = takers.shift(); if (t) t(s); else outbox.push(s); };
  fixed(G, '__mikaTake', () => new Promise((r) => {
    if (outbox.length) r(outbox.shift()); else if (ended) r(null); else takers.push(r);
  }));
  const call = (op, args) => new Promise((resolve, reject) => {
    if (ended) { reject(new Error('script_ended')); return; }
    const id = ++seq;
    pending.set(id, { resolve, reject });
    post(stringify({ id, op, args: args || {} }));
  });
  fixed(G, '__mikaSettle', (id, r) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (r && r.ok) { p.resolve(r.value); return; }
    const e = new Error(((r && r.error) || 'failed') + (r && r.message ? ': ' + r.message : ''));
    e.code = r && r.error;
    p.reject(e);
  });
  fixed(G, '__mikaEnd', () => {
    ended = true;
    for (const p of pending.values()) p.reject(new Error('script_ended'));
    pending.clear();
    for (const t of takers.splice(0)) t(null);
  });
  const sleep = (ms) => new Promise((r) => timer(r, ms));
  const refOf = (r) => (r && typeof r === 'object') ? r.ref : r;
  const str = (x) => { if (typeof x === 'string') return x; try { return stringify(x); } catch (e) { return String(x); } };
  const mika = freeze({
    snapshot: () => call('snapshot'),
    read: (opts) => call('read', opts || {}),
    query: (selector, opts) => call('query', { selector, ...(opts || {}) }),
    queryAll: (selector, opts) => call('queryAll', { selector, ...(opts || {}) }),
    text: (selector, opts) => call('text', { selector: selector || 'body', ...(opts || {}) }),
    tap: (ref) => call('tap', { ref: refOf(ref) }),
    type: (ref, text, opts) => call('type', { ref: refOf(ref), text, enter: !!(opts && opts.enter) }),
    scroll: (direction, opts) => call('scroll', { direction, ...(opts || {}) }),
    navigate: (url) => call('navigate', { url }),
    wait: async (w) => {
      if (typeof w === 'number') { await sleep(Math.max(0, Math.min(w, $MAX_WAIT_MS))); return true; }
      if (!w || typeof w.text !== 'string' || !w.text) throw new Error('invalid_arguments: mika.wait takes milliseconds or {text, timeoutMs}');
      return call('waitText', { text: w.text, timeoutMs: w.timeoutMs });
    },
    log: (...xs) => { if (!ended) post(stringify({ id: 0, op: 'log', args: { text: xs.map(str).join(' ') } })); },
  });

  // この実行環境のグローバルオブジェクトにある名前を、プロトタイプチェーンも含めてすべて隠す。純粋なECMAScript組み込みだけを許可する。
  const ALLOWED = new Set(${js(ALLOWED_GLOBALS)});
  const RESERVED = new Set(${js(RESERVED)});
  const names = new Set(['globalThis', 'window', 'self', 'document', 'location', 'top', 'parent', 'frames', 'opener']);
  for (let o = G; o; o = O.getPrototypeOf(o)) for (const k of O.getOwnPropertyNames(o)) {
    if (/^[A-Za-z_$][\w$]*$/.test(k) && !ALLOWED.has(k) && !RESERVED.has(k)) names.add(k);
  }
  names.add('__mikaRun');
  const shadow = [...names];
  const blanks = shadow.map(() => undefined);
  fixed(G, '__mikaRun', (code) => {
    // モデルのコードは内側のアロー関数に入れる。隠した名前と同名の宣言も独自のスコープに属するため、引数と衝突しない。
    const fn = new AsyncFunction(...shadow, 'mika', "'use strict';\nreturn (async () => {\n" + code + '\n})();\n//# sourceURL=$SOURCE_URL');
    return apply(fn, undefined, [...blanks, mika]).then((r) => (r === undefined ? null : stringify(r)));
  });
})();
//# sourceURL=$PRELUDE_URL
""".trimStart()

    /** はモデルコードの式を実行します(スクリプトワールドなどで評価されるお約束)。 */
    fun run(code: String): String = "globalThis.__mikaRun(${JsonPrimitive(code)})"

    /**
     * モデル コードではインポートは許可されません (語彙をチェックし、文字列、コメント、正規表現の外側にある場合はブロックします): 動的インポートは、グローバル スコープでモジュールを実行し、グローバル オブジェクトを取得します。
     * は問題の説明を返します。問題がない場合は null になります。
     */
    fun codeProblem(code: String): String? =
        if (JsSource.usesImport(code)) "import is not available in page scripts (write the word only inside strings or comments)" else null

    /**
     * は世界の固定リーダーを読み取ります。`{mode, selector, fields, limit, maxChars}` を押して DOM を読み取ります。純粋なデータのみが返されます。
     * モード: one (最初)、all (リストと合計)、count (数値)、text (結合された表示テキスト)、has (ページ テキストに [selector] という単語があるかどうか)。
     * パスワードのような値と value 属性がコーディングされ、on* 属性は与えられません。
     */
    val READER: String = """
(() => {
  'use strict';
  if (Object.prototype.hasOwnProperty.call(globalThis, '__mikaRead')) return;
  const MASK = '$SECRET_MASK';
  const SECRET_AC = new Set(${js(SECRET_AUTOCOMPLETE)});
  const SECRET_TYPES = new Set(${js(SECRET_INPUT_TYPES)});
  const secret = (el) => {
    if (el instanceof HTMLInputElement && SECRET_TYPES.has(String(el.type).toLowerCase())) return true;
    if (!(el instanceof HTMLInputElement) && !(el instanceof HTMLTextAreaElement)) return false;
    const ac = el.getAttribute('autocomplete');
    return !!ac && ac.toLowerCase().split(/\s+/).some((t) => SECRET_AC.has(t));
  };
  const clip = (s, n) => (s.length > n ? s.slice(0, n) : s);
  const textOf = (el) => (secret(el) ? '' : String(el.innerText ?? el.textContent ?? '').trim());
  const item = (el, f, n) => {
    const o = { tag: el.tagName.toLowerCase() };
    if (f.includes('text')) o.text = clip(textOf(el), n);
    if (f.includes('attrs')) {
      o.attrs = {};
      for (const a of el.attributes) {
        const k = a.name.toLowerCase();
        if (k.startsWith('on')) continue;
        o.attrs[k] = k === 'value' && secret(el) ? (a.value ? MASK : '') : clip(a.value, n);
      }
    }
    if (f.includes('value')) {
      o.value = 'value' in el ? (secret(el) ? (el.value ? MASK : '') : clip(String(el.value), n)) : null;
      if (el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio')) o.checked = el.checked;
      if (el instanceof HTMLSelectElement) o.selected = Array.from(el.selectedOptions, (x) => clip(x.text, n));
    }
    if (f.includes('box')) {
      const r = el.getBoundingClientRect(), w = innerWidth || 1, h = innerHeight || 1;
      o.box = { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height),
        center: { x: Math.round(Math.min(999, Math.max(0, (r.x + r.width / 2) / w * 999))), y: Math.round(Math.min(999, Math.max(0, (r.y + r.height / 2) / h * 999)) ) },
        visible: r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < h && r.left < w };
    }
    return o;
  };
  Object.defineProperty(globalThis, '__mikaRead', { value: (q) => {
    if (q.mode === 'has') return { ok: true, value: String(document.body ? document.body.innerText : '').includes(q.selector) };
    let list;
    try { list = document.querySelectorAll(q.selector); } catch (e) { return { ok: false, error: 'invalid_selector', message: String(e.message) }; }
    if (q.mode === 'count') return { ok: true, value: list.length };
    if (q.mode === 'text') {
      let t = '';
      for (const el of list) { const s = textOf(el); if (s) t += (t ? '\n' : '') + s; if (t.length >= q.maxChars) break; }
      return { ok: true, value: clip(t, q.maxChars) };
    }
    const items = [];
    for (let i = 0; i < list.length && items.length < q.limit; i++) items.push(item(list[i], q.fields, q.maxChars));
    return { ok: true, value: q.mode === 'one' ? (items[0] || null) : { items, total: list.length } };
  } });
})();
""".trimStart()

    /** は、読み方の世界では [spec] に従って読まれる式です。 */
    fun read(spec: JsonObject): String = "globalThis.__mikaRead($spec)"

    /**
     * 結論: スクリプトが引き続きリクエストを発行する場合、`Network.requestWillBeSent` 開始コール スタックにモデル スクリプトまたは事前スクリプトが存在します。
     * スタック全体 (非同期親チェーンを含む) を表示します。
     */
    fun requestFromScript(params: JsonObject): Boolean {
        var stack = (params["initiator"] as? JsonObject)?.get("stack") as? JsonObject
        var depth = 0
        while (stack != null && depth++ < 32) {
            val frames = stack["callFrames"] as? JsonArray
            if (frames != null && frames.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull in SCRIPT_URLS }) return true
            stack = stack["parent"] as? JsonObject
        }
        return false
    }

    /** 結論: スクリプトが実行中で、進行中の mika アクションがない場合、メイン フレームはジャンプします。それが mika アクションによって引き起こされていない場合、メイン フレームは停止します。 */
    fun navigationOutsideAction(params: JsonObject, mainFrameId: String, inAction: Boolean): Boolean =
        !inAction && (params["frameId"] as? JsonPrimitive)?.contentOrNull == mainFrameId

    private val SCRIPT_URLS = setOf(SOURCE_URL, PRELUDE_URL)

    /** 送信ボックス内の次のメッセージの式を取得します (スクリプトの世界で評価し、Promise を待ちます。完了後に null を返します)。 */
    const val TAKE = "globalThis.__mikaTake()"

    /** 送信トレイ内のメッセージ。有効でない呼び出しは null です。 */
    fun parseCall(payload: String): ScriptCall? {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: return null
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val args = o["args"] as? JsonObject ?: JsonObject(emptyMap())
        return ScriptCall(id, op, args)
    }

    /** は、[id] 番目の呼び出しの結果を返します。結果は、式に直接埋め込まれた JSON オブジェクト リテラルです。 */
    fun settle(id: Int, result: JsonObject): String = "globalThis.__mikaSettle && globalThis.__mikaSettle($id, $result)"

    const val END = "globalThis.__mikaEnd && globalThis.__mikaEnd()"

    private val STACK_LINE = Regex(Regex.escape(SOURCE_URL) + ":(\\d+):\\d+")

    /**
     * `Runtime.evaluate` 例外詳細 → (説明、スクリプト内の行番号)。行番号は、コール スタック内のモデル スクリプトの位置を取得し、モデルによって書き込まれた行番号 (1 から始まる) に変換します。
     * 構文エラー そのようなスタックは存在しないため、行番号は報告されません。
     */
    fun error(details: JsonObject): Pair<String, Int?> {
        val exception = details["exception"] as? JsonObject
        val description = (exception?.get("description") as? JsonPrimitive)?.contentOrNull
            ?: (exception?.get("value") as? JsonPrimitive)?.contentOrNull
        val text = (details["text"] as? JsonPrimitive)?.contentOrNull
        val message = description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: text ?: "script failed"
        val line = description?.let { STACK_LINE.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.let { it - LINE_OFFSET }?.takeIf { it >= 1 }
        return message to line
    }
}
