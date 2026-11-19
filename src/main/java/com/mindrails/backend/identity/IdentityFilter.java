package com.mindrails.backend.identity;

import com.mindrails.backend.identity.Identity.Current;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.Principal;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdentityFilter extends OncePerRequestFilter {
    public static final String CURRENT = IdentityFilter.class.getName()+".current";
    private final IdentityService identity;
    private final HostedFileAccess hostedFiles;
    @org.springframework.beans.factory.annotation.Autowired
    public IdentityFilter(IdentityService identity, HostedFileAccess hostedFiles) { this.identity=identity; this.hostedFiles=hostedFiles; }
    public IdentityFilter(IdentityService identity) { this(identity, new HostedFileAccess(identity, new IdentityProperties())); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if (request.getMethod().equals("OPTIONS") ||
                (path.equals("/api/health") && request.getMethod().equals("GET")) ||
                (Set.of("/api/auth/login","/api/auth/refresh","/api/auth/logout").contains(path) && request.getMethod().equals("POST"))) {
            chain.doFilter(request,response); return;
        }
        Current current;
        try {
            String header=request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) current=identity.authenticate(header.substring(7));
            else if (request.getMethod().equals("GET") && HostedFileAccess.isViewPath(path)) {
                String token=null;
                if(request.getCookies()!=null) for(Cookie cookie:request.getCookies())
                    if(cookie.getName().equals(HostedFileAccess.COOKIE)) { token=cookie.getValue(); break; }
                current=hostedFiles.authenticate(token,path);
            }
            else if (request.getMethod().equals("GET") && "websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
                current=identity.consumeTicket(request.getParameter("ticket"),path);
            else throw IdentityException.unauthorized();
        } catch(IdentityException e) {
            if(request.getMethod().equals("GET") && HostedFileAccess.isViewPath(path) && request.getParameter("app")!=null) {
                try {
                    String target=hostedFiles.applicationUrl(request.getParameter("app"),path);
                    response.setStatus(303); response.setHeader("Location",target);
                    response.setHeader("Cache-Control","no-store"); response.setHeader("Referrer-Policy","no-referrer"); return;
                } catch(IdentityException ignored) { /* Untrusted redirect destinations never leave the API. */ }
            }
            response.setStatus(e.status); response.setContentType("application/json");
            response.setHeader("Cache-Control","no-store");
            response.getWriter().write("{\"message\":\"Invalid or expired credentials\"}"); return;
        }
        request.setAttribute(CURRENT,current);
        response.setHeader("Cache-Control","no-store");
        try (var scope = ResourceAccess.as(current.id())) {
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public Principal getUserPrincipal() { return current; }
            @Override public String getRemoteUser() { return current.id().toString(); }
            @Override public boolean isUserInRole(String role) { return current.role().name().equals(role); }
        },response);
        }
    }
    public static Current current(HttpServletRequest request) {
        if(request.getAttribute(CURRENT) instanceof Current c) return c;
        throw IdentityException.unauthorized();
    }
}
