package com.belugachat.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.belugachat.app.chat.AttachmentStore
import com.belugachat.app.data.Part
import com.belugachat.app.llm.Content
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun kindOfDetectsPdfWordExcelAndRejectsUnknown() {
        assertEquals(AttachmentStore.Kind.PDF, AttachmentStore.kindOf("application/pdf", "report.pdf"))
        assertEquals(AttachmentStore.Kind.PDF, AttachmentStore.kindOf("application/octet-stream", "scan.PDF"))
        assertEquals(
            AttachmentStore.Kind.DOCX,
            AttachmentStore.kindOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "notes"),
        )
        assertEquals(AttachmentStore.Kind.DOCX, AttachmentStore.kindOf("application/octet-stream", "contract.docx"))
        assertEquals(
            AttachmentStore.Kind.XLSX,
            AttachmentStore.kindOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "sheet"),
        )
        assertEquals(AttachmentStore.Kind.XLSX, AttachmentStore.kindOf("application/octet-stream", "budget.xlsx"))
        assertNull(AttachmentStore.kindOf("application/zip", "archive.zip"))
    }

    @Test
    fun docxTextExtractsParagraphsAndTableRows() {
        val file = File(context.cacheDir, "sample.docx")
        zipEntries(
            file,
            mapOf(
                "word/document.xml" to """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>Hello &amp; Welcome</w:t></w:r></w:p>
                        <w:tbl>
                          <w:tr>
                            <w:tc><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>Price</w:t></w:r></w:p></w:tc>
                          </w:tr>
                          <w:tr>
                            <w:tc><w:p><w:r><w:t>Notebook</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>12</w:t></w:r></w:p></w:tc>
                          </w:tr>
                        </w:tbl>
                      </w:body>
                    </w:document>
                """.trimIndent(),
            ),
        )
        val extracted = AttachmentStore.docxText(file)
        assertTrue(extracted.contains("Hello & Welcome"))
        assertTrue(extracted.contains("Item\tPrice"))
        assertTrue(extracted.contains("Notebook\t12"))
    }

    @Test
    fun xlsxTextExtractsSharedStringsInlineStringsBooleansNumbersAndSheets() {
        val file = File(context.cacheDir, "sample.xlsx")
        zipEntries(
            file,
            mapOf(
                "xl/sharedStrings.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
                      <si><t>Product</t></si>
                      <si><t>Revenue</t></si>
                      <si><t>Alpha</t></si>
                    </sst>
                """.trimIndent(),
                "xl/workbook.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        <sheet name="Q1 Sales" sheetId="1" r:id="rId1"/>
                        <sheet name="Flags" sheetId="2" r:id="rId2"/>
                      </sheets>
                    </workbook>
                """.trimIndent(),
                "xl/_rels/workbook.xml.rels" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                      <Relationship Id="rId2" Target="worksheets/sheet2.xml"/>
                    </Relationships>
                """.trimIndent(),
                "xl/worksheets/sheet1.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1">
                          <c r="A1" t="s"><v>0</v></c>
                          <c r="B1" t="s"><v>1</v></c>
                        </row>
                        <row r="2">
                          <c r="A2" t="s"><v>2</v></c>
                          <c r="B2"><v>4250</v></c>
                        </row>
                      </sheetData>
                    </worksheet>
                """.trimIndent(),
                "xl/worksheets/sheet2.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1">
                          <c r="A1" t="inlineStr"><is><t>Active</t></is></c>
                          <c r="B1" t="b"><v>1</v></c>
                        </row>
                      </sheetData>
                    </worksheet>
                """.trimIndent(),
            ),
        )
        val extracted = AttachmentStore.xlsxText(file)
        assertTrue(extracted.contains("## Q1 Sales"))
        assertTrue(extracted.contains("Product\tRevenue"))
        assertTrue(extracted.contains("Alpha\t4250"))
        assertTrue(extracted.contains("## Flags"))
        assertTrue(extracted.contains("Active\tTRUE"))
    }

    @Test
    fun pdfTextAndToContentHandleBothExtractedAndBinaryModes() {
        val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val pdfFile = File(attachmentsDir, "sample-id.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(50f, 700f)
                stream.showText("Beluga Chat Quarterly Report 2026")
                stream.endText()
            }
            doc.save(pdfFile)
        }

        val rawText = AttachmentStore.pdfText(pdfFile)
        assertTrue(rawText.contains("Beluga Chat Quarterly Report 2026"))

        val store = AttachmentStore(context)
        val part = Part.File("sample-id", "report.pdf", "application/pdf", "sample-id.pdf", pdfFile.length())

        val textMode = store.toContent(part, allowBinaryPdf = false)
        assertEquals(1, textMode.size)
        val textContent = textMode.first() as Content.Text
        assertTrue(textContent.text.contains("<document name=\"report.pdf\">"))
        assertTrue(textContent.text.contains("Beluga Chat Quarterly Report 2026"))

        val binaryMode = store.toContent(part, allowBinaryPdf = true)
        assertEquals(1, binaryMode.size)
        assertTrue(binaryMode.first() is Content.Pdf)
    }

    private fun zipEntries(target: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
