package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.asset.service.TaskExecutionDeliverables;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/asset-storage/task-executions/{execution}/deliverables")
public class TaskExecutionDeliverablesController {
    private final TaskExecutionDeliverables files;
    public TaskExecutionDeliverablesController(TaskExecutionDeliverables files) { this.files = files; }

    @GetMapping public List<TaskExecutionDeliverables.File> list(@PathVariable UUID execution) { return files.list(execution); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID execution, @PathVariable UUID id) {
        files.delete(execution, id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable UUID execution, @PathVariable UUID id) throws IOException {
        var path = files.download(execution, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(path.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .contentLength(Files.size(path)).body(new FileSystemResource(path));
    }
}
