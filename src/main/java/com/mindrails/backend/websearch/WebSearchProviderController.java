package com.mindrails.backend.websearch;

import com.mindrails.backend.identity.IdentityFilter;
import com.mindrails.backend.identity.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/web-search-provider")
public final class WebSearchProviderController {
    private final WebSearchProviderService providers;

    public WebSearchProviderController(WebSearchProviderService providers) {
        this.providers = providers;
    }

    @GetMapping
    public List<WebSearchProvider> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        admin(request);
        return providers.list(page, size);
    }

    @GetMapping("/{id}")
    public WebSearchProvider get(@PathVariable UUID id, HttpServletRequest request) {
        admin(request);
        return providers.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebSearchProvider create(@RequestBody WebSearchProvider.Input input, HttpServletRequest request) {
        admin(request);
        return providers.save(null, input);
    }

    @PutMapping("/{id}")
    public WebSearchProvider update(@PathVariable UUID id, @RequestBody WebSearchProvider.Input input,
            HttpServletRequest request) {
        admin(request);
        return providers.save(id, input);
    }

    @PutMapping("/{id}/active")
    public WebSearchProvider activate(@PathVariable UUID id, HttpServletRequest request) {
        admin(request);
        return providers.activate(id);
    }

    @PostMapping("/{id}/test")
    public WebSearchProvider.TestResult test(@PathVariable UUID id, HttpServletRequest request) {
        admin(request);
        return providers.test(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest request) {
        admin(request);
        providers.delete(id);
    }

    private static void admin(HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
    }
}
