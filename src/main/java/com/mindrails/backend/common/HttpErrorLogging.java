package com.mindrails.backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

/** Observes failures before MVC advice consumes them; leaves error responses unchanged. */
@Slf4j
@Component
public final class HttpErrorLogging extends OncePerRequestFilter
        implements HandlerExceptionResolver, Ordered {
    private static final String FAILURE = HttpErrorLogging.class.getName() + ".failure";
    private static final String HANDLER = HttpErrorLogging.class.getName() + ".handler";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        request.setAttribute(FAILURE, exception);
        if (handler instanceof HandlerMethod method) {
            request.setAttribute(
                    HANDLER,
                    method.getBeanType().getSimpleName() + "#" + method.getMethod().getName());
        }
        return null; // Existing controller advice and Spring's default resolvers still own the
        // response.
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Throwable unhandled = null;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            unhandled = exception;
            throw exception;
        } finally {
            int status =
                    unhandled == null ? response.getStatus() : Math.max(500, response.getStatus());
            if (status >= 400) {
                var failure =
                        unhandled != null ? unhandled : (Throwable) request.getAttribute(FAILURE);
                var diagnostic = ExceptionDiagnostics.forLogging(failure);
                String summary =
                        "HTTP "
                                + status
                                + " "
                                + ExceptionDiagnostics.safe(request.getMethod())
                                + " "
                                + ExceptionDiagnostics.safe(request.getRequestURI())
                                + " handler="
                                + ExceptionDiagnostics.safe(
                                        String.valueOf(request.getAttribute(HANDLER)));
                if (status >= 500) {
                    log.error(summary, diagnostic);
                } else {
                    log.warn(summary, diagnostic);
                }
            }
        }
    }
}
