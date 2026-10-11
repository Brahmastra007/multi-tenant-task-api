package com.example.taskapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.taskapi.domain.Role;
import com.example.taskapi.exception.InvalidTokenException;
import io.jsonwebtoken.security.WeakKeyException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain unit test: no Spring context, no database. */
class JwtTokenProviderTest {

	private static final String SECRET = "j7gTPEG5EttC4MCxVETr/FPwTDh4FEM5bztwdFsjeFg=";
	private static final String OTHER_SECRET = "bXlTZWNvbmRUZXN0S2V5VGhhdElzMzJCeXRlc0xvbmchIQ==";

	private final JwtTokenProvider provider = provider(SECRET, Duration.ofMinutes(15));

	private final UserPrincipal user = new UserPrincipal(UUID.randomUUID(), UUID.randomUUID(), "alice@a.example",
			"hash-never-in-token", Role.MANAGER);

	@Test
	void accessTokenRoundTripsClaims() {
		UserPrincipal parsed = provider.parseAccessToken(provider.generateAccessToken(user));

		assertThat(parsed.id()).isEqualTo(user.id());
		assertThat(parsed.organizationId()).isEqualTo(user.organizationId());
		assertThat(parsed.email()).isEqualTo(user.email());
		assertThat(parsed.role()).isEqualTo(Role.MANAGER);
		assertThat(parsed.passwordHash()).isNull();
	}

	@Test
	void refreshTokenIsNotAcceptedAsAccessToken() {
		String refreshToken = provider.generateRefreshToken(user);

		assertThat(provider.parseRefreshToken(refreshToken).id()).isEqualTo(user.id());
		assertThatThrownBy(() -> provider.parseAccessToken(refreshToken)).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void accessTokenIsNotAcceptedAsRefreshToken() {
		String accessToken = provider.generateAccessToken(user);

		assertThatThrownBy(() -> provider.parseRefreshToken(accessToken)).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void tamperedTokenIsRejected() {
		String token = provider.generateAccessToken(user);
		// Change one character of the payload (the middle part); the signature no longer matches
		int payloadStart = token.indexOf('.') + 1;
		char flipped = token.charAt(payloadStart) == 'a' ? 'b' : 'a';
		String tampered = token.substring(0, payloadStart) + flipped + token.substring(payloadStart + 1);

		assertThatThrownBy(() -> provider.parseAccessToken(tampered)).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void tokenSignedWithAnotherKeyIsRejected() {
		String foreignToken = provider(OTHER_SECRET, Duration.ofMinutes(15)).generateAccessToken(user);

		assertThatThrownBy(() -> provider.parseAccessToken(foreignToken)).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void expiredTokenIsRejected() {
		String expired = provider(SECRET, Duration.ofSeconds(-1)).generateAccessToken(user);

		assertThatThrownBy(() -> provider.parseAccessToken(expired)).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void garbageIsRejected() {
		assertThatThrownBy(() -> provider.parseAccessToken("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
		assertThatThrownBy(() -> provider.parseAccessToken("")).isInstanceOf(InvalidTokenException.class);
	}

	@Test
	void secretShorterThan256BitsFailsAtStartup() {
		String shortSecret = "c2hvcnQ="; // "short"

		assertThatThrownBy(() -> provider(shortSecret, Duration.ofMinutes(15))).isInstanceOf(WeakKeyException.class);
	}

	private static JwtTokenProvider provider(String secret, Duration accessTokenTtl) {
		return new JwtTokenProvider(new JwtProperties(secret, accessTokenTtl, Duration.ofDays(7)));
	}

}
