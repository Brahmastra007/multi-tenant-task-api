package com.example.taskapi.dto;

/**
 * Token pair returned by register, login and refresh. Field names follow the OAuth2 convention.
 *
 * @param expiresIn access token lifetime in seconds
 */
public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {

	public static AuthResponse bearer(String accessToken, String refreshToken, long expiresIn) {
		return new AuthResponse(accessToken, refreshToken, "Bearer", expiresIn);
	}

}
