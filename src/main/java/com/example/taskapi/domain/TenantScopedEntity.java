package com.example.taskapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Base class for data owned by an organization (the tenant discriminator column).
 * The org id is set once on creation from the tenant context, never from a request body.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID organizationId;

}
