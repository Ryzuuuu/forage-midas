package com.jpmc.midascore.incentive;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("midas.incentive")
public record IncentiveProperties(boolean enabled, URI url, Duration connectTimeout, Duration readTimeout) {
}
