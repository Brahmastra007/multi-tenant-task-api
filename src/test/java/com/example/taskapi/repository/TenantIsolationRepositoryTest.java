package com.example.taskapi.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.taskapi.TestcontainersConfiguration;
import com.example.taskapi.config.JpaAuditingConfig;
import com.example.taskapi.domain.Organization;
import com.example.taskapi.domain.Project;
import com.example.taskapi.domain.Role;
import com.example.taskapi.domain.Task;
import com.example.taskapi.domain.TaskStatus;
import com.example.taskapi.domain.User;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

/** Verifies tenant scoping in repositories and the database's cross-tenant foreign keys. */
@DataJpaTest
@Import({ TestcontainersConfiguration.class, JpaAuditingConfig.class })
class TenantIsolationRepositoryTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private TaskRepository taskRepository;

	private Organization orgA;
	private Organization orgB;

	@BeforeEach
	void createOrganizations() {
		orgA = organization("Org A");
		orgB = organization("Org B");
	}

	@Test
	void scopedFinderHidesOtherTenantsProject() {
		Project projectOfA = project(orgA, "A's project");

		assertThat(projectRepository.findByIdAndOrganizationId(projectOfA.getId(), orgA.getId())).isPresent();
		assertThat(projectRepository.findByIdAndOrganizationId(projectOfA.getId(), orgB.getId())).isEmpty();
		assertThat(projectRepository.existsByIdAndOrganizationId(projectOfA.getId(), orgB.getId())).isFalse();
	}

	@Test
	void paginatedListReturnsOnlyOwnTenantsRows() {
		project(orgA, "A1");
		project(orgA, "A2");
		project(orgB, "B1");

		var page = projectRepository.findAllByOrganizationId(orgA.getId(), PageRequest.of(0, 10));

		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).extracting(Project::getName).containsExactlyInAnyOrder("A1", "A2");
	}

	@Test
	void auditingFillsTimestamps() {
		Project saved = project(orgA, "Audited");

		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
	}

	@Test
	void databaseRejectsTaskInOtherTenantsProject() {
		Project projectOfA = project(orgA, "A's project");
		Task task = task(orgB, projectOfA, null);

		taskRepository.save(task);

		// flush() sends the INSERT now; otherwise it would wait for a commit that never happens
		assertThatThrownBy(entityManager::flush)
				.isInstanceOf(ConstraintViolationException.class)
				.hasMessageContaining("fk_tasks_project");
	}

	@Test
	void databaseRejectsTaskAssignedToOtherTenantsUser() {
		Project projectOfA = project(orgA, "A's project");
		User userOfB = user(orgB, "bob@b.example");
		Task task = task(orgA, projectOfA, userOfB);

		taskRepository.save(task);

		assertThatThrownBy(entityManager::flush)
				.isInstanceOf(ConstraintViolationException.class)
				.hasMessageContaining("fk_tasks_assignee");
	}

	private Organization organization(String name) {
		Organization organization = new Organization();
		organization.setName(name);
		return organizationRepository.save(organization);
	}

	private Project project(Organization organization, String name) {
		Project project = new Project();
		project.setOrganizationId(organization.getId());
		project.setName(name);
		return projectRepository.save(project);
	}

	private User user(Organization organization, String email) {
		User user = new User();
		user.setOrganizationId(organization.getId());
		user.setEmail(email);
		user.setPasswordHash("not-a-real-hash");
		user.setRole(Role.USER);
		return userRepository.save(user);
	}

	private Task task(Organization organization, Project project, User assignee) {
		Task task = new Task();
		task.setOrganizationId(organization.getId());
		task.setProject(project);
		task.setAssignee(assignee);
		task.setTitle("Some task");
		task.setStatus(TaskStatus.TODO);
		return task;
	}

}
