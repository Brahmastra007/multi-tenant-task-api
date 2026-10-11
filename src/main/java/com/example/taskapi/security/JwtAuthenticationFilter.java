package com.example.taskapi.security;

import com.example.taskapi.exception.InvalidTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying "Authorization: Bearer <access token>" and sets the tenant context.
 * A missing or invalid token leaves the request anonymous; the security rules then decide (401 if protected).
 * Not a @Component: Spring Boot would also register it as a plain servlet filter, outside the security chain.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtTokenProvider tokenProvider;

	public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
		this.tokenProvider = tokenProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		try {
			String header = request.getHeader(HttpHeaders.AUTHORIZATION);
			if (header != null && header.startsWith(BEARER_PREFIX)) {
				authenticate(header.substring(BEARER_PREFIX.length()));
			}
			filterChain.doFilter(request, response);
		} finally {
			// Pooled threads: without this, the next request on this thread would inherit our tenant
			TenantContext.clear();
		}
	}

	private void authenticate(String token) {
		try {
			UserPrincipal principal = tokenProvider.parseAccessToken(token);
			var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
					principal.getAuthorities());
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.setContext(context);
			TenantContext.set(principal.organizationId());
		} catch (InvalidTokenException e) {
			SecurityContextHolder.clearContext();
		}
	}

}
