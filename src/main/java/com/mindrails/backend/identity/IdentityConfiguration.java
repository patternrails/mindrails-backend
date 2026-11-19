package com.mindrails.backend.identity;

import com.mindrails.backend.common.config.FileStorageProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IdentityProperties.class, FileStorageProperties.class})
public class IdentityConfiguration {
    @Bean
    IdentityStore jdbcIdentityStore(@Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcIdentityStore(jdbc);
    }
}
