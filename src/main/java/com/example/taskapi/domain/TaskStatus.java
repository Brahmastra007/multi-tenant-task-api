package com.example.taskapi.domain;

/** Workflow state of a task. Stored by name; must match ck_tasks_status. */
public enum TaskStatus {
	TODO,
	IN_PROGRESS,
	DONE
}
