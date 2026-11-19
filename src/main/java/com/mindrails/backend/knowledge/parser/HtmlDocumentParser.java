package com.mindrails.backend.knowledge.parser;

import com.mindrails.backend.knowledge.service.KnowledgeException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.nio.file.Path;

/** Static HTML only: never execute scripts or fetch referenced resources. */
final class HtmlDocumentParser implements DocumentParser {
    @Override
    public void parse(Path path, DocumentTextCollector output) throws Exception {
        var document = Jsoup.parse(path.toFile(), null);
        document.select("script,style,noscript,template,[hidden],[aria-hidden=true]").remove();
        NodeTraversor.traverse(new NodeVisitor() {
            private final StringBuilder text = new StringBuilder();
            private String heading = document.title().isBlank() ? "HTML" : document.title();
            private int paragraph;

            private void flush() {
                if (!text.toString().isBlank()) {
                    output.add(text.toString(), "HTML / " + heading + " / Paragraph " + ++paragraph);
                    text.setLength(0);
                }
            }

            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element element) {
                    if (element.isBlock()) flush();
                    if (element.normalName().matches("h[1-6]")) heading = element.text();
                    if (element.normalName().equals("br")) text.append('\n');
                } else if (node instanceof TextNode value) {
                    text.append(value.getWholeText());
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element) {
                    if (element.normalName().matches("t[dh]")) text.append(" | ");
                    if (element.isBlock()) flush();
                }
            }
        }, document.body());
        if (output.isEmpty())
            throw KnowledgeException.invalid(
                    "HTML contains no usable body text. JavaScript is not executed; export a static"
                            + " HTML document with text content.");
    }
}
