package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.identity.LocalAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Built-in accounts, for admins (local sign-in only; with an identity provider, users and roles live there). Role
 * and password changes end the user's sessions; deleting a user also revokes their API tokens.
 */
@RestController
@RequestMapping("/api/v1/users")
class UsersController {

	private final ObjectProvider<LocalAuth> localAuth;

	UsersController(ObjectProvider<LocalAuth> localAuth) {
		this.localAuth = localAuth;
	}

	record NewUser(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password,
			@NotEmpty List<String> roles) {
	}

	record Roles(@NotEmpty List<String> roles) {
	}

	record Password(@NotBlank @Size(max = 200) String password) {
	}

	@GetMapping
	Flux<LocalAuth.Account> list() {
		return auth().accounts();
	}

	@PostMapping
	Mono<ResponseEntity<LocalAuth.Account>> create(@Valid @RequestBody NewUser user, @AuthenticationPrincipal Jwt admin) {
		SetupController.requireSession(admin);
		return auth().createUser(user.username(), user.password(), user.roles())
				.map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
	}

	@PutMapping("/{username}/roles")
	Mono<ResponseEntity<Void>> roles(@PathVariable String username, @Valid @RequestBody Roles roles,
			@AuthenticationPrincipal Jwt admin) {
		SetupController.requireSession(admin);
		return auth().setRoles(admin.getSubject(), username, roles.roles()).thenReturn(ResponseEntity.noContent().build());
	}

	@PutMapping("/{username}/password")
	Mono<ResponseEntity<Void>> password(@PathVariable String username, @Valid @RequestBody Password password,
			@AuthenticationPrincipal Jwt admin) {
		SetupController.requireSession(admin);
		return auth().resetPassword(username, password.password()).thenReturn(ResponseEntity.noContent().build());
	}

	@DeleteMapping("/{username}")
	Mono<ResponseEntity<Void>> delete(@PathVariable String username, @AuthenticationPrincipal Jwt admin) {
		SetupController.requireSession(admin);
		return auth().deleteUser(admin.getSubject(), username).thenReturn(ResponseEntity.noContent().build());
	}

	private LocalAuth auth() {
		LocalAuth auth = localAuth.getIfAvailable();
		if (auth == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "users are managed in the identity provider");
		}
		return auth;
	}
}
