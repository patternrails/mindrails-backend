package com.mindrails.backend.identity;

import com.mindrails.backend.identity.Identity.*;
import jakarta.servlet.http.*;
import java.time.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String COOKIE="mindrails_refresh";
    private final IdentityService identity;
    private final IdentityProperties properties;
    public AuthController(IdentityService identity,IdentityProperties properties) { this.identity=identity;this.properties=properties; }
    public record Login(String loginId,String password) {}
    public record Refresh(String refreshToken) {}
    public record TicketRequest(String path) {}
    public record TicketResponse(String ticket,int expiresIn) {}
    @PostMapping("/login")
    public Tokens login(@RequestBody Login input,HttpServletRequest request,HttpServletResponse response) {
        Tokens tokens=identity.login(input.loginId(),input.password(),request.getRemoteAddr());
        cookie(response,request,tokens.refreshToken(),Duration.between(Instant.now(),tokens.refreshExpiresAt()));
        return browserTokens(tokens,request);
    }
    @PostMapping("/refresh")
    public Tokens refresh(@RequestBody(required=false) Refresh input,HttpServletRequest request,HttpServletResponse response) {
        try {
            Tokens tokens=identity.refresh(refreshToken(input,request));
            cookie(response,request,tokens.refreshToken(),Duration.between(Instant.now(),tokens.refreshExpiresAt()));return browserTokens(tokens,request);
        } catch (IdentityException e) { if(e.status==401) cookie(response,request,"",Duration.ZERO); throw e; }
    }
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required=false) Refresh input,HttpServletRequest request,HttpServletResponse response) {
        String header=request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") && !"1".equals(request.getHeader("X-MindRails-Auth"))
                ? (input == null ? null : input.refreshToken()) : refreshToken(input,request);
        if(header != null && header.startsWith("Bearer ")) {
            try { identity.logout(identity.authenticate(header.substring(7))); } catch(IdentityException ignored) { /* Already expired. */ }
        }
        try { identity.logoutRefresh(token); } catch(IdentityException ignored) { /* Logout is idempotent. */ }
        cookie(response,request,"",Duration.ZERO);
    }
    @GetMapping("/me") public Profile me(HttpServletRequest request) { return identity.profile(IdentityFilter.current(request).id()); }
    public record LanguageRequest(String language) {}
    @PutMapping("/language") public Profile language(@RequestBody LanguageRequest input,HttpServletRequest request) {
        return identity.language(IdentityFilter.current(request),input.language());
    }
    @PostMapping("/ws-ticket") public TicketResponse ticket(@RequestBody TicketRequest input,HttpServletRequest request) {
        return new TicketResponse(identity.ticket(IdentityFilter.current(request),input.path()),30);
    }
    private Tokens browserTokens(Tokens tokens,HttpServletRequest request) {
        return "1".equals(request.getHeader("X-MindRails-Auth"))
                ? new Tokens(tokens.accessToken(),null,tokens.accessExpiresAt(),tokens.refreshExpiresAt(),tokens.user()) : tokens;
    }
    private String refreshToken(Refresh input,HttpServletRequest request) {
        if(input != null && input.refreshToken() != null && !input.refreshToken().isBlank()) return input.refreshToken();
        // Cookie authentication requires a non-simple request, so cross-site forms cannot refresh or log out a browser.
        if(!"1".equals(request.getHeader("X-MindRails-Auth"))) throw IdentityException.forbidden();
        if(request.getCookies() != null) for(Cookie c:request.getCookies()) if(c.getName().equals(COOKIE)) return c.getValue();
        return null;
    }
    private void cookie(HttpServletResponse response,HttpServletRequest request,String token,Duration age) {
        String path=request.getContextPath()+"/api/auth";
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(COOKIE,token).httpOnly(true).secure(properties.isSecureCookie())
                .sameSite(properties.getCookieSameSite()).path(path).maxAge(age.isNegative()?Duration.ZERO:age).build().toString());
        response.setHeader("Cache-Control","no-store");
    }
}
