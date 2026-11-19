package com.mindrails.backend.image;

import com.mindrails.backend.identity.IdentityFilter;
import com.mindrails.backend.identity.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/image-generation-provider")
public final class ImageProviderController {
    private final ImageProviderService providers;
    public ImageProviderController(ImageProviderService providers) { this.providers = providers; }

    @GetMapping public List<ImageProvider.Info> list(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return providers.list(page, size); }
    @GetMapping("/{id}") public ImageProvider.Info get(@PathVariable UUID id) { return providers.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ImageProvider.Info create(@RequestBody ImageProvider.Input input, HttpServletRequest request) {
        admin(request); return providers.save(null, input);
    }
    @PutMapping("/{id}") public ImageProvider.Info update(@PathVariable UUID id,
            @RequestBody ImageProvider.Input input, HttpServletRequest request) {
        admin(request); return providers.save(id, input);
    }
    @PostMapping("/{id}/test") public ImageProvider.TestResult test(@PathVariable UUID id,
            @RequestParam(defaultValue="false") boolean generateImage, HttpServletRequest request) {
        admin(request); return providers.test(id, generateImage);
    }
    @GetMapping("/{id}/tests") public List<ImageProvider.TestResult> tests(@PathVariable UUID id, HttpServletRequest request) {
        admin(request); return providers.tests(id);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest request) { admin(request); providers.delete(id); }

    private void admin(HttpServletRequest request) { IdentityService.requireAdmin(IdentityFilter.current(request)); }
}
