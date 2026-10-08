package com.example.taskapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A container for tasks within an organization. Tasks are fetched via the repository, paginated. */
@Getter
@Setter
@Entity
@Table(name = "projects")
public class Project extends TenantScopedEntity {

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description")
	private String description;

}
