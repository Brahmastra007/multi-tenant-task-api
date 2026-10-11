package com.example.taskapi.exception;

import org.springframework.security.core.AuthenticationException;

/** A JWT that is malformed, wrongly signed, expired or of the wrong type. Spring Security answers 401. */
public class InvalidTokenException extends AuthenticationException {

	public InvalidTokenException(String message) {
		super(message);
	}

	public InvalidTokenException(String message, Throwable cause) {
		super(message, cause);
	}

}
