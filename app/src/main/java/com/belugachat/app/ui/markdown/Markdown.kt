package com.belugachat.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.belugachat.app.ui.theme.MonoStyle
import kotlinx.coroutines.delay
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
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Text as MdText

private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

private fun Node.children(): List<Node> {
    val out = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        out += child
        child = child.next
    }
    return out
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val document = remember(text) { parser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        document.children().forEach { Block(it, color) }
    }
}

@Composable
private fun Block(node: Node, color: Color) {
    val typography = MaterialTheme.typography
    when (node) {
        is Paragraph -> InlineText(node, typography.bodyLarge.copy(color = color))
        is Heading -> {
            val style = when (node.level) {
                1 -> typography.titleLarge
                2 -> typography.titleMedium
                else -> typography.titleSmall
            }
            InlineText(node, style.copy(color = color, fontWeight = FontWeight.SemiBold), Modifier.padding(top = 4.dp))
        }
        is FencedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), node.info?.substringBefore(' ').orEmpty())
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), "")
        is HtmlBlock -> CodeBlock(node.literal.trimEnd('\n'), "html")
        is BulletList -> ListBlock(node, ordered = false, start = 1, color = color)
        is OrderedList -> ListBlock(node, ordered = true, start = node.markerStartNumber ?: 1, color = color)
        is BlockQuote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.children().forEach { Block(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is TableBlock -> TableView(node, color)
        else -> node.children().forEach { Block(it, color) }
    }
}

@Composable
private fun ListBlock(node: Node, ordered: Boolean, start: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        node.children().filterIsInstance<ListItem>().forEachIndexed { index, item ->
            Row {
                Text(
                    if (ordered) "${start + index}." else "•",
                    style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.widthIn(min = if (ordered) 24.dp else 16.dp).padding(end = 6.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.children().forEach { Block(it, color) }
                }
            }
        }
    }
}

@Composable
private fun InlineText(node: Node, style: TextStyle, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(node, style, codeBg, linkColor) { inlines(node, codeBg, linkColor) }
    Text(annotated, style = style, modifier = modifier)
}

private fun inlines(node: Node, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    fun walk(n: Node) {
        when (n) {
            is MdText -> append(n.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 12.5.sp)) { append(" ${n.literal} ") }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { n.children().forEach(::walk) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { n.children().forEach(::walk) }
            is Link -> withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                n.children().forEach(::walk)
            }
            is Image -> withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(SpanStyle(color = linkColor)))) {
                append("🖼 ")
                n.children().forEach(::walk)
            }
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            is HtmlInline -> append(n.literal)
            else -> n.children().forEach(::walk)
        }
    }
    node.children().forEach(::walk)
}

@Composable
fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 34.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language.ifEmpty { "code" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable { clipboard.setText(AnnotatedString(code)); copied = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    null,
                    Modifier.size(14.dp),
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (copied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(code, style = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurface), softWrap = false)
        }
    }
}

@Composable
private fun TableView(table: TableBlock, color: Color) {
    val rows = mutableListOf<Pair<Boolean, List<TableCell>>>()
    table.children().forEach { section ->
        section.children().filterIsInstance<TableRow>().forEach { row ->
            rows += (section is TableHead) to row.children().filterIsInstance<TableCell>()
        }
    }
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.second.size }
    val widths = (0 until columns).map { col ->
        val longest = rows.maxOf { (_, cells) -> cells.getOrNull(col)?.let { plain(it).length } ?: 0 }
        (longest * 8 + 24).coerceIn(72, 280).dp
    }
    val border = MaterialTheme.colorScheme.outlineVariant
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, border, RoundedCornerShape(8.dp))) {
            rows.forEachIndexed { index, (header, cells) ->
                Row(Modifier.background(if (header) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) {
                    for (col in 0 until columns) {
                        val cell = cells.getOrNull(col)
                        Text(
                            cell?.let { inlines(it, codeBg, linkColor) } ?: AnnotatedString(""),
                            style = MaterialTheme.typography.bodyMedium.copy(color = color, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal),
                            modifier = Modifier.width(widths[col]).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (index < rows.lastIndex) HorizontalDivider(color = border)
            }
        }
    }
}

private fun plain(node: Node): String = buildString {
    fun walk(n: Node) {
        when (n) {
            is MdText -> append(n.literal)
            is Code -> append(n.literal)
            else -> n.children().forEach(::walk)
        }
    }
    walk(node)
}
