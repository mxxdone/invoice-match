package com.invoicematch.core.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local, non-production demo identities. These exist only so the API is
 * runnable and testable without a real IdP; passwords are placeholders and must
 * never be reused. A production deployment replaces this with a real
 * {@code UserDetailsService} / OAuth resource server.
 */
@ConfigurationProperties(prefix = "security.demo")
public class DemoSecurityProperties {

    private List<DemoUser> users = new ArrayList<>();

    public List<DemoUser> getUsers() {
        return users;
    }

    public void setUsers(List<DemoUser> users) {
        this.users = users;
    }

    public static class DemoUser {

        private String username;
        private String password;
        private List<String> roles = new ArrayList<>();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }
}
