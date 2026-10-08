package com.example.taskapi.repository;

import com.example.taskapi.domain.Organization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Organizations are the tenants themselves. No findAll: listing every tenant is never a valid request. */
public interface OrganizationRepository extends Repository<Organization, UUID> {

	Organization save(Organization organization);

	Optional<Organization> findById(UUID id);

}
