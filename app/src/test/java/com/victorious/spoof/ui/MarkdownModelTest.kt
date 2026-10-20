package com.victorious.spoof.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MarkdownModelTest {
    @Test fun tablesParsed() {
        val b = MarkdownModel.parse("| Op | Kind |\n|---|---|\n| node.status.get | **read** |\n| spoof.pool.list | read |\n")
        val t = b.single() as MdBlock.Table
        assertEquals(listOf("Op", "Kind"), t.header.map(MarkdownModel::plain))
        assertEquals(2, t.rows.size)
        assertEquals("node.status.get", MarkdownModel.plain(t.rows[0][0]))
        assertTrue(t.rows[0][1].single().bold)
    }
    @Test fun inlineStylesAndNoRawMarkers() {
        val p = MarkdownModel.parse("Use **bold**, *it*, `code` and [link](https://example.com).").single() as MdBlock.Para
        assertEquals("Use bold, it, code and link.", MarkdownModel.plain(p.spans))
        assertTrue(p.spans.first { it.text == "bold" }.bold)
        assertTrue(p.spans.first { it.text == "code" }.code)
        assertEquals("https://example.com", p.spans.first { it.text == "link" }.link)
    }
    @Test fun headingsListsCode() {
        val b = MarkdownModel.parse("# Title\n\n## Sub\n\n- one\n- two\n\n1. a\n2. b\n\n```\n./gradlew test\n```\n\n---\n")
        assertEquals(1, (b[0] as MdBlock.Heading).level); assertEquals(2, (b[1] as MdBlock.Heading).level)
        val items = b.filterIsInstance<MdBlock.ListItem>()
        assertEquals(listOf("•", "•", "1.", "2."), items.map { it.marker })
        assertEquals("./gradlew test", (b.first { it is MdBlock.Code } as MdBlock.Code).text)
        assertTrue(b.last() is MdBlock.Rule)
    }
    @Test fun realManualRendersTablesAndNoPipes() {
        val f = listOf(File("../docs/MANUAL.md"), File("docs/MANUAL.md")).first { it.exists() }
        val blocks = MarkdownModel.parse(f.readText())
        assertTrue("manual has tables", blocks.any { it is MdBlock.Table })
        val paras = blocks.filterIsInstance<MdBlock.Para>().map { MarkdownModel.plain(it.spans) }
        assertFalse("a table leaked as raw text", paras.any { it.trim().startsWith("|") && it.contains("---") })
        assertFalse(paras.any { it.contains("**") })
    }
}
