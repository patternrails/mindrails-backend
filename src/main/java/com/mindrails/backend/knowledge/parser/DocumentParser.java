package com.mindrails.backend.knowledge.parser;

import java.nio.file.Path;

/** One format's text and source locations. Validation and chunking are separate. */
interface DocumentParser {
    void parse(Path path, DocumentTextCollector output) throws Exception;
}
