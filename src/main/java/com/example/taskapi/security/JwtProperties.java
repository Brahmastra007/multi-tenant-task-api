package com.example.taskapi.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT settings bound from app.jwt.* in application*.yaml. Startup fails if any is missing.
 *
 * @param secret          Base64-encoded HMAC key, at least 256 bits (32 bytes)
 * @param accessTokenTtl  lifetime of access tokens; short, because they cannot be revoked
 * @param refreshTokenTtl lifetime of refresh tokens
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
		@NotBlank String secret,
		@NotNull Duration accessTokenTtl,
		@NotNull Duration refreshTokenTtl) {
}
