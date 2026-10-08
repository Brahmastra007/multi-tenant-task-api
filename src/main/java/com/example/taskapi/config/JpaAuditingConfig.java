package com.example.taskapi.config;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Turns on automatic created_at / updated_at / created_by population.
 * Kept out of the main class so web-only test slices don't need JPA.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

	/** Supplies created_by. Returns no user until JWT authentication exists (Phase 3). */
	@Bean
	AuditorAware<UUID> auditorAware() {
		return Optional::empty;
	}

}
