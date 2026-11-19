package com.mindrails.backend.persistence;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("mindrails.mysql")
public class DatabaseProperties {
    /** JDBC URL for persistence, metrics and management read pools. */
    private String url;
    /** Login name for persistence, metrics and management read pools. */
    private String username;
    /** Login password for persistence, metrics and management read pools. */
    @ToString.Exclude
    private String password;

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
