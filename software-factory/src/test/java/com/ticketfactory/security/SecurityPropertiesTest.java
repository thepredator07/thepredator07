package com.ticketfactory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

    private static SecurityProperties props(String mode, List<String> users, String password, String clientId) {
        return new SecurityProperties(mode, users, "admin", password, clientId, clientId.isEmpty() ? "" : "secret");
    }

    @Test
    void fakeModeIsOpenByDefault() {
        assertThat(props("", List.of(), "", "").effectiveMode(true)).isEqualTo(SecurityProperties.Mode.NONE);
    }

    @Test
    void realModeRefusesToStartWithoutSignIn() {
        assertThatThrownBy(() -> props("", List.of(), "", "").effectiveMode(false))
                .hasMessageContaining("needs dashboard sign-in");
        assertThatThrownBy(() -> props("none", List.of(), "", "").effectiveMode(false))
                .hasMessageContaining("only allowed with fake integrations");
    }

    @Test
    void githubModeNeedsAnOauthAppAndAnAllowList() {
        assertThatThrownBy(() -> props("github", List.of("alice"), "", "").effectiveMode(false))
                .hasMessageContaining("GITHUB_OAUTH_CLIENT_ID");
        assertThatThrownBy(() -> props("github", List.of(" "), "", "id").effectiveMode(false))
                .hasMessageContaining("FACTORY_ALLOWED_USERS");
        assertThat(props("GitHub", List.of("Alice", " bob "), "", "id").effectiveMode(false))
                .isEqualTo(SecurityProperties.Mode.GITHUB);
        assertThat(props("github", List.of("Alice", " bob "), "", "id").normalizedAllowedUsers())
                .containsExactly("alice", "bob");
    }

    @Test
    void basicModeNeedsALongEnoughPassword() {
        assertThatThrownBy(() -> props("basic", List.of(), "short", "").effectiveMode(false))
                .hasMessageContaining("at least 12");
        assertThat(props("basic", List.of(), "correct-horse-battery", "").effectiveMode(false))
                .isEqualTo(SecurityProperties.Mode.BASIC);
    }

    @Test
    void unknownModesAreRejected() {
        assertThatThrownBy(() -> props("ldap", List.of(), "", "").effectiveMode(true))
                .hasMessageContaining("github, basic or none");
    }
}
