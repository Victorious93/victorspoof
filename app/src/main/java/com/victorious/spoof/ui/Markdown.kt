package com.victorious.spoof.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MdText
import org.commonmark.node.Code as MdCode
import org.commonmark.parser.Parser

/** Inline run. Pure Kotlin so the parser is unit-testable on the JVM. */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: String? = null)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<Span>) : MdBlock
    data class Para(val spans: List<Span>) : MdBlock
    data class ListItem(val depth: Int, val marker: String, val spans: List<Span>) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val spans: List<Span>) : MdBlock
    data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : MdBlock
    data object Rule : MdBlock
}

/** CommonMark + GFM tables -> flat block list. Images are dropped (the logo is shown natively). */
object MarkdownModel {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

    fun parse(md: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var n = parser.parse(md).firstChild
        while (n != null) { block(n, out, 0); n = n.next }
        return out
    }

    private fun children(n: Node) = generateSequence(n.firstChild) { it.next }

    private fun block(n: Node, out: MutableList<MdBlock>, depth: Int) {
        when (n) {
            is Heading -> out += MdBlock.Heading(n.level, inlines(n))
            is Paragraph -> inlines(n).takeIf { s -> s.any { it.text.isNotBlank() } }?.let { out += MdBlock.Para(it) }
            is BulletList, is OrderedList -> {
                var i = (n as? OrderedList)?.markerStartNumber ?: 1
                children(n).forEach { item ->
                    val marker = if (n is OrderedList) "${i++}." else "•"
                    var first = true
                    children(item).forEach { c ->
                        when (c) {
                            is Paragraph -> { out += MdBlock.ListItem(depth, if (first) marker else " ", inlines(c)); first = false }
                            else -> block(c, out, depth + 1)
                        }
                    }
                }
            }
            is FencedCodeBlock -> out += MdBlock.Code(n.literal.trimEnd())
            is IndentedCodeBlock -> out += MdBlock.Code(n.literal.trimEnd())
            is BlockQuote -> children(n).forEach { c -> out += MdBlock.Quote(inlines(c)) }
            is ThematicBreak -> out += MdBlock.Rule
            is TableBlock -> {
                var header = emptyList<List<Span>>(); val rows = mutableListOf<List<List<Span>>>()
                children(n).forEach { section ->
                    children(section).forEach { row ->
                        val cells = children(row).map { inlines(it) }.toList()
                        if (section is TableHead) header = cells else rows += cells
                    }
                }
                out += MdBlock.Table(header, rows)
            }
            is HtmlBlock -> Unit
            else -> children(n).forEach { block(it, out, depth) }
        }
    }

    fun inlines(n: Node): List<Span> {
        val out = mutableListOf<Span>()
        fun walk(x: Node, bold: Boolean, italic: Boolean, link: String?) {
            when (x) {
                is MdText -> out += Span(x.literal, bold, italic, false, link)
                is MdCode -> out += Span(x.literal, bold, italic, true, link)
                is StrongEmphasis -> children(x).forEach { walk(it, true, italic, link) }
                is Emphasis -> children(x).forEach { walk(it, bold, true, link) }
                is Link -> children(x).forEach { walk(it, bold, italic, x.destination) }
                is SoftLineBreak -> out += Span(" ", bold, italic, false, link)
                is HardLineBreak -> out += Span("\n", bold, italic, false, link)
                is Image -> Unit
                else -> children(x).forEach { walk(it, bold, italic, link) }
            }
        }
        children(n).forEach { walk(it, false, false, null) }
        return out
    }

    fun plain(spans: List<Span>) = spans.joinToString("") { it.text }
}

private fun annotated(spans: List<Span>, linkColor: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { s ->
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) codeBg else androidx.compose.ui.graphics.Color.Unspecified,
                color = if (s.link != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (s.link != null) TextDecoration.Underline else null,
            )
            if (s.link != null) pushStringAnnotation("url", s.link)
            withStyle(style) { append(s.text) }
            if (s.link != null) pop()
        }
    }

@Composable
fun RichText(spans: List<Span>, modifier: Modifier = Modifier, fontSize: Int = 15, weight: FontWeight? = null) {
    val uri = LocalUriHandler.current
    val text = annotated(spans, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
    @Suppress("DEPRECATION")
    ClickableText(text, modifier, style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp, fontWeight = weight,
        color = MaterialTheme.colorScheme.onBackground)) { off ->
        text.getStringAnnotations("url", off, off).firstOrNull()?.let { a ->
            if (a.item.startsWith("http")) runCatching { uri.openUri(a.item) }
        }
    }
}

@Composable
fun MarkdownView(md: String, modifier: Modifier = Modifier) {
    val blocks = androidx.compose.runtime.remember(md) { MarkdownModel.parse(md) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(blocks) { b -> MdBlockView(b) }
    }
}

@Composable
private fun MdBlockView(b: MdBlock) {
    val outline = MaterialTheme.colorScheme.outline
    when (b) {
        is MdBlock.Heading -> RichText(b.spans, Modifier.padding(top = if (b.level <= 2) 14.dp else 8.dp),
            fontSize = when (b.level) { 1 -> 26; 2 -> 20; 3 -> 17; else -> 15 }, weight = FontWeight.Bold)
        is MdBlock.Para -> RichText(b.spans)
        is MdBlock.ListItem -> Row(Modifier.padding(start = (8 + 16 * b.depth).dp)) {
            Text(b.marker, Modifier.width(24.dp)); RichText(b.spans, Modifier.weight(1f))
        }
        is MdBlock.Code -> Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(10.dp))
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary)); Spacer(Modifier.width(8.dp)); RichText(b.spans)
        }
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        is MdBlock.Table -> {
            val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
            Column(Modifier.horizontalScroll(rememberScrollState()).border(BorderStroke(1.dp, outline))) {
                val all = listOf(b.header) + b.rows
                all.forEachIndexed { r, row ->
                    Row(Modifier.height(IntrinsicSize.Min).background(if (r == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)) {
                        for (c in 0 until cols) {
                            Box(Modifier.width(150.dp).fillMaxHeight().border(BorderStroke(0.5.dp, outline)).padding(6.dp)) {
                                RichText(row.getOrNull(c) ?: emptyList(), fontSize = 13, weight = if (r == 0) FontWeight.Bold else null)
                            }
                        }
                    }
                }
            }
        }
    }
}
