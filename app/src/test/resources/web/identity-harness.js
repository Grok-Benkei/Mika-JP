// は、デスクトップ ID パッチ用の最小限の「モバイル ブラウザ」環境を作成します。組み込みのゲッター/メソッドは、不正なレシーバーに遭遇したときにカーネルと同様に不正な呼び出しを報告します。
// 使用法: ノードidentity-harness.js <パッチファイル>;テスト アサーション用の JSON 行を出力します。
'use strict';
const fs = require('fs');
const vm = require('vm');

function makeRealm() {
  const ctx = vm.createContext({});
  vm.runInContext(`
    function illegal() { throw new TypeError('Illegal invocation'); }
    // 全局对象上的方法:不带接收者调用时就是全局对象
    function isWin(t) { return t === undefined || t === globalThis || (t !== null && typeof t === 'object' && 'innerWidth' in t); }
    function brand(C, name, get) {
      Object.defineProperty(C.prototype, name, { get: { get [name]() { if (!(this instanceof C)) illegal(); return get.call(this); } }['get ' + name] ||
        Object.getOwnPropertyDescriptor({ get [name]() { if (!(this instanceof C)) illegal(); return get.call(this); } }, name).get,
        enumerable: true, configurable: true });
    }
    var window = globalThis;
    function Navigator() {} brand(Navigator, 'maxTouchPoints', () => 5); brand(Navigator, 'platform', () => 'Linux armv8l');
    brand(Navigator, 'webdriver', () => false);
    var navigator = new Navigator();
    function MediaQueryList(q) { this._q = q; } brand(MediaQueryList, 'media', function () { return this._q; });
    brand(MediaQueryList, 'matches', function () {
      return /\\(pointer:\\s*coarse\\)|\\(hover:\\s*none\\)/.test(this._q) || (this._q.includes('(min-width: 0px)') && !this._q.includes('(min-width: 1px)'));
    });
    Object.defineProperty(window, 'matchMedia', { value: { matchMedia(q) { if (arguments.length < 1) throw new TypeError('1 argument required'); if (!isWin(this)) illegal(); return new MediaQueryList(String(q)); } }.matchMedia,
      writable: true, enumerable: true, configurable: true });
    function Document() {}
    Object.defineProperty(Document.prototype, 'createEvent', { value: { createEvent(t) { if (!(this instanceof Document)) illegal(); return { type: t }; } }.createEvent,
      writable: true, enumerable: true, configurable: true });
    var document = new Document();
    function Element() {} function HTMLElement() {} function SVGElement() {}
    Object.defineProperty(window, 'ontouchstart', { value: null, writable: true, enumerable: true, configurable: true });
    Object.defineProperty(HTMLElement.prototype, 'ontouchstart', { get() { return null; }, enumerable: true, configurable: true });
    var innerWidth = 1280, innerHeight = 800;
    Object.defineProperty(window, 'outerWidth', { get: Object.getOwnPropertyDescriptor({ get outerWidth() { if (!isWin(this)) illegal(); return 412; } }, 'outerWidth').get, enumerable: true, configurable: true });
    Object.defineProperty(window, 'outerHeight', { get: Object.getOwnPropertyDescriptor({ get outerHeight() { if (!isWin(this)) illegal(); return 900; } }, 'outerHeight').get, enumerable: true, configurable: true });
    function DOMException(m, n) { this.message = m; this.name = n; }
    function HTMLIFrameElement() {}
    var child = null;
    brand(HTMLIFrameElement, 'contentWindow', () => child);
  `, ctx);
  return ctx;
}

const script = fs.readFileSync(process.argv[2], 'utf8');
const top = makeRealm();
const sub = makeRealm();
// 親ページ スクリプトによって作成された新しい同一オリジン フレーム: ドキュメントなしで注入が開始されます
vm.runInContext('child = null', top);
top.child = vm.runInContext('globalThis', sub);
vm.runInContext(script, top);

const out = {};
const run = (code) => vm.runInContext(code, top);
const tryThrows = (code) => { try { run(code); return 'no throw'; } catch (e) { return e.message; } };
out.maxTouchPoints = run('navigator.maxTouchPoints');
out.platform = run('navigator.platform');
out.touchInWindow = run("'ontouchstart' in window");
out.touchInElement = run("'ontouchstart' in HTMLElement.prototype");
out.outer = run('[outerWidth, outerHeight]');
out.coarse = run("matchMedia('(pointer: coarse)').matches");
out.fine = run("matchMedia('(pointer: fine)').matches");
out.hover = run("matchMedia('(hover: hover)').matches");
out.media = run("matchMedia('(pointer: fine)').media");
out.mediaOwnProps = run("Object.getOwnPropertyNames(matchMedia('(pointer: fine)')).filter(k => k !== '_q')");
out.otherQuery = run("matchMedia('(min-width: 100px)').media");
out.createTouch = tryThrows("document.createEvent('TouchEvent')");
out.getterIllegal = tryThrows("Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints').get.call({})");
out.outerIllegal = tryThrows("Object.getOwnPropertyDescriptor(window, 'outerWidth').get.call({})");
out.mediaIllegal = tryThrows("Object.getOwnPropertyDescriptor(MediaQueryList.prototype, 'media').get.call({})");
out.methodIllegal = tryThrows("matchMedia.call({}, '(pointer: fine)')");
out.methodNoArgs = tryThrows('matchMedia()');
out.lengths = run('[matchMedia.length, document.createEvent.length, Function.prototype.toString.length, Object.getOwnPropertyDescriptor(Navigator.prototype, "maxTouchPoints").get.length]');
out.sources = run('[String(matchMedia), String(Object.getOwnPropertyDescriptor(Navigator.prototype, "maxTouchPoints").get), Function.prototype.toString.call(Function.prototype.toString)]');
out.hasPrototype = run('"prototype" in Object.getOwnPropertyDescriptor(Navigator.prototype, "maxTouchPoints").get');
out.chrome = run('typeof window.chrome');
// フレームワーク: contentWindow を取得するときに追加します。 2 つのウィンドウを互いに見ると、その toString は組み込みのように見えます。
out.childTouch = run('new HTMLIFrameElement().contentWindow.navigator.maxTouchPoints');
out.crossSource = vm.runInContext('Function.prototype.toString.call(child.matchMedia)', top);
out.crossSourceBack = vm.runInContext('Function.prototype.toString.call(Object.getOwnPropertyDescriptor(Navigator.prototype, "platform").get)', sub);
// すでにパッチが適用されているウィンドウには、再度パッチは適用されません。
vm.runInContext(script, top);
out.matchMediaStillOnce = run("matchMedia('(pointer: fine)').matches");
console.log(JSON.stringify(out));
