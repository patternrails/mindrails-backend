package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.identity.HostedFileAccess;
import com.mindrails.backend.identity.IdentityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/api/asset-storage")
public final class FilePreviewController {
    private final @Nullable AssetStorageService assets;
    private final SessionDeliverables sessions;
    private final TaskExecutionDeliverables executions;
    private final FilePreviewService previews;
    private final HostedFileAccess hostedFiles;

    @org.springframework.beans.factory.annotation.Autowired
    public FilePreviewController(@Nullable AssetStorageService assets,
            SessionDeliverables sessions, TaskExecutionDeliverables executions, FilePreviewService previews, HostedFileAccess hostedFiles) {
        this.assets = assets; this.sessions = sessions;
        this.executions = executions; this.previews = previews;
        this.hostedFiles = hostedFiles;
    }

    @GetMapping("/versions/{version}/preview")
    public ResponseEntity<FilePreviewService.Preview> asset(@PathVariable UUID version) throws IOException {
        if (assets == null) throw AssetException.disabled();
        var file = assets.version(version); // Includes the original asset's owner check.
        try (var input = assets.open(version)) {
            return response(previews.read(file.getFilename(), input));
        }
    }

    @GetMapping("/sessions/{session}/deliverables/{id}/preview")
    public ResponseEntity<FilePreviewService.Preview> session(@PathVariable UUID session, @PathVariable UUID id) throws IOException {
        return file(sessions.download(session, id));
    }

    @GetMapping("/task-executions/{execution}/deliverables/{id}/preview")
    public ResponseEntity<FilePreviewService.Preview> execution(@PathVariable UUID execution, @PathVariable UUID id) throws IOException {
        return file(executions.download(execution, id));
    }

    public record HostRequest(String origin) {}
    public record HostedFile(String path, int expiresIn) {}

    @PostMapping("/versions/{version}/host")
    public ResponseEntity<HostedFile> hostAsset(@PathVariable UUID version, @RequestBody HostRequest input, HttpServletRequest request) throws IOException {
        return grant(asset(version).getBody(), input, request);
    }
    @PostMapping("/sessions/{session}/deliverables/{id}/host")
    public ResponseEntity<HostedFile> hostSession(@PathVariable UUID session, @PathVariable UUID id, @RequestBody HostRequest input, HttpServletRequest request) throws IOException {
        return grant(session(session, id).getBody(), input, request);
    }
    @PostMapping("/task-executions/{execution}/deliverables/{id}/host")
    public ResponseEntity<HostedFile> hostExecution(@PathVariable UUID execution, @PathVariable UUID id, @RequestBody HostRequest input, HttpServletRequest request) throws IOException {
        return grant(execution(execution, id).getBody(), input, request);
    }

    @GetMapping("/versions/{version}/view")
    public ResponseEntity<String> viewAsset(@PathVariable UUID version) throws IOException { return html(asset(version).getBody()); }
    @GetMapping("/sessions/{session}/deliverables/{id}/view")
    public ResponseEntity<String> viewSession(@PathVariable UUID session, @PathVariable UUID id) throws IOException { return html(session(session, id).getBody()); }
    @GetMapping("/task-executions/{execution}/deliverables/{id}/view")
    public ResponseEntity<String> viewExecution(@PathVariable UUID execution, @PathVariable UUID id) throws IOException { return html(execution(execution, id).getBody()); }

    private ResponseEntity<HostedFile> grant(FilePreviewService.Preview preview, HostRequest input, HttpServletRequest request) {
        requireHtml(preview);
        hostedFiles.requireOrigin(input.origin());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        path = path.substring(0, path.length() - "/host".length()) + "/view";
        String token = hostedFiles.issue(IdentityFilter.current(request), path);
        String cookie = ResponseCookie.from(HostedFileAccess.COOKIE, token).httpOnly(true)
                .secure(hostedFiles.secureCookie()).sameSite(hostedFiles.cookieSameSite())
                .path(request.getContextPath() + path).maxAge(HostedFileAccess.SECONDS).build().toString();
        return ResponseEntity.ok().header("Set-Cookie", cookie).header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new HostedFile(path + "?app=" + URLEncoder.encode(input.origin(), StandardCharsets.UTF_8), HostedFileAccess.SECONDS));
    }

    private void requireHtml(FilePreviewService.Preview preview) {
        if (preview == null || !preview.kind().equals("html")) throw new AssetException(AssetException.Reason.PREVIEW_UNSUPPORTED,
                "Direct hosting supports HTML files only");
    }

    private ResponseEntity<String> html(FilePreviewService.Preview preview) {
        requireHtml(preview);
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header("Content-Disposition", ContentDisposition.inline().filename(preview.filename(), StandardCharsets.UTF_8).build().toString())
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer").header("Cross-Origin-Opener-Policy", "same-origin")
                // Scripts can operate on their own document, but receive no MindRails origin/storage authority.
                .header("Content-Security-Policy", "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'; frame-ancestors 'none'")
                .body(preview.content());
    }

    private ResponseEntity<FilePreviewService.Preview> file(Path path) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return response(previews.read(path.getFileName().toString(), input));
        }
    }

    private ResponseEntity<FilePreviewService.Preview> response(FilePreviewService.Preview preview) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff").body(preview);
    }
}
