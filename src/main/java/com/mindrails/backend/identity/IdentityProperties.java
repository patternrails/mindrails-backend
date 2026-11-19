package com.mindrails.backend.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;

@Data
@ConfigurationProperties("mindrails.identity")
public class IdentityProperties {
    private String adminLoginId = "admin";
    @lombok.ToString.Exclude
    private String adminPassword = "";
    private long accessSeconds = 900;
    private long refreshSeconds = 604800;
    private boolean secureCookie = true;
    private String cookieSameSite = "Lax";
    private String allowedOrigins = "http://localhost:3000,http://127.0.0.1:3000";
    public String[] origins() {
        return java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
}

