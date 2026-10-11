package com.example.taskapi.security;

import com.example.taskapi.domain.Role;
import com.example.taskapi.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies HMAC-SHA256 signed JWTs. Claims: sub (user id), org, email, role, type.
 * The payload is only Base64-encoded, not encrypted, so it must never hold secrets.
 */
@Component
public class JwtTokenProvider {

	private static final String CLAIM_ORGANIZATION = "org";
	private static final String CLAIM_EMAIL = "email";
	private static final String CLAIM_ROLE = "role";
	private static final String CLAIM_TYPE = "type";

	/** Stops a long-lived refresh token from being used as an access token, and vice versa. */
	private enum TokenType {
		ACCESS,
		REFRESH
	}

	private final JwtProperties properties;
	private final SecretKey signingKey;

	public JwtTokenProvider(JwtProperties properties) {
		this.properties = properties;
		// Throws WeakKeyException at startup if the secret is shorter than 256 bits
		this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
	}

	public String generateAccessToken(UserPrincipal user) {
		return generate(user, TokenType.ACCESS, properties.accessTokenTtl());
	}

	public String generateRefreshToken(UserPrincipal user) {
		return generate(user, TokenType.REFRESH, properties.refreshTokenTtl());
	}

	/** @throws InvalidTokenException if the token is invalid, expired or a refresh token */
	public UserPrincipal parseAccessToken(String token) {
		return parse(token, TokenType.ACCESS);
	}

	/** @throws InvalidTokenException if the token is invalid, expired or an access token */
	public UserPrincipal parseRefreshToken(String token) {
		return parse(token, TokenType.REFRESH);
	}

	private String generate(UserPrincipal user, TokenType type, Duration ttl) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(user.id().toString())
				.claim(CLAIM_ORGANIZATION, user.organizationId().toString())
				.claim(CLAIM_EMAIL, user.email())
				.claim(CLAIM_ROLE, user.role().name())
				.claim(CLAIM_TYPE, type.name())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(ttl)))
				.signWith(signingKey)
				.compact();
	}

	private UserPrincipal parse(String token, TokenType expectedType) {
		Claims claims;
		try {
			// Verifies the signature and expiry; unsigned ("alg": "none") tokens are rejected
			claims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
		} catch (JwtException | IllegalArgumentException e) {
			throw new InvalidTokenException("Invalid or expired token", e);
		}
		if (!expectedType.name().equals(claims.get(CLAIM_TYPE, String.class))) {
			throw new InvalidTokenException("Wrong token type");
		}
		return new UserPrincipal(
				UUID.fromString(claims.getSubject()),
				UUID.fromString(claims.get(CLAIM_ORGANIZATION, String.class)),
				claims.get(CLAIM_EMAIL, String.class),
				null,
				Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
	}

}
