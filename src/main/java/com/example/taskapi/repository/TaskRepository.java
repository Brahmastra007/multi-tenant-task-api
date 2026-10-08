package com.example.taskapi.repository;

import com.example.taskapi.domain.Task;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TaskRepository extends TenantScopedRepository<Task> {

	Page<Task> findAllByProjectIdAndOrganizationId(UUID projectId, UUID organizationId, Pageable pageable);

}
