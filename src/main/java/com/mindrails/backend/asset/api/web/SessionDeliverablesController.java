package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.asset.service.SessionDeliverables;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/asset-storage/sessions/{session}/deliverables")
public class SessionDeliverablesController {
    private final SessionDeliverables files;
    public SessionDeliverablesController(SessionDeliverables files) { this.files = files; }

    @GetMapping public List<SessionDeliverables.File> list(@PathVariable UUID session) { return files.list(session); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID session, @PathVariable UUID id) {
        files.delete(session, id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable UUID session, @PathVariable UUID id) throws IOException {
        var path = files.download(session, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(path.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .contentLength(Files.size(path)).body(new FileSystemResource(path));
    }
}
