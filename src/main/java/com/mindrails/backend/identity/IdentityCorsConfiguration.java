package com.mindrails.backend.identity;

import java.util.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods=false)
public class IdentityCorsConfiguration {
    @Bean FilterRegistrationBean<CorsFilter> identityCors(IdentityProperties properties) {
        var config=new CorsConfiguration();
        config.setAllowedOrigins(List.of(properties.origins()));
        if(config.getAllowedOrigins().contains("*")) throw new IllegalStateException("Identity CORS requires explicit origins");
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","X-MindRails-Auth","Idempotency-Key"));
        config.setExposedHeaders(List.of("Content-Disposition","X-MindRails-Implementation"));config.setAllowCredentials(true);config.setMaxAge(600L);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/api/**",config);
        var bean=new FilterRegistrationBean<>(new CorsFilter(source));bean.setOrder(Ordered.HIGHEST_PRECEDENCE+10);return bean;
    }
}
