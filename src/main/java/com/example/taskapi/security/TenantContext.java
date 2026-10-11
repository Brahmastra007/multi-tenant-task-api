package com.example.taskapi.security;

import java.util.UUID;

/**
 * Holds the organization id of the request being handled on the current thread.
 * Set by JwtAuthenticationFilter and cleared in its finally block: servlet threads are pooled,
 * so a value left behind would leak into the next request served by the same thread.
 */
public final class TenantContext {

	private static final ThreadLocal<UUID> CURRENT_ORGANIZATION = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(UUID organizationId) {
		CURRENT_ORGANIZATION.set(organizationId);
	}

	/** Throws instead of returning null, so a missing tenant can never reach a query. */
	public static UUID get() {
		UUID organizationId = CURRENT_ORGANIZATION.get();
		if (organizationId == null) {
			throw new IllegalStateException("No organization in the tenant context");
		}
		return organizationId;
	}

	public static void clear() {
		CURRENT_ORGANIZATION.remove();
	}

}
