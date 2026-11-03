package com.wangzan101.mika.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import com.wangzan101.mika.ui.theme.LightColors
import com.wangzan101.mika.ui.theme.MikaColors

/** の回答の Markdown によって解析された表示モデル: ブロック レベルの構造 + スタイル付きのインライン テキスト。純粋なデータであり、インターフェースに依存しません。 */
sealed interface MdBlock {
    data class Para(val text: AnnotatedString) : MdBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Code(val info: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    /** [start] は null で、順序なしリストです。各リスト項目はブロックのセットです。 */
    data class ListBlock(val start: Int?, val tight: Boolean, val items: List<List<MdBlock>>) : MdBlock
    /** 各行のセルの数がヘッダーの列数に達します。 */
    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdBlock
    data object Rule : MdBlock
}

object MarkdownModel {
    /** 参照やリストがどれほど深くネストされていても、深すぎる再帰を避けるためにそれらはプレーン テキストとして表示されます。 */
    private const val MAX_DEPTH = 12
    private val LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

    /** インライン コードとリンクのスタイル。色は暗い色と明るい色に従い、表示される現在のカラー マッチングによって与えられます。 */
    class Styles(val code: SpanStyle, val link: TextLinkStyles) {
        companion object {
            fun of(c: MikaColors) = Styles(
                code = SpanStyle(fontFamily = FontFamily.Monospace, background = c.fill),
                link = TextLinkStyles(SpanStyle(color = c.accent)),
            )

            val Light = of(LightColors)
        }
    }

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    /** 解析が失敗した場合 (書きかけのストリーミング テキストによる事故を含む)、段落全体がプレーン テキストとして表示されます。 */
    fun parse(markdown: String, styles: Styles = Styles.Light): List<MdBlock> = try {
        blocks(parser.parse(markdown), 0, styles)
    } catch (e: Exception) {
        plain(markdown)
    } catch (e: StackOverflowError) {
        plain(markdown)
    }

    /** ストリーミング スニペットは文法の途中で停止できます。表示時には末尾のみが完成しており、実際の答えは変更されません。 */
    fun parseStreaming(markdown: String, styles: Styles = Styles.Light): List<MdBlock> = parse(StreamingMarkdown.preview(markdown), styles)

    /**
     * は、Markdown 記号 (コピーと引用用) を削除した後のプレーン テキストに答えました。段落、タイトル、コードは元のテキストと同じで、各ブロックの間には空白行があります。
     * リスト内の各項目は 1 行にあり、その前に「・」または「1.」が付きます。引用の各行の前には「>」が付けられます。表の各行は 1 行であり、セル間にはタブが使用されます。区切り文字は必要ありません。
     */
    fun toPlainText(markdown: String): String = blocksText(parse(markdown)).trim()

    private fun blocksText(blocks: List<MdBlock>, sep: String = "\n\n"): String = blocks.mapNotNull { b ->
        when (b) {
            is MdBlock.Para -> b.text.text
            is MdBlock.Heading -> b.text.text
            is MdBlock.Code -> b.code
            is MdBlock.Quote -> blocksText(b.blocks).lines().joinToString("\n") { "> $it".trimEnd() }
            is MdBlock.ListBlock -> b.items.mapIndexed { i, item ->
                val mark = if (b.start == null) "• " else "${b.start + i}. "
                mark + blocksText(item, "\n").lines().joinToString("\n" + " ".repeat(mark.length))
            }.joinToString("\n")
            is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString("\t") { it.text } }
            MdBlock.Rule -> null
        }?.takeIf { it.isNotBlank() }
    }.joinToString(sep)

    private fun plain(s: String): List<MdBlock> = if (s.isBlank()) emptyList() else listOf(MdBlock.Para(AnnotatedString(s.trim())))

    private fun children(n: Node): Sequence<Node> = generateSequence(n.firstChild) { it.next }

    private fun blocks(parent: Node, depth: Int, s: Styles): List<MdBlock> = children(parent).mapNotNull { block(it, depth, s) }.toList()

    private fun block(n: Node, depth: Int, s: Styles): MdBlock? {
        if (depth > MAX_DEPTH) return MdBlock.Para(AnnotatedString(plainText(n)))
        return when (n) {
            is Paragraph -> MdBlock.Para(inlines(n, s))
            is org.commonmark.node.Heading -> MdBlock.Heading(n.level, inlines(n, s))
            is FencedCodeBlock -> MdBlock.Code(n.info.orEmpty().trim(), n.literal.orEmpty().trimEnd('\n'))
            is IndentedCodeBlock -> MdBlock.Code("", n.literal.orEmpty().trimEnd('\n'))
            is HtmlBlock -> MdBlock.Para(AnnotatedString(n.literal.orEmpty().trimEnd('\n')))
            is BlockQuote -> MdBlock.Quote(blocks(n, depth + 1, s))
            is ListBlock -> MdBlock.ListBlock(
                start = (n as? OrderedList)?.let { it.markerStartNumber ?: 1 },
                tight = n.isTight,
                items = children(n).filterIsInstance<ListItem>().map { blocks(it, depth + 1, s) }.toList(),
            )
            is TableBlock -> table(n, s)
            is ThematicBreak -> MdBlock.Rule
            is LinkReferenceDefinition -> null
            else -> plainText(n).takeIf { it.isNotBlank() }?.let { MdBlock.Para(AnnotatedString(it)) }
        }
    }

    private fun table(t: TableBlock, s: Styles): MdBlock.Table {
        val rows = mutableListOf<List<AnnotatedString>>()
        var header: List<AnnotatedString> = emptyList()
        for (section in children(t)) for (row in children(section).filterIsInstance<TableRow>()) {
            val cells = children(row).filterIsInstance<TableCell>().map { inlines(it, s) }.toList()
            if (section is TableHead && header.isEmpty()) header = cells else rows += cells
        }
        val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
        fun pad(r: List<AnnotatedString>) = r + List(cols - r.size) { AnnotatedString("") }
        return MdBlock.Table(pad(header), rows.map(::pad))
    }

    private fun inlines(parent: Node, s: Styles): AnnotatedString = buildAnnotatedString { appendInlines(parent, s) }

    private fun AnnotatedString.Builder.appendInlines(parent: Node, s: Styles) {
        for (n in children(parent)) when (n) {
            is Text -> append(n.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Code -> withStyle(s.code) { append(n.literal) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(n, s) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInlines(n, s) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(n, s) }
            is Link -> link(n.destination, s) { appendInlines(n, s) }
            // チャット内に画像は表示されず、代替テキストのみが残ります（公開可能なアドレスがあればリンク化されます）
            is Image -> link(n.destination, s) { if (n.firstChild != null) appendInlines(n, s) else append(n.destination.orEmpty()) }
            is HtmlInline -> append(n.literal)
            else -> appendInlines(n, s)
        }
    }

    private inline fun AnnotatedString.Builder.link(dest: String?, s: Styles, content: AnnotatedString.Builder.() -> Unit) {
        val url = dest?.trim().orEmpty()
        if (url.substringBefore(':', "").lowercase() in LINK_SCHEMES) withLink(LinkAnnotation.Url(url, s.link)) { content() } else content()
    }

    private fun plainText(n: Node): String = buildString {
        fun walk(x: Node) {
            when (x) {
                is Text -> append(x.literal)
                is Code -> append(x.literal)
                is FencedCodeBlock -> append(x.literal)
                is IndentedCodeBlock -> append(x.literal)
                is HtmlBlock -> append(x.literal)
                is HtmlInline -> append(x.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                else -> {
                    children(x).forEach(::walk)
                    if (x is Paragraph || x is org.commonmark.node.Heading) append('\n')
                }
            }
        }
        walk(n)
    }.trim()
}
