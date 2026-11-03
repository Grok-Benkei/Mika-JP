package com.wangzan101.mika.core.cap.web

/**
 * ページに渡された CSS セレクターは、value 属性に従って要素を選択することを許可されていません: `input[value^="h"]` パスワード ボックスと隠しフィールドの値を 1 つずつ推測してみてください。 リテラルのみを読み取ると
 * は見逃されるため、まず CSS 読み取りセレクターの規則に従ってブラウザーで表示される状態に戻してから検索します。コメントを削除してエスケープを解除します (`v\61 lue` は値です)。 引用符で囲まれた段落全体
 * (属性名は引用符で囲まれて記述されず、引用符で囲まれたコメント マークと `[value` はカウントされません)。属性名の前に名前空間を付けることができます (`*|value`、`|value`)。
 * 復元時にさらに認識することをお勧めします。エスケープされた角括弧 (クラス名 `.\5b value`) も、値によって選択されたものとしてカウントされます。
 * と `url(` は値の選択としてカウントされます: url( `)` までのマークの後に引用符はありません。コメント マークと引用符内のコメントはコメントでも文字列でも始まりません。
 * の上記の復元は一致しません。 `:is()` は間違った項目を破棄するだけで、後続の `[value` は引き続き有効です。セレクターでは使用されません。
 * web_script の mika.query は Kotlin ([selectsByValue]) で検索されます。読み取り専用スクリプトの検索メソッドは、同じルール セット ([JS]) の JS バージョンを使用してページ内で呼び出されます。
 */
object SelectorPolicy {
    fun selectsByValue(selector: String): Boolean = plain(selector).let { URL_FUNCTION.containsMatchIn(it) || BY_VALUE.containsMatchIn(it) }

    /** `[`、オプションの名前空間、値。名前にこれ以上の単語は続きません。 CSS の空白文字 (スペース、タブ、改行) のみが認識されます。これは JS バージョンと同じです。 復元後に */
    private val BY_VALUE = Regex("""\[[ \t\n]*(?:[^ \t\n\[\]|]*[ \t\n]*\|[ \t\n]*)?value(?![\w\x{80}-\x{10FFFF}-])""", RegexOption.IGNORE_CASE)

    /** が表示されます。 `url(` (名前はエスケープで綴ることができます): CSS はここで URL マークを開始します。 */
    private val URL_FUNCTION = Regex("""url\(""", RegexOption.IGNORE_CASE)

    /** は CSS の発音に従って復元されます。改行は最初に \n に統合されます。コメントはスペースに変更されます。引用符で囲まれた段落全体が `""` に変更されます。 「escape」はそれが表す言葉に変わります。 */
    internal fun plain(selector: String): String {
        val s = selector.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n')
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2
                    out.append(' ')
                }
                c == '"' || c == '\'' -> {
                    i++
                    // 引用符内: バックスラッシュと改行は行継続であり、他のエスケープは通常どおりに使用されます。エスケープされていない改行は段落を終了します (ブラウザーにも同じことが当てはまります)
                    while (i < s.length && s[i] != c && s[i] != '\n') {
                        i = when {
                            s[i] != '\\' -> i + 1
                            i + 1 < s.length && s[i + 1] == '\n' -> i + 2
                            else -> escape(s, i).second
                        }
                    }
                    if (i < s.length && s[i] == c) i++
                    out.append("\"\"")
                }
                c == '\\' && (i + 1 >= s.length || s[i + 1] != '\n') -> {
                    val (ch, next) = escape(s, i)
                    out.append(ch)
                    i = next
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** [i] はバックスラッシュです。エスケープされた単語と次の位置を返します。 1 ～ 6 の 16 進数 (次のスペースはエスケープとしてカウントされます)、それ以外の場合は次の単語そのもの。 */
    private fun escape(s: String, i: Int): Pair<String, Int> {
        if (i + 1 >= s.length) return "\uFFFD" to s.length
        var j = i + 1
        while (j < s.length && j - i <= 6 && s[j].isHex()) j++
        if (j == i + 1) {
            val cp = s.codePointAt(j)
            return String(Character.toChars(cp)) to j + Character.charCount(cp)
        }
        val n = s.substring(i + 1, j).toInt(16)
        val ch = if (n == 0 || n in 0xD800..0xDFFF || n > 0x10FFFF) "\uFFFD" else String(Character.toChars(n))
        return ch to if (j < s.length && s[j] in " \t\n") j + 1 else j
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** JS バージョン: 上記に対応する `selectsByValue(选择器字符串)` をページ内に 1 行ずつ定義します。 */
    val JS: String = """
  const selectsByValue = (() => {
    const HEX = /^[0-9a-fA-F]${'$'}/;
    const escape = (s, i) => {
      if (i + 1 >= s.length) return ['\ufffd', s.length];
      let j = i + 1;
      while (j < s.length && j - i <= 6 && HEX.test(s[j])) j++;
      if (j === i + 1) { const cp = s.codePointAt(j); return [String.fromCodePoint(cp), j + (cp > 0xffff ? 2 : 1)]; }
      const n = parseInt(s.slice(i + 1, j), 16);
      const ch = n === 0 || (n >= 0xd800 && n <= 0xdfff) || n > 0x10ffff ? '\ufffd' : String.fromCodePoint(n);
      return [ch, j < s.length && ' \t\n'.includes(s[j]) ? j + 1 : j];
    };
    const plain = (raw) => {
      const s = raw.replace(/\r\n?|\f/g, '\n');
      let out = '', i = 0;
      while (i < s.length) {
        const c = s[i];
        if (c === '/' && s[i + 1] === '*') { const end = s.indexOf('*/', i + 2); i = end < 0 ? s.length : end + 2; out += ' '; }
        else if (c === '"' || c === "'") {
          i++;
          while (i < s.length && s[i] !== c && s[i] !== '\n') i = s[i] !== '\\' ? i + 1 : s[i + 1] === '\n' ? i + 2 : escape(s, i)[1];
          if (s[i] === c) i++;
          out += '""';
        } else if (c === '\\' && s[i + 1] !== '\n') { const [ch, next] = escape(s, i); out += ch; i = next; }
        else { out += c; i++; }
      }
      return out;
    };
    const BY_VALUE = /\[[ \t\n]*(?:[^ \t\n\[\]|]*[ \t\n]*\|[ \t\n]*)?value(?![\w\u0080-\uffff-])/i;
    const URL_FUNCTION = /url\(/i;
    return (s) => { const p = plain(s); return URL_FUNCTION.test(p) || BY_VALUE.test(p); };
  })();
"""
}
