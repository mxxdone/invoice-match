package com.invoicematch.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Demo credentials are local/test only and encoded, the reserved identity can
 * never be a login, and the default deployable configuration fails closed with
 * no configured identities.
 */
class DemoCredentialsConfigurationTest {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void withNoConfiguredUsersTheApiFailsClosed() {
        UserDetailsService users = configuration.demoUserDetailsService(new DemoSecurityProperties());

        assertThat(users).isInstanceOf(InMemoryUserDetailsManager.class);
        assertThatThrownBy(() -> users.loadUserByUsername("submitter"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void reservedIdentityCannotBeConfiguredAsALogin() {
        DemoSecurityProperties properties = new DemoSecurityProperties();
        DemoSecurityProperties.DemoUser reserved = new DemoSecurityProperties.DemoUser();
        reserved.setUsername(SecurityPrincipals.RESERVED);
        reserved.setPassword("{noop}x");
        reserved.setRoles(List.of("SUBMITTER"));
        properties.setUsers(List.of(reserved));

        assertThatThrownBy(() -> configuration.demoUserDetailsService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved identity");
    }

    @Test
    void unknownRoleIsRejected() {
        DemoSecurityProperties properties = new DemoSecurityProperties();
        DemoSecurityProperties.DemoUser user = new DemoSecurityProperties.DemoUser();
        user.setUsername("auditor");
        user.setPassword("{noop}x");
        user.setRoles(List.of("AUDITOR"));
        properties.setUsers(List.of(user));

        assertThatThrownBy(() -> configuration.demoUserDetailsService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown role");
    }

    @Test
    void defaultConfigurationShipsNoCredentialsAndLocalProfileUsesHashes() throws IOException {
        String defaults = resource("/application.yml");
        assertThat(defaults).doesNotContain("security:").doesNotContain("demo:");

        String local = resource("/application-local.yml");
        assertThat(local).contains("security:").contains("demo:").contains("{bcrypt}");
        assertThat(local).doesNotContain("{noop}");

        String test = resource("/application-test.yml");
        assertThat(test).contains("{bcrypt}").doesNotContain("{noop}");
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = DemoCredentialsConfigurationTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath resource " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
