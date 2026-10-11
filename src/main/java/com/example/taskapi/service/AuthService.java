package com.example.taskapi.service;

import com.example.taskapi.domain.Organization;
import com.example.taskapi.domain.Role;
import com.example.taskapi.domain.User;
import com.example.taskapi.dto.AuthResponse;
import com.example.taskapi.dto.LoginRequest;
import com.example.taskapi.dto.RefreshRequest;
import com.example.taskapi.dto.RegisterRequest;
import com.example.taskapi.exception.DuplicateResourceException;
import com.example.taskapi.exception.InvalidTokenException;
import com.example.taskapi.repository.OrganizationRepository;
import com.example.taskapi.repository.UserRepository;
import com.example.taskapi.security.JwtProperties;
import com.example.taskapi.security.JwtTokenProvider;
import com.example.taskapi.security.UserPrincipal;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, login and token refresh. Every method returns a fresh access + refresh token pair. */
@Service
@RequiredArgsConstructor
public class AuthService {

	private final OrganizationRepository organizationRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider tokenProvider;
	private final JwtProperties jwtProperties;

	/** Creates a new organization with the registering user as its first ADMIN, in one transaction. */
	@Transactional
	public AuthResponse register(RegisterRequest request) {
		String email = normalizeEmail(request.email());
		if (userRepository.existsByEmail(email)) {
			throw new DuplicateResourceException("Email is already registered");
		}

		Organization organization = new Organization();
		organization.setName(request.organizationName().trim());
		organizationRepository.save(organization);

		User user = new User();
		user.setOrganizationId(organization.getId());
		user.setEmail(email);
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setRole(Role.ADMIN);
		userRepository.save(user);

		return issueTokens(UserPrincipal.from(user));
	}

	/** @throws org.springframework.security.authentication.BadCredentialsException (401) on a wrong email or password */
	public AuthResponse login(LoginRequest request) {
		Authentication authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(normalizeEmail(request.email()), request.password()));
		return issueTokens((UserPrincipal) authentication.getPrincipal());
	}

	/** Re-reads the user, so a deleted user or a changed role takes effect at the next refresh. */
	@Transactional(readOnly = true)
	public AuthResponse refresh(RefreshRequest request) {
		UserPrincipal claims = tokenProvider.parseRefreshToken(request.refreshToken());
		User user = userRepository.findByIdAndOrganizationId(claims.id(), claims.organizationId())
				.orElseThrow(() -> new InvalidTokenException("User no longer exists"));
		return issueTokens(UserPrincipal.from(user));
	}

	private AuthResponse issueTokens(UserPrincipal user) {
		return AuthResponse.bearer(
				tokenProvider.generateAccessToken(user),
				tokenProvider.generateRefreshToken(user),
				jwtProperties.accessTokenTtl().toSeconds());
	}

	/**
	 * Emails are case-insensitive, so store and look them up in lower case. No trim needed: @Email already
	 * rejects surrounding spaces. Locale.ROOT: in some locales (e.g. Turkish) "I".toLowerCase() is not "i".
	 */
	private static String normalizeEmail(String email) {
		return email.toLowerCase(Locale.ROOT);
	}

}
