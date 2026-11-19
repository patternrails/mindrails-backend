package com.mindrails.backend.asset;

import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.service.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class FilePreviewServiceTest {
    final AssetStorageProperties properties = new AssetStorageProperties();
    final FilePreviewService previews = new FilePreviewService(properties);
    private FilePreviewService.Preview read(String name, String text) throws Exception {
        return previews.read(name, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void allTextKindsUseOriginalTextIncludingInvalidJsonAndUtf8Bom() throws Exception {
        for (String name : new String[]{"a.txt", "a.MD", "a.markdown", "a.json", "a.csv", "a.htm", "a.html"}) {
            var preview = read(name, "\uFEFF你好\r\n<original>");
            assertEquals("你好\r\n<original>", preview.content());
            assertEquals(21, preview.bytes());
        }
        assertEquals("{bad json}", read("a.json", "{bad json}").content());
    }

    @Test void readsAtMostConfiguredLimitPlusOneByteAndCountsBytesRatherThanCharacters() throws Exception {
        properties.setMaxPreviewBytes(6);
        assertEquals("你好", read("a.txt", "你好").content());
        var input = new ByteArrayInputStream(new byte[100]);
        var failure = assertThrows(AssetException.class, () -> previews.read("a.txt", input));
        assertEquals(AssetException.Reason.PREVIEW_TOO_LARGE, failure.reason());
        assertEquals(93, input.available());
        assertEquals(AssetException.Reason.PREVIEW_TOO_LARGE,
                assertThrows(AssetException.class, () -> read("a.txt", "你好!")).reason());
    }

    @Test void rejectsBinaryInvalidUtf8AndUnlistedExtensions() {
        for (byte[] bytes : new byte[][]{{0, 1}, {(byte) 0xc3, 0x28}, {0x7f}, {0x1b, 0x20}})
            assertEquals(AssetException.Reason.PREVIEW_UNSUPPORTED,
                    assertThrows(AssetException.class, () -> previews.read("fake.txt", new ByteArrayInputStream(bytes))).reason());
        assertThrows(AssetException.class, () -> read("a.svg", "<svg></svg>"));
        assertThrows(AssetException.class, () -> read("no-extension", "hello"));
    }

    @Test void rasterImagePreviewReturnsMetadataWithoutEmbeddingBinaryBytesInJson() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=");
        properties.setMaxPreviewBytes(6); // Text preview budget does not reject a valid image.
        var image = previews.read("generated.png", new ByteArrayInputStream(png));
        assertEquals("image", image.kind()); assertEquals("image/png", image.mediaType());
        assertEquals(png.length, image.bytes()); assertEquals("", image.content()); assertNull(image.renderedHtml());
        assertEquals(AssetException.Reason.PREVIEW_UNSUPPORTED, assertThrows(AssetException.class,
                () -> read("fake.png", "<svg><script>alert(1)</script></svg>")).reason());
        properties.setMaxUploadBytes(10);
        assertEquals(AssetException.Reason.PREVIEW_TOO_LARGE, assertThrows(AssetException.class,
                () -> previews.read("too-large.png", new ByteArrayInputStream(png))).reason());
    }

    @Test void htmlKeepsStaticLayoutButRemovesExecutableContentResourcesAndNavigation() throws Exception {
        String html = "<html lang='zh'><head><style>body{color:red}</style><style>body{color:green}</style><base href='https://bad.example'>"
                + "<meta http-equiv='refresh' content='0;url=https://bad.example'></head><body class='dashboard' style='padding:20px'>"
                + "<main class='report' style='color:blue'><h1>Report</h1><script>alert(1)</script>"
                + "<a href='https://bad.example' onclick='alert(2)'>link</a><iframe srcdoc='<script>bad()</script>'></iframe>"
                + "<form action='https://bad.example'><input name='secret'></form>"
                + "<img src='https://bad.example/pixel'><img src='data:image/svg+xml;base64,AAAA'>"
                + "<img src='data:image/png;base64,AAAA'></main></body></html>";
        var preview = read("report.html", html);
        assertEquals(html, preview.content());
        var doc = Jsoup.parse(preview.renderedHtml());
        assertEquals("Report", doc.selectFirst("h1").text());
        assertEquals("color:blue", doc.selectFirst("main").attr("style"));
        assertTrue(doc.select("style").html().contains("color:red"));
        assertEquals("zh", doc.selectFirst("html").attr("lang"));
        assertEquals("dashboard", doc.body().className());
        assertEquals("padding:20px", doc.body().attr("style"));
        assertTrue(preview.renderedHtml().indexOf("color:red") < preview.renderedHtml().indexOf("color:green"));
        assertTrue(doc.select("script,iframe,form,input,meta[http-equiv],base,[onclick],a[href]").isEmpty());
        assertEquals(1, doc.select("img[src]").size());
        assertEquals("data:image/png;base64,AAAA", doc.selectFirst("img[src]").attr("src"));
    }
}
