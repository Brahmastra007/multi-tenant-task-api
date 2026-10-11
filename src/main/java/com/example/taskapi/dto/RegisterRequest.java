package com.example.taskapi.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creates a new organization with this user as its first ADMIN. No org id or role: those are never client input. */
public record RegisterRequest(
		@NotBlank @Size(max = 255) String organizationName,
		@NotBlank @Email @Size(max = 255) String email,
		// BCrypt only uses the first 72 bytes of a password
		@NotBlank @Size(min = 8, max = 72) String password) {
}
