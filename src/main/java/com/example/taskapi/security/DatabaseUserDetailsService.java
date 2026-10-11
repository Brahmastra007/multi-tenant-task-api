package com.example.taskapi.security;

import com.example.taskapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads users by email for login. Spring Security reports a missing user as generic "bad credentials". */
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

	private final UserRepository userRepository;

	@Override
	public UserPrincipal loadUserByUsername(String email) {
		return userRepository.findByEmail(email)
				.map(UserPrincipal::from)
				.orElseThrow(() -> new UsernameNotFoundException("No user with that email"));
	}

}
