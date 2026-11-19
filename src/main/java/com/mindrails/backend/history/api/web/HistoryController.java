package com.mindrails.backend.history.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;

import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.model.HistoryFilter;
import com.mindrails.backend.history.service.HistoryService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/history")
public class HistoryController {
    private final HistoryService service;

    public HistoryController(HistoryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<HistoryPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) String status) {
        return response(
                service.list(new HistoryFilter(null, null, agentId, sessionId, status), page, size)
                        .map(
                                data ->
                                        new HistoryPageResponse(
                                                data.items(),
                                                data.total(),
                                                data.visibleTotal(),
                                                data.limit(),
                                                data.page(),
                                                data.size(),
                                                data.hasNext(),
                                                service.recordingEnabled())),
                HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<HistoryRecord> get(@PathVariable UUID id) {
        return response(service.get(id).map(com.mindrails.backend.tool.display.ToolUiProjection::history), HttpStatus.OK);
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<HistoryPreview> preview(@PathVariable UUID id) {
        return response(service.get(id).map(HistoryPreview::from), HttpStatus.OK);
    }

    public record MetadataResponse(UUID id, @com.fasterxml.jackson.annotation.JsonRawValue String metadata) {}
    public record MessageResponse(UUID id, int index, @com.fasterxml.jackson.annotation.JsonRawValue String message) {}

    @GetMapping("/{id}/metadata")
    public ResponseEntity<MetadataResponse> metadata(@PathVariable UUID id) {
        return response(service.metadata(id).map(value -> new MetadataResponse(id, value)), HttpStatus.OK);
    }

    @GetMapping("/{id}/messages/{index}")
    public ResponseEntity<MessageResponse> originalMessage(@PathVariable UUID id, @PathVariable int index) {
        return response(service.originalMessage(id, index).map(value -> new MessageResponse(id, index, value)), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return response(service.delete(id), HttpStatus.NO_CONTENT);
    }
}
