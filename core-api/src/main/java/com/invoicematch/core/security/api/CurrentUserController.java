package com.invoicematch.core.security.api;

import com.invoicematch.core.security.Actor;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal login/identity read contract for the UI. It exposes the authenticated
 * principal and its business roles over the existing HTTP Basic security; it
 * introduces no new authentication scheme.
 */
@RestController
@RequestMapping("/api")
public class CurrentUserController {

    private final AuthorizationService authorization;

    public CurrentUserController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping("/me")
    public CurrentUserView me() {
        Actor actor = authorization.actor();
        List<String> roles = actor.roles().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Role::name)
                .toList();
        return new CurrentUserView(actor.username(), roles);
    }
}
