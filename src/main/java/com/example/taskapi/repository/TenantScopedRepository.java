package com.example.taskapi.repository;

import com.example.taskapi.domain.TenantScopedEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * Base for repositories of tenant-owned entities. Extends the empty {@link Repository} marker
 * instead of JpaRepository so unscoped methods like findById/findAll don't exist and can't be
 * called by mistake: every read must name the organization.
 */
@NoRepositoryBean
public interface TenantScopedRepository<T extends TenantScopedEntity> extends Repository<T, UUID> {

	T save(T entity);

	/** Callers must load the entity through a scoped finder first. */
	void delete(T entity);

	Optional<T> findByIdAndOrganizationId(UUID id, UUID organizationId);

	boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

	Page<T> findAllByOrganizationId(UUID organizationId, Pageable pageable);

}
