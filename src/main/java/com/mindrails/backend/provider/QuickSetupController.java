package com.mindrails.backend.provider;

import com.mindrails.backend.common.ServiceResponseException;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.identity.Identity;
import com.mindrails.backend.identity.IdentityFilter;
import com.mindrails.backend.identity.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/provider-quick-setup")
public final class QuickSetupController {
    private final QuickSetupService setup;

    public QuickSetupController(QuickSetupService setup) {
        this.setup = setup;
    }

    @GetMapping
    public QuickSetupService.Status status(HttpServletRequest request) {
        admin(request);
        return setup.status();
    }

    @PostMapping("/{kind}/test")
    public QuickSetupService.Test test(@PathVariable QuickSetupService.Kind kind,
            @RequestBody QuickSetupService.Draft draft, HttpServletRequest request) {
        var actor = admin(request);
        try {
            return setup.test(actor.id(), kind, draft);
        } catch (IllegalArgumentException invalid) {
            throw invalidDraft();
        }
    }

    @PostMapping
    public ResponseEntity<Identity.Profile> save(@RequestBody QuickSetupService.Setup input, HttpServletRequest request) {
        var actor = admin(request);
        try {
            var profile = setup.save(actor, input);
            return profile == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(profile);
        } catch (IllegalArgumentException invalid) {
            throw invalidDraft();
        }
    }

    private static Identity.Current admin(HttpServletRequest request) {
        var actor = IdentityFilter.current(request);
        IdentityService.requireAdmin(actor);
        return actor;
    }

    private static ServiceResponseException invalidDraft() {
        return new ServiceResponseException(HttpStatus.BAD_REQUEST, ServiceResult.ErrorCode.INVALID_ARGUMENT,
                "Check the provider type, endpoint, model and API key.");
    }
}
