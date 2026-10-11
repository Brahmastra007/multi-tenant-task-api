package com.example.taskapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.taskapi.TestcontainersConfiguration;
import com.example.taskapi.domain.Role;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Full app + real Postgres: auth endpoints, JWT filter, tenant context and role rules, over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthSecurityIntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider tokenProvider;

	/** Test-only endpoint exposing what the filter put in the tenant context. Not part of the real app. */
	@TestConfiguration(proxyBeanMethods = false)
	static class TenantProbeConfig {

		@Bean
		TenantProbeController tenantProbeController() {
			return new TenantProbeController();
		}

	}

	@RestController
	static class TenantProbeController {

		@GetMapping("/api/test/tenant")
		Map<String, String> tenant(@AuthenticationPrincipal UserPrincipal principal) {
			return Map.of("organizationId", TenantContext.get().toString(), "userId", principal.id().toString());
		}

	}

	@Test
	void registerCreatesAdminAndReturnsTokenPair() throws Exception {
		String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(registerJson("Acme", uniqueEmail(), PASSWORD)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andReturn().getResponse().getContentAsString();

		UserPrincipal user = tokenProvider.parseAccessToken(JsonPath.read(body, "$.accessToken"));
		assertThat(user.role()).isEqualTo(Role.ADMIN);
		assertThat(tokenProvider.parseRefreshToken(JsonPath.read(body, "$.refreshToken")).id()).isEqualTo(user.id());
	}

	@Test
	void protectedEndpointWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/test/tenant")).andExpect(status().isUnauthorized());
	}

	@Test
	void protectedEndpointWithInvalidTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/test/tenant").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void accessTokenSetsTenantContextForRequestAndItIsClearedAfterwards() throws Exception {
		String accessToken = JsonPath.read(register(uniqueEmail()), "$.accessToken");
		UserPrincipal user = tokenProvider.parseAccessToken(accessToken);

		mockMvc.perform(get("/api/test/tenant").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.organizationId").value(user.organizationId().toString()))
				.andExpect(jsonPath("$.userId").value(user.id().toString()));

		// MockMvc ran the request on this thread: the filter's finally block must have cleared it
		assertThatThrownBy(TenantContext::get).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void loginWithCorrectPasswordReturnsTokensAndIgnoresEmailCase() throws Exception {
		String email = uniqueEmail();
		register(email);

		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(email.toUpperCase(), PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void loginWithWrongPasswordOrUnknownEmailReturns401() throws Exception {
		String email = uniqueEmail();
		register(email);

		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(email, "wrong-password")))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(uniqueEmail(), PASSWORD)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void registeringAnExistingEmailReturns409() throws Exception {
		String email = uniqueEmail();
		register(email);

		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(registerJson("Other org", email.toUpperCase(), PASSWORD)))
				.andExpect(status().isConflict());
	}

	@Test
	void invalidRegistrationReturns400() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(registerJson("Acme", "not-an-email", "short")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void refreshTokenIssuesNewPairButIsRejectedAsBearerToken() throws Exception {
		String body = register(uniqueEmail());
		String accessToken = JsonPath.read(body, "$.accessToken");
		String refreshToken = JsonPath.read(body, "$.refreshToken");

		mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content(refreshJson(refreshToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());

		// Each token only works in its own place
		mockMvc.perform(get("/api/test/tenant").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content(refreshJson(accessToken)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void refreshForUserThatNoLongerExistsReturns401() throws Exception {
		// Validly signed, but no such user in the database
		String refreshToken = tokenProvider.generateRefreshToken(principal(Role.ADMIN));

		mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content(refreshJson(refreshToken)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void userRoleCannotDeleteProjectsOrTasks() throws Exception {
		String userToken = tokenProvider.generateAccessToken(principal(Role.USER));

		mockMvc.perform(delete("/api/projects/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete("/api/tasks/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void managerRolePassesDeleteRuleButNotUserManagement() throws Exception {
		String managerToken = tokenProvider.generateAccessToken(principal(Role.MANAGER));

		// 404, not 403: security let it through; no project controller exists yet (Phase 5)
		mockMvc.perform(delete("/api/projects/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
				.andExpect(status().isForbidden());
	}

	/** Registers a new organization and returns the response body. */
	private String register(String email) throws Exception {
		return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(registerJson("Org of " + email, email, PASSWORD)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	/** The database is shared by all tests in this class, so every test uses its own email. */
	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@test.example";
	}

	private static UserPrincipal principal(Role role) {
		return new UserPrincipal(UUID.randomUUID(), UUID.randomUUID(), uniqueEmail(), null, role);
	}

	private static String registerJson(String organizationName, String email, String password) {
		return """
				{"organizationName": "%s", "email": "%s", "password": "%s"}""".formatted(organizationName, email, password);
	}

	private static String loginJson(String email, String password) {
		return """
				{"email": "%s", "password": "%s"}""".formatted(email, password);
	}

	private static String refreshJson(String refreshToken) {
		return """
				{"refreshToken": "%s"}""".formatted(refreshToken);
	}

}
