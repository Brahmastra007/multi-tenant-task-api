package com.example.taskapi.domain;

/** Access level of a user within their organization. Stored by name; must match ck_users_role. */
public enum Role {
	ADMIN,
	MANAGER,
	USER
}
