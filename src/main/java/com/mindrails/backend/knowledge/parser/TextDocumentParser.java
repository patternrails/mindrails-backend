package com.mindrails.backend.knowledge.parser;

import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class TextDocumentParser implements DocumentParser {
    private final boolean markdown;

    TextDocumentParser(boolean markdown) {
        this.markdown = markdown;
    }

    @Override
    public void parse(Path path, DocumentTextCollector output) throws Exception {
        String text = decode(Files.readAllBytes(path));
        if (text.indexOf('\0') >= 0) {
            throw KnowledgeException.invalid("Binary contents are not a text document");
        }
        extractBlocks(text, output);
    }

    private String decode(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            return Charset.forName("GB18030")
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
    }

    private void extractBlocks(String text, DocumentTextCollector output) {
        String heading = "Text";
        var current = new StringBuilder();
        boolean insideCodeFence = false;
        for (String line : text.replace("\ufeff", "").replace("\r\n", "\n").split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                insideCodeFence = !insideCodeFence;
            }
            if (markdown && !insideCodeFence && line.matches("^#{1,6} .*")) {
                output.add(current.toString(), heading);
                current.setLength(0);
                heading = line;
            }
            current.append(line).append('\n');
        }
        output.add(current.toString(), heading);
    }
}
