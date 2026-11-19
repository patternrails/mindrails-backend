package com.mindrails.backend.asset.config;

import com.mindrails.backend.common.config.FileStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AssetStorageProperties.class, FileStorageProperties.class})
public class AssetPropertiesConfiguration {}
