package com.example.taskapi.security;

import com.example.taskapi.domain.Role;
import com.example.taskapi.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated user as Spring Security sees it. Built from the database at login
 * (with the password hash) and from JWT claims on every other request (hash is null).
 */
public record UserPrincipal(UUID id, UUID organizationId, String email, String passwordHash, Role role)
		implements UserDetails {

	public static UserPrincipal from(User user) {
		return new UserPrincipal(user.getId(), user.getOrganizationId(), user.getEmail(), user.getPasswordHash(),
				user.getRole());
	}

	/** hasRole("ADMIN") checks for the authority "ROLE_ADMIN". */
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return email;
	}

	/** The record default would print the password hash into logs. */
	@Override
	public String toString() {
		return "UserPrincipal[id=" + id + ", organizationId=" + organizationId + ", role=" + role + "]";
	}

}
