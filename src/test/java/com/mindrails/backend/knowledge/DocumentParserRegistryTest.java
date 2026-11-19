package com.mindrails.backend.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.parser.DocumentChunker;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.knowledge.store.LocalDocumentStorage;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

class DocumentParserRegistryTest {
    @TempDir Path directory;
    final KnowledgeProperties properties = new KnowledgeProperties();
    final DocumentParserRegistry parser = new DocumentParserRegistry(properties);

    @Test
    void htmlExtractsDeckHeadingsParagraphsAndTablesWithoutScriptsOrResources() throws Exception {
        var file = directory.resolve("deck.HTML");
        Files.writeString(file, """
                <!doctype html><meta charset="utf-8"><title>发布计划</title>
                <style>body{background:red}</style>
                <section><h1>第一张：预算</h1><p>预算 <strong>1200</strong> &amp; 部署。</p>
                <table><tr><td>数据库</td><td>MySQL</td></tr></table></section>
                <section><h2>第二张：时间</h2><div>中文段落<br>明天上线。</div></section>
                <script src="http://127.0.0.1:1/no-network">secret script</script>
                <img src="http://127.0.0.1:1/no-image"><div hidden>hidden secret</div>
                <template>template secret</template>
                """);
        var blocks = parser.parse(file, "deck.HTML");
        String text = blocks.stream().map(b -> b.text()).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(text.contains("预算 1200 & 部署。"));
        assertTrue(text.contains("MySQL"));
        assertTrue(text.contains("明天上线"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("background"));
        assertTrue(blocks.getLast().location().contains("第二张：时间"));
        assertTrue(blocks.stream().allMatch(b -> b.location().contains("Paragraph")));
        var chunks = new DocumentChunker(properties).split(UUID.randomUUID(), blocks, 400, 60);
        assertTrue(chunks.stream().anyMatch(c -> c.text().contains("1200")));
    }

    @Test
    void htmlHonorsDeclaredEncodingAndRejectsEmptyDynamicPagesAndTextOverflow() throws Exception {
        var file = directory.resolve("page.htm");
        Files.write(file, "<meta charset=gb18030><h1>中文标题</h1><p>知识库嵌入内容</p>"
                .getBytes(java.nio.charset.Charset.forName("GB18030")));
        assertEquals("知识库嵌入内容", parser.parse(file, "page.htm").getLast().text());
        properties.setMaxTextChars(3);
        assertTrue(assertThrows(KnowledgeException.class, () -> parser.parse(file, "page.htm"))
                .getMessage().contains("limit"));
        properties.setMaxTextChars(1000);
        Files.writeString(file, "<title>App</title><div id=app></div><script>render()</script>");
        assertTrue(assertThrows(KnowledgeException.class, () -> parser.parse(file, "page.htm"))
                .getMessage().contains("JavaScript"));
        assertTrue(assertThrows(KnowledgeException.class, () -> DocumentParserRegistry.extension("archive.zip"))
                .getMessage().contains(".zip. Supported types: txt, md, html, htm"));
    }

    @Test
    void markdownPreservesChineseHeadingsCodeAndStableChunkIds() throws Exception {
        var file = directory.resolve("manual.md");
        Files.writeString(
                file, "# 数据库\n项目使用 MySQL\n```sql\nSELECT 1;\n```\n## 部署\n" + "部署步骤。".repeat(300));
        var blocks = parser.parse(file, "manual.md");
        assertEquals(2, blocks.size());
        assertTrue(blocks.getFirst().text().contains("SELECT 1"));
        UUID revision = UUID.randomUUID();
        var chunks = new DocumentChunker(properties).split(revision, blocks, 400, 60);
        assertEquals(chunks, new DocumentChunker(properties).split(revision, blocks, 400, 60));
        assertTrue(chunks.size() > 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= 400));
        assertTrue(chunks.getLast().location().contains("部署"));
    }

    @Test
    void pdfPreservesPagesAndRejectsScannedOrCorruptContent() throws Exception {
        var file = directory.resolve("guide.pdf");
        try (var pdf = new PDDocument()) {
            for (int i = 1; i <= 2; i++) {
                var page = new PDPage();
                pdf.addPage(page);
                try (var text = new PDPageContentStream(pdf, page)) {
                    text.beginText();
                    text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    text.newLineAtOffset(40, 700);
                    text.showText("Document page " + i);
                    text.endText();
                }
            }
            pdf.save(file.toFile());
        }
        var blocks = parser.parse(file, "guide.pdf");
        assertEquals("Page 2", blocks.getLast().location());
        var scan = directory.resolve("scan.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(scan.toFile());
        }
        assertTrue(
                assertThrows(KnowledgeException.class, () -> parser.parse(scan, "scan.pdf"))
                        .getMessage()
                        .contains("OCR"));
        Files.writeString(file, "not PDF");
        assertThrows(KnowledgeException.class, () -> parser.parse(file, "guide.pdf"));
    }

    @Test
    void officeExtractsTablesAndSlideNumbers() throws Exception {
        var word = directory.resolve("guide.docx");
        try (var document = new XWPFDocument();
                var output = Files.newOutputStream(word)) {
            document.createParagraph().createRun().setText("中文操作指南");
            document.createTable(1, 2).getRow(0).getCell(0).setText("数据库 MySQL");
            document.write(output);
        }
        var blocks = parser.parse(word, "guide.docx");
        assertTrue(blocks.stream().anyMatch(block -> block.text().contains("数据库")));
        assertTrue(blocks.stream().anyMatch(block -> block.location().contains("Table")));
        var slides = directory.resolve("guide.pptx");
        try (var document = new XMLSlideShow();
                var output = Files.newOutputStream(slides)) {
            document.createSlide().createTextBox().setText("第一张：配置数据库");
            document.createSlide().createTextBox().setText("第二张：启动服务");
            document.write(output);
        }
        blocks = parser.parse(slides, "guide.pptx");
        assertTrue(blocks.getLast().location().contains("Slide 2"));
    }

    @Test
    void storageRejectsTraversalAndSizeOverflow() throws Exception {
        properties.setMaxUploadBytes(4);
        var storage = new LocalDocumentStorage(new FileStorageProperties(directory), properties);
        assertThrows(KnowledgeException.class, () -> storage.path("../../private.txt"));
        assertThrows(
                KnowledgeException.class,
                () ->
                        storage.write(
                                UUID.randomUUID(),
                                "txt",
                                new java.io.ByteArrayInputStream(new byte[5])));
        assertEquals(0, Files.walk(directory).filter(Files::isRegularFile).count());
    }

    @Test
    void textEncodingsAndExtractionLimitAreEnforced() throws Exception {
        var file = directory.resolve("notes.txt");
        for (var charset :
                List.of(
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.charset.StandardCharsets.UTF_16,
                        java.nio.charset.Charset.forName("GB18030"))) {
            Files.write(file, "中文知识与数据库部署".getBytes(charset));
            assertEquals("中文知识与数据库部署", parser.parse(file, "notes.txt").getFirst().text());
        }
        properties.setMaxTextChars(3);
        assertThrows(KnowledgeException.class, () -> parser.parse(file, "notes.txt"));
    }

    @Test
    void encryptedPdfIsRejected() throws Exception {
        var file = directory.resolve("secret.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.protect(
                    new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                            "owner",
                            "reader",
                            new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            pdf.save(file.toFile());
        }
        assertThrows(KnowledgeException.class, () -> parser.parse(file, "secret.pdf"));
    }
}
