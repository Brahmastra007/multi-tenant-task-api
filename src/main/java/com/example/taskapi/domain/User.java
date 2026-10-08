package com.example.taskapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A member of an organization. Email is unique across all organizations so login needs only email. */
@Getter
@Setter
@Entity
@Table(name = "users") // "user" is a reserved word in PostgreSQL
public class User extends TenantScopedEntity {

	@Column(name = "email", nullable = false, unique = true)
	private String email;

	/** BCrypt hash; the plain-text password is never stored. */
	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private Role role;

}
