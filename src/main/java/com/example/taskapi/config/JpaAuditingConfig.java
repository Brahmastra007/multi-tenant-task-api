package com.example.taskapi.config;

import com.example.taskapi.security.UserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Turns on automatic created_at / updated_at / created_by population.
 * Kept out of the main class so web-only test slices don't need JPA.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

	/** Supplies created_by: the authenticated user's id, or empty (anonymous, e.g. during registration). */
	@Bean
	AuditorAware<UUID> auditorAware() {
		return () -> {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			// instanceof is false for null and for anonymous requests (principal "anonymousUser")
			if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
				return Optional.of(principal.id());
			}
			return Optional.empty();
		};
	}

}
