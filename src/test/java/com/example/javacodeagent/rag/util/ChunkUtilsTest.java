package com.example.javacodeagent.rag.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkUtils 文档读取与分块单测（不触网、不依赖 Ollama）。
 *
 * 覆盖上传链路的三种格式：txt / docx（直接解 zip 里的 document.xml）/ pdf（PDFBox 抽取），
 * 以及分块的句子边界行为——真实踩过的坑是"PDF 声明支持但实际解析会抛异常"。
 */
class ChunkUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void readTxt() throws IOException {
        Path file = tempDir.resolve("note.txt");
        Files.writeString(file, "贵州茅台2026年三季报净利润同比增长15%。", StandardCharsets.UTF_8);

        String text = ChunkUtils.readSupportedDocument(file);

        assertTrue(text.contains("贵州茅台"));
    }

    @Test
    void readDocx() throws IOException {
        Path file = tempDir.resolve("report.docx");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>动力电池装机量</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>同比增长 20%</w:t></w:r></w:p></w:body></w:document>";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        String text = ChunkUtils.readSupportedDocument(file);

        assertTrue(text.contains("动力电池装机量"));
        assertTrue(text.contains("同比增长 20%"), "段落应被转换为换行而不是粘连: " + text);
    }

    @Test
    void readPdf() throws IOException {
        Path file = tempDir.resolve("research.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Moutai Q3 net profit up 15%");
                content.endText();
            }
            document.save(file.toFile());
        }

        String text = ChunkUtils.readSupportedDocument(file);

        assertTrue(text.contains("Moutai"), "PDF 应能被抽取为正文文本: " + text);
    }

    @Test
    void unsupportedFormatThrows() throws IOException {
        Path file = tempDir.resolve("report.md");
        Files.writeString(file, "# 无标题", StandardCharsets.UTF_8);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ChunkUtils.readSupportedDocument(file));

        assertTrue(ex.getMessage().contains(".pdf"), "报错信息应写清支持的格式: " + ex.getMessage());
    }

    @Test
    void chunkByToken_shouldSplitBySentenceBoundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append("第").append(i).append("条：公司主营业务收入保持稳定增长，毛利率同比提升。");
        }

        List<ChunkUtils.Chunk> chunks = ChunkUtils.chunkByToken(sb.toString(), "研报.pdf");

        assertTrue(chunks.size() > 1, "长文本应被切成多块，实际: " + chunks.size());
        assertEquals("研报.pdf", chunks.get(0).source());
        assertTrue(chunks.get(0).id().startsWith("chunk-"));
        for (ChunkUtils.Chunk chunk : chunks) {
            assertFalse(chunk.content().isBlank());
            assertTrue(chunk.tokenCount() > 0);
        }
    }

    @Test
    void chunkByToken_blankTextReturnsEmpty() {
        assertTrue(ChunkUtils.chunkByToken("   ", "empty.txt").isEmpty());
        assertTrue(ChunkUtils.chunkByToken(null, "empty.txt").isEmpty());
    }
}
