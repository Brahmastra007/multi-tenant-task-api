package com.example.taskapi.repository;

import com.example.taskapi.domain.User;
import java.util.Optional;

public interface UserRepository extends TenantScopedRepository<User> {

	/**
	 * Unscoped on purpose: at login the organization is not known yet. Safe because email is
	 * globally unique. Use only for authentication and registration, never for request handling.
	 */
	Optional<User> findByEmail(String email);

	/** Unscoped on purpose: registration must reject an email used in any organization. */
	boolean existsByEmail(String email);

}
