package com.example.taskapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A tenant. All users, projects and tasks belong to exactly one organization. */
@Getter
@Setter
@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

	@Column(name = "name", nullable = false)
	private String name;

}
