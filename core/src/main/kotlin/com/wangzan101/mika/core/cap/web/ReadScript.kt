package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.record.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

const val READ_SCRIPT_DEFAULT_TIMEOUT_MS = 10_000
const val READ_SCRIPT_MAX_TIMEOUT_MS = 30_000
/** スリープを待機する最大時間。 */
const val READ_SLEEP_MAX_MS = 5_000

/**
 * スクリプトの読み方 (web_evaluate_js、サイトコマンド): 現在のタグのメインフレーム内の隔離された世界で実行されます。本物の DOM を見ることはできますが、オリジナルに触ることはできません。
 * - モデル コードは web_script メソッドに従ってコンパイルされます。この世界のグローバル オブジェクトの名前はすべてパラメーターとしてカバーされ、純粋な ECMAScript 組み込みと以下に示すいくつかの名前のみが残ります。
 *   (document、window、location、fetch、state、args、sleep、getComputedStyle、URL、URLSearchParams)。
 * - 指定されたページ オブジェクトはすべてパッケージ化されています。属性の読み取りは通常どおり読み取られ (読み取りオブジェクトは別のレイヤーにラップされます)、書き込み、削除、属性の定義、プロトタイプの変更はすべて読み取り専用でレポートされます。
 *   関数は、登録された読み取り専用メソッド (検索、属性の読み取り、ボリューム位置、反復)、およびその他のメソッド (ポイント、フォーカス、送信、DOM の変更、ウィンドウを開く、ジャンプ) によってのみ調整でき、すべて読み取り専用でレポートされます。
 *   ネイティブ関数はオリジナルのみを取得し、モデルは常にパッケージを取得します。モデルが触れることができる組み込みオブジェクトが最初にフリーズされ、グローバル名が読み取り専用に設定されます。これらを変更して、元の関数をインターセプトしたり、チェックに使用される関数を置き換えたりすることはできません。
 * - パスワード、隠しフィールド、確認コード、銀行カードなどの入力ボックスの値は、`***` として読み取られます。入力ボックス自体のインターフェイス上の [SECRET_FIELD_PROPS] のみを読み取ることができます。 同じことが、
 *   の相同 iframe、シャドウ ルート、およびテンプレートにも当てはまります (ノードを識別するには、instanceof ではなくネイティブ ゲッターを使用します)。 innerHTML は、どのウィンドウにも属さないコピーをコーディングするタイプであり、ページ コードは実行されません。
 *   外側の要素に単語を含む秘密のテキストエリアがある場合、textContent クラスもコピーからフェッチされます。シークレットテキストエリアの子テキストノードとシークレット入力ボックスの値属性ノードは`***`として読み取られます。
 *   も、取得後にページを離れました。
 *   value 属性に従って要素を選択することはできません (値は少しずつ推測できます)。セレクターは、まず CSS ルールに従って復元し、次に ([SelectorPolicy]) を判断する必要があります。
 *   セレクターと属性名のパラメーターは最初に文字列に変更され、変更されるのは 1 回だけです。チェックされるパラメーターはブラウザーに渡されます。
 * - ネットワークに接続せずにスクリプトを読み取る: fetch が呼び出されるときに、blocked_network([NETWORK_MESSAGE]) が報告されます。 XMLHttpRequest、sendBeacon、WebSocket、EventSource、
 *   new Image() は、パッケージ化された呼び出しと構造体の一種であり、blocked_network も報告します。要素を作成して src/href を変更するということは、本来はページ (read_only) を変更することを意味します。すでにページ上にあるもののみを読み取ることができます。
 * - 状態は、このタグの読み取りワールドに残るオブジェクトです。同じページ上の複数の読み取りスクリプトによって共有されます。ページが変わったり文書が変更されると消えます。
 * 結論 (DevTools イベントについて): スクリプトはネットワーク リクエストを作成し、実行中にメイン フレームがジャンプするため、スクリプトの読み取りはエラーで終了します。
 */
object ReadScriptProtocol {
    /** スクリプトを読むときに、ネットワークの使用方法を読んでください。 */
    const val NETWORK_MESSAGE = "network access is not allowed in read scripts; use web_capture to read what the page loaded"

    /** リクエストを送信できるページ関数とコンストラクターの名前: パッケージ化を通じて呼び出された場合、コンストラクターは (単なる read_only ではなく)blocked_network を報告し、その理由を明確に説明します。 純粋な ECMAScript 組み込み以外の */
    val NETWORK_NAMES = listOf(
        "fetch", "sendBeacon", "XMLHttpRequest", "WebSocket", "EventSource", "Image", "Audio", "Request",
        "Worker", "SharedWorker", "RTCPeerConnection", "importScripts",
    )

    const val WORLD_PREFIX = "mika-read-"
    const val SOURCE_URL = "mika-read.js"
    const val PRELUDE_URL = "mika-read-prelude.js"
    const val MASK = PageScriptProtocol.SECRET_MASK

    /** モデル コードで使用できる名前も、パラメーターが渡される順序です。 */
    val PROVIDED = listOf("document", "window", "location", "fetch", "state", "args", "sleep", "getComputedStyle", "URL", "URLSearchParams")

    /**
     * インターフェイスに従って、パッケージ化を通じて呼び出すことができる読み取り専用メソッド。プロトタイプでは見つからないスキップ (WebView はシステム バージョンによって異なります)。
     * Symbol.iterator とのインターフェイスもその反復子を解放します。
     */
    val READ_METHODS: Map<String, List<String>> = linkedMapOf(
        "Node" to listOf("contains", "hasChildNodes", "getRootNode", "compareDocumentPosition", "isEqualNode", "isSameNode", "lookupPrefix", "lookupNamespaceURI", "isDefaultNamespace"),
        "Element" to listOf(
            "querySelector", "querySelectorAll", "closest", "matches", "webkitMatchesSelector", "getAttribute", "getAttributeNS", "getAttributeNames",
            "hasAttribute", "hasAttributeNS", "hasAttributes", "getElementsByClassName", "getElementsByTagName", "getElementsByTagNameNS",
            "getBoundingClientRect", "getClientRects", "checkVisibility",
        ),
        "Document" to listOf(
            "querySelector", "querySelectorAll", "getElementById", "getElementsByClassName", "getElementsByTagName", "getElementsByTagNameNS",
            "getElementsByName", "elementFromPoint", "elementsFromPoint", "hasFocus",
        ),
        "DocumentFragment" to listOf("querySelector", "querySelectorAll", "getElementById"),
        "CharacterData" to listOf("substringData"),
        "NodeList" to listOf("item", "forEach", "entries", "keys", "values"),
        "HTMLCollection" to listOf("item", "namedItem"),
        "HTMLFormControlsCollection" to listOf("namedItem"),
        "HTMLOptionsCollection" to listOf("item", "namedItem"),
        "HTMLSelectElement" to listOf("item", "namedItem"),
        "HTMLAnchorElement" to listOf("toString"),
        "HTMLAreaElement" to listOf("toString"),
        "NamedNodeMap" to listOf("item", "getNamedItem", "getNamedItemNS"),
        "DOMTokenList" to listOf("item", "contains", "forEach", "entries", "keys", "values"),
        "CSSStyleDeclaration" to listOf("getPropertyValue", "getPropertyPriority", "item"),
        "DOMRectReadOnly" to listOf("toJSON"),
        "DOMRectList" to listOf("item"),
        "MediaQueryList" to listOf(),
    )

    /** グローバル オブジェクト自体の読み取り専用メソッド。 */
    val GLOBAL_READ_METHODS = listOf("getComputedStyle", "matchMedia")

    /** 読み取り不可能な属性: Cookie、ローカル ストレージ (ログイン資格情報は常にその中にあります)。 */
    val DENIED_PROPS = listOf("cookie", "localStorage", "sessionStorage", "indexedDB", "caches", "cookieStore", "credentials", "serviceWorker", "clipboard", "openDatabase")

    /**
     * シークレット入力ボックス (パスワード ボックス、隠しフィールド、確認コードなど) の独自のインターフェイス (HTMLInputElement、HTMLTextAreaElement) で読み取ることができる属性: ページに設定された設定は、入力されたコンテンツによって変更されません。
     * インターフェイスの他の属性は、`***` として読み取られます: valueAsNumber、valueAsDate、selectionStart /selectionEnd、textLength、validity、validationMessage、
     * とブラウザの後に新たに追加されました。メソッドはまだパッケージ化されており、調整できるかどうかは [READ_METHODS] によって制御されます。 Value と textContent は、`***` または空として分類されます。
     */
    val SECRET_FIELD_PROPS = listOf(
        "type", "name", "form", "labels", "list", "placeholder", "autocomplete", "disabled", "readOnly", "required", "willValidate",
        "maxLength", "minLength", "size", "pattern", "min", "max", "step", "multiple", "dirName", "cols", "rows", "wrap",
        "formAction", "formEnctype", "formMethod", "formNoValidate", "formTarget",
    )

    /** は、最初に組み込みオブジェクト (プロトタイプとともに) をフリーズしてから、それらをモデルに与えます。グローバル名も読み取り専用に設定されます。モデルはグローバル名を変更できないため、パッケージで渡されたオリジナルをインターセプトしたり、チェックに使用される関数を置き換えたりすることはできません。 */
    private val FROZEN = PageScriptProtocol.ALLOWED_GLOBALS.filter { it !in setOf("undefined", "NaN", "Infinity") } + listOf("URL", "URLSearchParams")

    /** モデル コードの最初の行の前に、コンパイルされたソース コードの数行 (`(async function anonymous(…`、`) {`、`'use strict';`、`return (` または空白行) があります。 */
    private const val LINE_OFFSET = 4

    private fun js(v: Collection<String>): String = v.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    private fun jsMap(m: Map<String, List<String>>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> JsonPrimitive(k).toString() + ":" + js(v) }

    /** には、ワールドを読み取るための事前スクリプトがロードされています: 組み込みオブジェクトのフリーズ、調整可能なメソッドの登録、パッケージ化の定義、`__mikaReadRun` および `__mikaReadStop`。何もしていないふりをしてください。 */
    val PRELUDE: String = """
(() => {
  'use strict';
  const G = globalThis, O = Object, R = Reflect;
  if (O.prototype.hasOwnProperty.call(G, '__mikaReadRun')) return;
  const AsyncFunction = O.getPrototypeOf(async function () {}).constructor;
  const fnBind = G.Function.prototype.bind;
  const apply = R.apply, rget = R.get, ownKeys = R.ownKeys, getDesc = O.getOwnPropertyDescriptor, getProto = O.getPrototypeOf;
  const freeze = O.freeze, isArray = Array.isArray, keysOf = O.keys, define = O.defineProperty, names = O.getOwnPropertyNames;
  const stringify = JSON.stringify, parseJson = JSON.parse, timer = G.setTimeout;
  const URL_ = G.URL, loc = G.location, doc = G.document;
  const Elem = G.Element, Input = G.HTMLInputElement, Area = G.HTMLTextAreaElement;
  const MASK = '$MASK';
  const fixed = (o, k, v) => { try { define(o, k, { value: v, writable: false, enumerable: false, configurable: false }); } catch (e) {} };
  const fail = (code, message) => { const e = new Error(code + ': ' + message); e.code = code; return e; };
  const deny = (what) => { throw fail('read_only', what + ' is not allowed in a read script; it can only read the page'); };
  const NETWORK = new Set(${js(NETWORK_NAMES)});
  const offline = () => { throw fail('blocked_network', ${JsonPrimitive(NETWORK_MESSAGE)}); };

  // 文字列をコードとして実行する経路（グローバルスコープで動く）と、呼び出しスタックのAPIを遮断する。
  const unavailable = function () { throw fail('read_only', 'running code from strings is not available'); };
  const fnKinds = [function () {}, async function () {}, function* () {}, async function* () {}];
  for (const f of fnKinds) fixed(getProto(f), 'constructor', unavailable);
  fixed(G, 'eval', unavailable);
  fixed(G, 'Function', unavailable);
  fixed(G.Error, 'prepareStackTrace', undefined);

  // モデルから到達できる組み込みオブジェクトを凍結する。
  const ice = (x) => { if (x && (typeof x === 'object' || typeof x === 'function')) { try { freeze(x); } catch (e) {} } };
  // グローバル名も読み取り専用にする。モデルがStringを書き換えると後続の検査を無効化できるため。この実行環境で後から動く読み取りスクリプトにも適用する。
  for (const n of ${js(FROZEN)}) { const v = G[n]; ice(v); if (v && typeof v === 'function') ice(v.prototype); if (v !== undefined) fixed(G, n, v); }
  for (const f of fnKinds) ice(getProto(f));
  const arrayIter = getProto([][Symbol.iterator]());
  for (const p of [arrayIter, getProto(arrayIter), getProto(new Map().entries()), getProto(new Set().values()), getProto(''[Symbol.iterator]())]) ice(p);

  // ラッパーを介して呼び出せるネイティブ関数。
  const SAFE = new WeakSet();
  const READ = ${jsMap(READ_METHODS)};
  for (const iface of keysOf(READ)) {
    const C = G[iface];
    const p = C && C.prototype;
    if (!p) continue;
    for (const k of READ[iface]) { const d = getDesc(p, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
    const it = getDesc(p, Symbol.iterator);
    if (it && typeof it.value === 'function') SAFE.add(it.value);
  }
  for (const k of ${js(GLOBAL_READ_METHODS)}) { const d = getDesc(G, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
  for (const k of ['toString', 'valueOf']) { const d = getDesc(loc, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
  for (const f of [Array.prototype.values, Array.prototype.keys, Array.prototype.entries, Array.prototype.forEach, arrayIter.next,
    O.prototype.toString, O.prototype.toLocaleString, O.prototype.valueOf, O.prototype.hasOwnProperty, O.prototype.propertyIsEnumerable]) SAFE.add(f);
  // 第1引数がセレクターかどうかと、属性名を受け取る引数の位置。
  const SELECTORS = new WeakSet();
  for (const iface of ['Element', 'Document', 'DocumentFragment']) {
    const p = G[iface] && G[iface].prototype;
    if (p) for (const k of ['querySelector', 'querySelectorAll', 'closest', 'matches', 'webkitMatchesSelector']) { const d = getDesc(p, k); if (d && typeof d.value === 'function') SELECTORS.add(d.value); }
  }
  const ATTRS = new WeakMap();
  for (const [k, i] of [['getAttribute', 0], ['getAttributeNS', 1]]) { const d = Elem && getDesc(Elem.prototype, k); if (d && typeof d.value === 'function') ATTRS.set(d.value, i); }
${SelectorPolicy.JS}  const DENIED = new Set(${js(DENIED_PROPS)});

  // 値を読み取らない入力欄。パスワード、非表示フィールド、autocompleteでパスワード・カード番号・ワンタイムコードと指定されたもの。
  const SECRET_AC = new Set(${js(PageScriptProtocol.SECRET_AUTOCOMPLETE)});
  const SECRET_TYPES = new Set(${js(PageScriptProtocol.SECRET_INPUT_TYPES)});
  // ノードの種類はinstanceofではなくネイティブのgetterで調べる。同一オリジンのiframe内のノードは、そのフレーム独自のインターフェースに属する。instanceofでは判別できなくても、ネイティブのgetterなら判別できる。
  const getterOf = (C, k) => { const d = C && getDesc(C.prototype, k); return d && d.get; };
  const nodeTypeOf = getterOf(G.Node, 'nodeType'), localNameOf = getterOf(Elem, 'localName'), shadowHostOf = getterOf(G.ShadowRoot, 'host');
  const native = (g, x) => { if (!g || x === null || typeof x !== 'object') return undefined; try { return apply(g, x, []); } catch (e) { return undefined; } };
  // ノードの種類をオブジェクトごとに記録する。同じオブジェクトの種類は変わらない。属性を読む前には通常この判定が必要で、コレクション・矩形・スタイルなどの非ノードに対しては、
  // nodeTypeのネイティブgetterが毎回例外を投げる。同じオブジェクトの種類は1回だけ判定する。
  const kinds = new WeakMap();
  const typeOf = (x) => {
    if (x === null || typeof x !== 'object') return undefined;
    let t = kinds.get(x);
    if (t === undefined) { t = native(nodeTypeOf, x) || 0; kinds.set(x, t); }
    return t || undefined;
  };
  const tagOf = (x) => (typeOf(x) === 1 ? native(localNameOf, x) : undefined);
  const secretNow = (el) => {
    const tag = tagOf(el);
    if (tag === 'input') {
      if (SECRET_TYPES.has(String(el.type).toLowerCase())) return true;
    } else if (tag !== 'textarea') return false;
    const ac = el.getAttribute('autocomplete');
    return !!ac && ac.toLowerCase().split(/\s+/).some((t) => SECRET_AC.has(t));
  };
  // 一度機密入力欄になったものは、表示切り替えでtypeがtextになっても機密として扱い続ける。読み取り用の実行環境を作成した時点から監視する。
  const wasSecret = new WeakSet();
  const markIn = (root) => { try { if (secretNow(root)) wasSecret.add(root); for (const x of root.querySelectorAll('input, textarea')) if (secretNow(x)) wasSecret.add(x); } catch (e) {} };
  try {
    new G.MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'attributes') { if (secretNow(r.target)) wasSecret.add(r.target); } else for (const n of r.addedNodes) if (n.nodeType === 1) markIn(n);
      }
    }).observe(doc, { subtree: true, childList: true, attributes: true, attributeFilter: ['type', 'autocomplete'] });
  } catch (e) {}
  markIn(doc);
  const secret = (el) => secretNow(el) || (!!el && typeof el === 'object' && wasSecret.has(el));
  const SECRET_TEXT = new Set(['value', 'defaultValue', 'textContent', 'innerText', 'outerText', 'innerHTML', 'nodeValue']);
  // 機密入力欄固有のインターフェースの属性。ページ側で決まり、入力内容で変わらないものだけを読む。それ以外は、将来追加される値の取得APIも含めて***に置き換える。
  const FIELD_SAFE = new Set(${js(SECRET_FIELD_PROPS)});
  const fieldApi = (el, k) => { const tag = tagOf(el); return !!((tag === 'input' && Input && getDesc(Input.prototype, k)) || (tag === 'textarea' && Area && getDesc(Area.prototype, k))); };
  // innerHTMLとouterHTMLが出力する入力欄。templateの内容も出力されるが、querySelectorAllでは内部を探せないため、別途調べる。
  // 元の要素と複製を同じ順序で列挙する。templateは深く入れ子にできるため再帰しない。内容を持つのはHTMLのtemplateだけで、SVGの同名要素にはない。
  // 検索にはネイティブのメソッドを使う。フォーム内にquerySelectorAllという名前のコントロールがあっても検索できる。
  const contentOf = getterOf(G.HTMLTemplateElement, 'content');
  const QSA = { 1: Elem && Elem.prototype.querySelectorAll, 9: G.Document && G.Document.prototype.querySelectorAll, 11: G.DocumentFragment && G.DocumentFragment.prototype.querySelectorAll };
  const fields = (node, out) => {
    const todo = [node];
    const inner = (t) => { const c = tagOf(t) === 'template' && native(contentOf, t); if (c) todo.push(c); };
    for (let i = 0; i < todo.length; i++) {
      const n = todo[i], find = QSA[typeOf(n)];
      inner(n);
      if (find) for (const x of apply(find, n, ['input, textarea, template'])) { if (tagOf(x) === 'template') inner(x); else out.push(x); }
    }
    return out;
  };
  // HTMLに機密入力欄のvalueが含まれる場合、複製をマスクしてから取得する。対象の判定は元の要素で行い、複製の同じ位置をマスクする。複製には、過去に機密入力欄だったかどうかの記録がない。
  // 複製は、どのウィンドウにも属さない空の文書に置く。文書は1回作成して再利用する。カスタム要素を構築せず、ページのコードも実行せず、リソースも読み込まない。ShadowRootは複製できないため、子ノードをdivに入れる。
  // 出力形式はページ自身のものと2点異なるが、マスクには影響しない。noscriptの内容がエスケープされ、XHTMLのページがHTML形式になる。
  let inert = null;
  // マスク済みの複製。vに機密入力欄がなければnull、元の要素と複製が対応しなければMASK。
  const masked = (v) => {
    const root = typeOf(v) !== 1;
    const orig = fields(v, root ? [] : [v]);
    let hit = false;
    for (const x of orig) if (secret(x)) { hit = true; break; }
    if (!hit) return null;
    const d = inert || (inert = doc.implementation.createHTMLDocument(''));
    let c;
    if (root) { c = d.createElement('div'); for (const n of v.childNodes) c.appendChild(d.importNode(n, true)); } else c = d.importNode(v, true);
    const copy = fields(c, root ? [] : [c]);
    if (copy.length !== orig.length) return MASK;
    for (let i = 0; i < copy.length; i++) {
      if (!secret(orig[i])) continue;
      const x = copy[i];
      if (x.hasAttribute('value')) x.setAttribute('value', MASK);
      if (tagOf(x) === 'textarea' && x.textContent) x.textContent = MASK;
    }
    return c;
  };
  const html = (v, k) => { const c = masked(v); return c === null ? rget(v, k, v) : c === MASK ? MASK : rget(c, k, c); };
  // 機密textarea内の文字は初期値そのものである。ページが入力内容を初期値へ書き戻した場合もここに入る。外側の要素やshadow rootのtextContentから取り出せる。
  // 未描画の外側の要素のinnerTextも同様で、この場合innerTextはtextContentに等しい。textareaがoption内にある場合は、描画済みのinnerTextにも含まれる。
  // optionは自身のtextを使って表示される。aとoptionのtext、optionのlabelとvalue、outputのvalueとdefaultValueも子孫の文字として扱う。
  // これらは複製上でマスクしてから取得する。描画済みのその他の要素のinnerTextにはtextareaの内容が含まれないため、そのまま渡す。
  const textOf = getterOf(G.Node, 'textContent'), parentOf = getterOf(G.Node, 'parentNode'), closest = Elem && Elem.prototype.closest;
  // 属性名にconstructorや__proto__が含まれることがあるため、Mapを使う。
  const DESC = new Map([['text', new Set(['a', 'option'])], ['label', new Set(['option'])], ['value', new Set(['option', 'output'])], ['defaultValue', new Set(['output'])]]);
  // v内に文字を持つ機密textareaがあるか。なければ0、あれば1、option内にあれば2。
  const areasIn = (v) => {
    const find = QSA[typeOf(v)];
    let hit = 0;
    if (find) for (const a of apply(find, v, ['textarea'])) if (secret(a) && native(textOf, a)) { hit = 1; if (closest && apply(closest, a, ['option'])) return 2; }
    return hit;
  };
  const text = (v, k) => {
    const x = rget(v, k, v);
    if (!x || typeof x !== 'string') return x;
    const hit = areasIn(v), shown = k === 'innerText' || k === 'outerText';
    if (!hit || (shown && hit === 1 && x !== native(textOf, v))) return x;
    const c = masked(v);
    return c === null ? x : c === MASK ? MASK : DESC.has(k) ? rget(c, k, c) : native(textOf, c);
  };
  // 一度取得した機密のテキストノードは記録し、以後も機密として扱う。機密textareaの子テキストノードを含み、XMLページのCDATAセクションもテキストノードとして値に含める。
  // 機密入力欄のvalue属性ノードも対象。ページがtextareaの文字を置き換えたり、入力内容を初期値へ書き戻したり、value属性を削除した場合、古いノードは文書から外れても文字を保持している。
  // ノードが属する入力欄も記録する。モデルへ渡した時点では通常の入力欄でも、後から機密になり、同じノードへ文字が書き込まれ、そのノードが文書から外れたり、
  // 別の要素へ移動したりした場合は、元の入力欄に基づいて判定する。
  const held = new WeakSet(), ownerOf = new WeakMap();
  const HELD_TEXT = new Set(['data', 'nodeValue', 'textContent', 'wholeText', 'length', 'value']);
  const substringData = G.CharacterData && G.CharacterData.prototype.substringData;
  const holds = (v) => {
    if (held.has(v)) return true;
    const t = typeOf(v);
    let p;
    if (t === 3 || t === 4) { p = native(parentOf, v); if (tagOf(p) !== 'textarea') p = undefined; }
    else if (t === 2) { if (String(v.name).toLowerCase() !== 'value') return false; p = v.ownerElement; }
    else return false;
    if (p) ownerOf.set(v, p); else p = ownerOf.get(v);
    if (!p || !secret(p)) return false;
    held.add(v);
    return true;
  };
  const readProp = (v, k) => {
    if (typeof k === 'string') {
      if (DENIED.has(k)) deny('reading ' + k);
      if ((k === 'outerHTML' || k === 'innerHTML') && (typeOf(v) === 1 || (k === 'innerHTML' && native(shadowHostOf, v) !== undefined))) return html(v, k);
      if (SECRET_TEXT.has(k) && secret(v)) { const x = rget(v, k, v); return x ? MASK : x; }
      if (!FIELD_SAFE.has(k) && fieldApi(v, k) && secret(v)) { const x = rget(v, k, v); return typeof x === 'function' ? x : MASK; }
      if (HELD_TEXT.has(k) && holds(v)) { const x = rget(v, k, v); return x ? MASK : x; }
      if (k === 'textContent' || k === 'innerText' || k === 'outerText') return text(v, k);
      const tags = DESC.get(k);
      if (tags !== undefined) {
        const tag = tagOf(v);
        // selectのvalueは、実際のページで選択中のoptionのvalueである。複製で選ばれているoptionとは限らない。
        if (tag === 'select' && k === 'value' && areasIn(v)) { const i = rget(v, 'selectedIndex', v); return i < 0 ? '' : readProp(rget(v, 'options', v)[i], k); }
        if (tags.has(tag)) return text(v, k);
      }
    }
    return rget(v, k, v);
  };

  // ラップ処理。モデルにはラッパーだけを渡し、元のオブジェクトはネイティブ関数だけに渡す。
  const toProxy = new WeakMap(), toReal = new WeakMap();
  const wrap = (v, depth) => {
    if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return v;
    if (toReal.has(v)) return v;
    const d = (depth || 0) + 1;
    if (d <= 4 && isArray(v)) { const out = []; for (let i = 0; i < v.length; i++) out.push(wrap(v[i], d)); return out; }
    if (d <= 4 && typeof v === 'object') {
      const proto = getProto(v);
      if (proto === O.prototype || proto === null) {
        const out = {};
        for (const k of keysOf(v)) define(out, k, { value: wrap(v[k], d), enumerable: true, writable: true, configurable: true });
        return out;
      }
    }
    let p = toProxy.get(v);
    if (!p) { holds(v); p = makeProxy(v); toProxy.set(v, p); toReal.set(p, v); }
    return p;
  };
  const toNative = (a) => {
    if (a === null || (typeof a !== 'object' && typeof a !== 'function')) return a;
    const r = toReal.get(a);
    // ラップ済みの関数はそのまま渡す。誰が呼び出しても検査を通す。
    if (r !== undefined) return typeof r === 'function' ? a : r;
    // モデル自身が渡すコールバック。ネイティブ関数から渡される値を先にラップする。
    if (typeof a === 'function') return function () { const xs = []; for (let i = 0; i < arguments.length; i++) xs.push(wrap(arguments[i])); return toNative(apply(a, undefined, xs)); };
    return a;
  };
  const nameOf = (f) => { try { return String(f.name || ''); } catch (e) { return ''; } };
  const call = (f, self, xs) => {
    if (!SAFE.has(f)) { if (NETWORK.has(nameOf(f))) offline(); deny('calling ' + (nameOf(f) || 'this function')); }
    const t = toNative(self);
    if (f === substringData && holds(t)) return MASK;
    const args = [];
    for (const x of xs) args.push(toNative(x));
    // セレクターと属性名は、最初に1回だけ文字列へ変換する。オブジェクトのtoStringは呼ぶたびに異なる値を返せるため、検査した値をそのままブラウザへ渡す。
    if (SELECTORS.has(f) && args.length) {
      args[0] = String(args[0]);
      if (selectsByValue(args[0])) throw fail('invalid_selector', 'selecting by the value attribute is not allowed; read the value property instead');
    }
    const at = ATTRS.get(f);
    if (at !== undefined && at < args.length) args[at] = String(args[at]);
    if (at !== undefined && secret(t) && String(args[at]).toLowerCase() === 'value') { const x = apply(f, t, args); return x ? MASK : x; }
    return wrap(apply(f, t, args));
  };
  const changing = () => deny('changing the page');
  // 関数のラッパーの土台には、bind済みの通常関数を使う。newで呼べるのでconstructへ到達し、禁止理由を明示できる。独自のprototypeを持たず、Proxyの制約にも抵触しない。
  const makeProxy = (v) => new Proxy(typeof v === 'function' ? apply(fnBind, function () {}, [undefined]) : {}, {
    get: (_, k) => wrap(readProp(v, k)),
    set: changing, defineProperty: changing, deleteProperty: changing, setPrototypeOf: changing, preventExtensions: changing,
    getPrototypeOf: () => null,
    has: (_, k) => k in v,
    ownKeys: () => { const out = []; for (const k of ownKeys(v)) if (!(typeof k === 'string' && DENIED.has(k))) out.push(k); return out; },
    getOwnPropertyDescriptor: (_, k) => {
      if (typeof k === 'string' && DENIED.has(k)) return undefined;
      const d = getDesc(v, k);
      return d ? { value: wrap(readProp(v, k)), writable: false, enumerable: d.enumerable, configurable: true } : undefined;
    },
    apply: (_, self, xs) => call(v, self, xs),
    construct: () => { if (typeof v === 'function' && NETWORK.has(nameOf(v))) offline(); deny('creating page objects'); },
  });

  // モデルが使える名前は、純粋なECMAScript組み込みとPROVIDEDだけ。それ以外のグローバル名はすべて隠す。
  const PROVIDED = ${js(PROVIDED)};
  const ALLOWED = new Set(${js(PageScriptProtocol.ALLOWED_GLOBALS)}.concat(PROVIDED));
  const RESERVED = new Set(${js(PageScriptProtocol.RESERVED)});
  const hidden = new Set(['globalThis', 'self', 'top', 'parent', 'frames', 'opener', '__mikaReadRun', '__mikaReadStop']);
  for (let o = G; o; o = getProto(o)) for (const k of names(o)) {
    if (/^[A-Za-z_$][\w$]*$/.test(k) && !ALLOWED.has(k) && !RESERVED.has(k)) hidden.add(k);
  }
  const shadow = [...hidden];
  const blanks = shadow.map(() => undefined);
  const params = shadow.concat(PROVIDED);
  const state = {};
  const pageWindow = wrap(G), pageDocument = wrap(doc), pageLocation = wrap(loc), computedStyle = wrap(G.getComputedStyle);
  let generation = 0;
  const STACK_LINE = /mika-read\.js:(\d+):\d+/;
  const KNOWN = new Set(['read_only', 'invalid_selector', 'blocked_network', 'limit_exceeded', 'network_error', 'too_large', 'invalid_arguments', 'script_ended']);
  const failure = (e) => {
    const m = STACK_LINE.exec(String((e && e.stack) || ''));
    const line = m ? Number(m[1]) - $LINE_OFFSET : 0;
    const code = e && typeof e.code === 'string' && KNOWN.has(e.code) ? e.code : 'script_error';
    return { ok: false, error: code, message: String((e && e.message) || e), line: line > 0 ? line : null };
  };

  fixed(G, '__mikaReadStop', () => { generation++; });
  fixed(G, '__mikaReadRun', (code, args, lim) => {
    const gen = ++generation;
    const live = () => { if (gen !== generation) throw fail('script_ended', 'the script was stopped'); };
    // ネットワークには接続しない。ページの背後のデータが必要なら、ページ自身が受信した応答をweb_captureで読む。
    const pageFetch = () => { live(); offline(); };
    const sleep = (ms) => { live(); return new Promise((done) => timer(done, Math.max(0, Math.min(Number(ms) || 0, lim.sleepMs)))); };
    let fn;
    try {
      fn = new AsyncFunction(...params, "'use strict';\nreturn (\n" + code + '\n);\n//# sourceURL=$SOURCE_URL');
    } catch (e) {
      try {
        fn = new AsyncFunction(...params, "'use strict';\n\n" + code + '\n//# sourceURL=$SOURCE_URL');
      } catch (e2) {
        return Promise.resolve(failure(e2));
      }
    }
    const input = args == null ? undefined : parseJson(stringify(args));
    const ice2 = (x) => { if (x && typeof x === 'object') { for (const k of keysOf(x)) ice2(x[k]); freeze(x); } return x; };
    let running;
    try {
      running = apply(fn, undefined, blanks.concat([pageDocument, pageWindow, pageLocation, pageFetch, state, ice2(input), sleep, computedStyle, URL_, G.URLSearchParams]));
    } catch (e) {
      return Promise.resolve(failure(e));
    }
    return running.then((v) => {
      let s;
      try { s = v === undefined ? 'null' : stringify(v); } catch (e) { return { ok: false, error: 'script_error', message: 'the result is not JSON-serializable: ' + String((e && e.message) || e), line: null }; }
      if (s === undefined) s = 'null';
      const max = lim.resultChars;
      return { ok: true, json: s.length > max ? s.slice(0, max) : s, truncated: s.length > max };
    }, failure);
  });
})();
//# sourceURL=$PRELUDE_URL
""".trimStart()

    /** 読み取りスクリプトの式を 1 回実行します (読み取りワールドで評価し、Promise を待機し、値で返します)。 [args] は site コマンドのパラメータです。 */
    fun run(code: String, args: JsonObject?, resultChars: Int): String =
        "globalThis.__mikaReadRun(${JsonPrimitive(code)}, ${args ?: "null"}, " +
            "{resultChars: $resultChars, sleepMs: $READ_SLEEP_MAX_MS})"

    /** 制限時間に達すると、まだ実行中のスクリプトの後のスリープは失敗します。 */
    const val STOP = "globalThis.__mikaReadStop && globalThis.__mikaReadStop()"

    /** ワールドがロードされるときに、ページのソース (location.origin) を返します。そうでない場合は null です。 */
    const val PROBE = "typeof globalThis.__mikaReadRun === 'function' ? String(location.origin) : null"

    /** 開始コール スタック (非同期親チェーンを含む) に読み取りスクリプトまたはそのプレスクリプトがあります。 */
    fun fromScript(params: JsonObject): Boolean {
        var stack = (params["initiator"] as? JsonObject)?.get("stack") as? JsonObject
        var depth = 0
        while (stack != null && depth++ < 32) {
            val frames = stack["callFrames"] as? JsonArray
            if (frames != null && frames.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull in SCRIPT_URLS }) return true
            stack = stack["parent"] as? JsonObject
        }
        return false
    }

    private val SCRIPT_URLS = setOf(SOURCE_URL, PRELUDE_URL)

    /**
     * 結論: `Network.requestWillBeSent` は読み取りスクリプト (またはそのプレスクリプト) によって送信されるリクエストであり、説明を返します。 スクリプトの読み取りはネットワークに触れず、どのリクエストも機能しません。
     * はスクリプト読み取りによって送信されず、null です。
     */
    fun requestViolation(params: JsonObject): String? {
        if (!fromScript(params)) return null
        val request = params["request"] as? JsonObject
        val url = (request?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val method = (request?.get("method") as? JsonPrimitive)?.contentOrNull ?: "GET"
        return "the script sent a $method request (${url.take(200)}); $NETWORK_MESSAGE"
    }

    /** `scheme://host[:port]`、デフォルトのポートは書き込まれません。読み取られない場合は null です。 */
    fun originOf(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = u.port.takeIf { it >= 0 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    /**
     * `__mikaReadRun` 値→結果によって返されるオブジェクト。その結果、Web データのルール (名前フィールド、JWT、長く複雑な文字列、キー) に従ってまず感度が解除され、その後 [EVAL_RESULT_MAX_CHARS] にカットされます。
     * [fresh]: 今回は、新しい読み取りワールドが作成されます (状態に保存されていた以前の読み取りワールドは失われます)。
     */
    fun result(value: JsonObject?, fresh: Boolean): EvalJs {
        value ?: return EvalJs.Failed("script_error", "the script produced no readable result")
        fun str(k: String) = (value[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((value["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val line = (value["line"] as? JsonPrimitive)?.intOrNull
            return EvalJs.Failed(str("error") ?: "script_error", (line?.let { "line $it: " } ?: "") + str("message").orEmpty())
        }
        val json = Redactor.pageData(str("json") ?: "null")
        val truncated = (value["truncated"] as? JsonPrimitive)?.booleanOrNull == true || json.length > EVAL_RESULT_MAX_CHARS
        return EvalJs.Ok(json.take(EVAL_RESULT_MAX_CHARS), truncated, fresh)
    }
}
