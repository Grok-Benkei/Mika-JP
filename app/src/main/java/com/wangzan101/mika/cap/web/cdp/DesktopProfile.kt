package com.wangzan101.mika.cap.web.cdp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** カーネルによって報告されるブランドとバージョン (UA-CH のブランド/fullVersionList はそれぞれ 1 つのバージョンを取ります)。 Web ページの */
data class Brand(val brand: String, val major: String, val full: String)

/**
 * デスクトップ バージョンのアイデンティティ: Linux デスクトップの UA および UA-CH、カーネル バージョン番号はネイティブ WebView に忠実に従い、ブランドはカーネル独自のものに従います (Android WebView は削除されています)。
 * ページで読み取ることができるものはすべて、この ID: UA / UA-CH / navigator.platform と一致する必要があり、ビューポート、画面、ピクセル比はシミュレーション デバイス ([userAgentOverride]、
 * [metricsOverride]、[emulationCalls]);シミュレーションを設定できません (タッチ機能、ポインタおよびホバー メディア クエリ、ウィンドウ フレーム、WebView 固有のトレース)
 * は、ドキュメントの先頭に挿入された [identityScript] によって埋められます。タイムゾーンは変更されず、ローカルタイムゾーンが使用されます。言語のデフォルトは、ユーザーが Mega で言語を選択するときにも使用されます。
 * リクエスト ヘッダーと navigator. language は 1 つに置き換えられます ([PageLanguage] を参照)。
 * モバイル バージョン サイトは変更されず、マシンの実際の携帯電話 ID が使用されます。
 */
object DesktopProfile {
    /** デスクトップ バージョンのビューポート幅 (CSS ピクセル)。 */
    const val WIDTH = 1280
    const val FALLBACK_HEIGHT = 800
    /** デスクトップ タブのホスト表示: デスクトップ ビューポートを 1 CSS ピクセルから 1 ピクセルに描画します。 */
    const val HOST_WIDTH = WIDTH
    const val HOST_HEIGHT = 800
    const val HOST_DPI = 160
    const val NAVIGATOR_PLATFORM = "Linux x86_64"
    const val PLATFORM = "Linux"
    /** デスクトップ ブラウザ ウィンドウ内の Web ページの外側の部分 (タブ バー、アドレス バー) の高さ: ウィンドウ フレームは、Web ページのビューポートよりもはるかに高くなります。 */
    const val WINDOW_CHROME_PX = 85
    private const val WEBVIEW_BRAND = "Android WebView"
    private val CHROME = Regex("""Chrome/(\d+)((?:\.\d+)*)""")

    /** カーネルのデフォルト UA からバージョンを取得します: (メイン バージョン、フル バージョン)。 慣例により、 */
    fun version(defaultUa: String): Pair<String, String>? =
        CHROME.find(defaultUa)?.let { m -> m.groupValues[1] to (m.groupValues[1] + m.groupValues[2]) }

    /** UA のバージョンはメイン バージョンのみを書き込みます (残りは 0)。 */
    fun userAgent(major: String): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

    /** カーネルによって指定されたブランドから Android WebView を削除します。プレースホルダー ブランドが利用できない場合は、Chromium を使用して追加します。 */
    fun brands(engine: List<Brand>?, major: String, full: String): List<Brand> =
        engine?.filter { it.brand != WEBVIEW_BRAND }?.takeIf { l -> l.any { it.brand == "Chromium" } }
            ?: listOf(Brand("Chromium", major, full), Brand("Not_A Brand", "24", "24.0.0.0"))

    /**
     * `Emulation.setUserAgentOverride`のパラメータ。ここには acceptLanguage はありません。システムに従っている場合、言語はユーザーのもののままです。 ユーザーがMikaで言語を選択すると、
     * は[PageLanguage.withLanguage]によって追加されます。
     */
    fun userAgentOverride(major: String, full: String, brands: List<Brand>): JsonObject = buildJsonObject {
        put("userAgent", userAgent(major))
        put("platform", NAVIGATOR_PLATFORM)
        put("userAgentMetadata", metadata(brands, full, PLATFORM, "", "x86", "", mobile = false, bitness = "64", wow64 = false))
    }

    /** CDP の `Emulation.UserAgentMetadata` (UA-CH);デスクトップ ID は、モバイル バージョンのデフォルト ID ([PageLanguage.mobileOverride]) と共有されます。 */
    fun metadata(
        brands: List<Brand>, fullVersion: String, platform: String, platformVersion: String,
        architecture: String, model: String, mobile: Boolean, bitness: String, wow64: Boolean,
    ): JsonObject = buildJsonObject {
        put("brands", JsonArray(brands.map { b -> buildJsonObject { put("brand", b.brand); put("version", b.major) } }))
        put("fullVersionList", JsonArray(brands.map { b -> buildJsonObject { put("brand", b.brand); put("version", b.full) } }))
        put("fullVersion", fullVersion)
        put("platform", platform)
        put("platformVersion", platformVersion)
        put("architecture", architecture)
        put("model", model)
        put("mobile", mobile)
        put("bitness", bitness)
        put("wow64", wow64)
    }

    /** ビューポートの高さはホストのアスペクト比に従ってデスクトップの幅まで拡大され、ページ全体がホストをちょうど埋めます。ホストのサイズが不明な場合は 800 を使用します。 */
    fun height(viewW: Int, viewH: Int): Int =
        if (viewW > 0 && viewH > 0) (WIDTH.toDouble() * viewH / viewW).roundToInt() else FALLBACK_HEIGHT

    /**
     * シミュレーション画面のスケーリング: 時間短縮なし、1 CSS ピクセルが [density] (シミュレーションなしのページの devicePixelRatio) ビュー ピクセルを占有します。
     * デスクトップ幅のページでは、左側のセクションのみが表示される場合があります。ページ全体に縮小 [WIDTH] はビューの幅をカバーするだけです。ビュー ピクセルと CSS ピクセルの比率は viewW / WIDTH です。
     * (クリックすると画面変換が表示されます)。デスクトップ ホスト上のビュー幅は [WIDTH] で、1 対 1 です。
     */
    fun fitScale(viewW: Int, density: Float): Double? =
        if (viewW > 0 && density > 0f) viewW / (WIDTH * density.toDouble()) else null

    /**
     * `Emulation.setDeviceMetricsOverride` パラメータ: デスクトップの幅、ピクセル比 1. 非モバイル端末。 [scale] は、ページ全体をホスト幅まで縮小して表示します。
     * 画面は、「ウィンドウが画面全体を覆う」ように計算されます。幅はビューポートと同じで、高さはビューポートにブラウザ ウィンドウの外枠を加えたもの ([WINDOW_CHROME_PX])。これは、スクリプトで指定された externalHeight と一致します。
     */
    fun metricsOverride(viewW: Int, viewH: Int, scale: Double?): JsonObject = buildJsonObject {
        val h = height(viewW, viewH)
        put("width", WIDTH)
        put("height", h)
        put("deviceScaleFactor", 1)
        put("mobile", false)
        put("screenWidth", WIDTH)
        put("screenHeight", h + WINDOW_CHROME_PX)
        put("screenOrientation", buildJsonObject {
            val landscape = WIDTH >= h + WINDOW_CHROME_PX
            put("type", if (landscape) "landscapePrimary" else "portraitPrimary")
            put("angle", 0)
        })
        scale?.let { put("scale", it) }
    }

    /**
     * デスクトップ バージョンは、UA とビューポートに加えてシミュレーション コマンド (メソッド、パラメーター) も送信します。タッチ シミュレーションはオンになっておらず、マウスはタッチ イベントを派生しません。
     * ポインターおよびマウス ホバー レポート (カーネルがこれらのメディア機能を認識しない場合、この項目は機能せず、[identityScript] が処理します)。
     * 各商品個別でのお届けとなります。いずれかのアイテムが拒否されても、他のアイテムには影響しません。
     */
    fun emulationCalls(): List<Pair<String, JsonObject>> = listOf(
        "Emulation.setTouchEmulationEnabled" to buildJsonObject { put("enabled", false) },
        "Emulation.setEmitTouchEventsForMouse" to buildJsonObject { put("enabled", false) },
        "Emulation.setEmulatedMedia" to buildJsonObject {
            put("features", JsonArray(DESKTOP_MEDIA.map { (n, v) -> buildJsonObject { put("name", n); put("value", v) } }))
        },
    )

    /**
     * デスクトップ バージョンのビューポートにはどれが使用されますか: エージェント ラベルはシミュレーションを使用して、ページ全体 [WIDTH] をビューにインデントします ([metricsOverride]、画面の読み取りと入力座標は [scale] に従って変換されます)。
     * ユーザーが見て自分の手でクリックするラベル ([handOperated]) は、ビューポート シミュレーションを追加せず (それをキャンセルし)、WebView 独自のワイド ビューポートと概要モードのままにします。
     * を開くと、ページ全体がインデントされ、2 本の指で画面を拡大できます。シミュレーションではズームがロックされ、ズームインできません。UA、UA-CH、およびアイデンティティ パッチ ラベルは同じです。
     */
    fun viewportCall(handOperated: Boolean, viewW: Int, viewH: Int, scale: Double?): Pair<String, JsonObject> =
        if (handOperated) "Emulation.clearDeviceMetricsOverride" to JsonObject(emptyMap())
        else "Emulation.setDeviceMetricsOverride" to metricsOverride(viewW, viewH, scale)

    /** のモバイル バージョンに切り替えると、[emulationCalls] で変更されたメディア プロパティはキャンセルされます (空のリストは、キャンセルされていることを意味します)。 */
    fun clearMediaCall(): Pair<String, JsonObject> =
        "Emulation.setEmulatedMedia" to buildJsonObject { put("features", JsonArray(emptyList())) }

    private val DESKTOP_MEDIA = listOf("pointer" to "fine", "any-pointer" to "fine", "hover" to "hover", "any-hover" to "hover")

    /**
     * デスクトップ バージョンは、シミュレーションでは設定できない以下のもののみを補うために、各ドキュメント (フレームを含む) の先頭にパッチを挿入します。
     * - タッチ: navigator.maxTouchPoints は 0、ontouch* プロセッサー属性を削除します。`document.createEvent('TouchEvent')` はデスクトップのようにサポートされていません。
     * - ポインターとホバー: matchMedia のポインター / 任意のポインター / ホバー / 任意のホバー。マウスを押して応答します (残りはそのままカーネルに任せます)。
     *   返されたオブジェクトのメディアは、ページによって指定された元のテキストのままです (プロトタイプのゲッターに変更され、オブジェクトには多くの属性がありません)。
     * - ウィンドウ フレーム: externalWidth = innerWidth、outerHeight = innerHeight + [WINDOW_CHROME_PX];
     * - navigator.platform は [NAVIGATOR_PLATFORM] (DevTools を使用しない UA にも適しています)。 navigator.webdriver は false です。
     *   navigator.connection は、携帯電話でのみ使用できるタイプを削除します。 window.chrome がない場合は、空のオブジェクトを埋めます (デスクトップ ブラウザには常に存在します)。
     * 置き換えられたゲッターは、最初に元のゲッターを調整し (レシーバーが間違っている場合でも不正な呼び出しを報告します)、その後新しい値を与えます。置き換えられたメソッドは元の長さを保持します。
     * ソースコードは組み込み関数として表示されており、他のウィンドウのtoStringも同様に見えます（各ウィンドウのパッチソースコードは同じなので、ソースコードから特定してください）。
     * ページ スクリプトによって作成された同じオリジン フレームワーク (about:blank、srcdoc) は、必ずしもドキュメントを通じて注入を開始するとは限りません。最初に contentWindow / contentDocument を通じて注入されるときです。
     * が取得されると、同じコピーがフレームワーク自身の環境に追加されます (フレームワークが eval を禁止している場合は、返されてこの環境に追加されます)。接触点が 0 であると報告されたウィンドウは埋まらなくなります。
     * は、UA、言語、タイムゾーン、ハードウェアパラメーターには影響しません。
     */
    fun identityScript(): String = IDENTITY_SCRIPT
        .replace("__PLATFORM__", NAVIGATOR_PLATFORM)
        .replace("__CHROME_PX__", WINDOW_CHROME_PX.toString())

    private val IDENTITY_SCRIPT = """
(function desktopIdentity(W) {
  'use strict';
  try { if (!W.navigator || W.navigator.maxTouchPoints === 0) return; } catch (e) { return; }
  var FP = W.Function.prototype, readSource = FP.toString, ours = new Set(), lists = new WeakMap();
  function ownFn(fn) { ours.add(readSource.call(fn)); return fn; }
  function accessor(target, prop, answer) {
    var old = target && Object.getOwnPropertyDescriptor(target, prop);
    if (!old || typeof old.get !== 'function' || !old.configurable) return;
    var realGet = old.get;
    var get = ownFn(Object.getOwnPropertyDescriptor({ get [prop]() { return answer(realGet.call(this), this); } }, prop).get);
    Object.defineProperty(target, prop, { get: get, set: old.set, enumerable: old.enumerable, configurable: true });
  }
  function method(target, prop, body) {
    var old = target && Object.getOwnPropertyDescriptor(target, prop);
    if (!old || typeof old.value !== 'function' || !old.configurable) return;
    var real = old.value;
    var fn = ownFn({ [prop]() { return body.call(this, real, arguments); } }[prop]);
    Object.defineProperty(fn, 'length', { value: real.length, configurable: true });
    Object.defineProperty(target, prop, { value: fn, writable: old.writable, enumerable: old.enumerable, configurable: true });
  }
  function constant(v) { return function () { return v; }; }

  method(FP, 'toString', function (real, args) {
    var text = real.apply(this, args);
    return ours.has(text) ? 'function ' + this.name + '() { [native code] }' : text;
  });

  var nav = W.Navigator && W.Navigator.prototype;
  accessor(nav, 'maxTouchPoints', constant(0));
  accessor(nav, 'platform', constant('__PLATFORM__'));
  accessor(nav, 'webdriver', constant(false));

  [W, W.Document && W.Document.prototype, W.Element && W.Element.prototype,
   W.HTMLElement && W.HTMLElement.prototype, W.SVGElement && W.SVGElement.prototype].forEach(function (o) {
    if (!o) return;
    ['ontouchstart', 'ontouchend', 'ontouchmove', 'ontouchcancel'].forEach(function (p) {
      var d = Object.getOwnPropertyDescriptor(o, p);
      if (d && d.configurable) delete o[p];
    });
  });
  method(W.Document && W.Document.prototype, 'createEvent', function (real, args) {
    if (args.length && /^\s*touchevents?\s*${'$'}/i.test(String(args[0]))) {
      throw new W.DOMException("Failed to execute 'createEvent' on 'Document': The provided event type ('" + args[0] + "') is invalid.", 'NotSupportedError');
    }
    return real.apply(this, args);
  });

  var FEATURE = /\(\s*(any-)?(pointer|hover)\s*(?::\s*([a-z-]+)\s*)?\)/gi;
  var TRUE_Q = '(min-width: 0px)', FALSE_Q = '(max-width: 0px) and (min-width: 1px)';
  function asMouse(all, any, feature, value) {
    if (!value) return TRUE_Q;
    value = value.toLowerCase();
    return (feature.toLowerCase() === 'pointer' ? value === 'fine' : value === 'hover') ? TRUE_Q : FALSE_Q;
  }
  method(W, 'matchMedia', function (real, args) {
    if (!args.length) return real.apply(this, args);
    var query = String(args[0]);
    FEATURE.lastIndex = 0;
    if (!FEATURE.test(query)) return real.apply(this, args);
    var list = real.call(this, query.replace(FEATURE, asMouse));
    lists.set(list, real.call(this, query).media);
    return list;
  });
  accessor(W.MediaQueryList && W.MediaQueryList.prototype, 'media', function (v, self) { return lists.has(self) ? lists.get(self) : v; });

  accessor(W, 'outerWidth', function () { return W.innerWidth; });
  accessor(W, 'outerHeight', function () { return W.innerHeight + __CHROME_PX__; });

  var conn = W.NetworkInformation && W.NetworkInformation.prototype;
  ['type', 'ontypechange'].forEach(function (p) {
    var d = conn && Object.getOwnPropertyDescriptor(conn, p);
    if (d && d.configurable) delete conn[p];
  });

  if (!('chrome' in W)) Object.defineProperty(W, 'chrome', { value: {}, writable: true, enumerable: true, configurable: true });

  // スクリプトで作成した about:blank / srcdoc のフレームには、文書開始時の注入が行われない場合がある。初めて取得した時点で同じ内容を補う。
  var self = desktopIdentity, source = readSource.call(self);
  function adopt(child) {
    try { if (!child || child === W || child.navigator.maxTouchPoints === 0) return; } catch (e) { return; }
    try { child.Function('W', 'return (' + source + ')(W);')(child); } catch (e) { try { self(child); } catch (e2) {} }
  }
  var frame = W.HTMLIFrameElement && W.HTMLIFrameElement.prototype;
  accessor(frame, 'contentWindow', function (v) { adopt(v); return v; });
  accessor(frame, 'contentDocument', function (v) { if (v) adopt(v.defaultView); return v; });
})(window);
"""
}
