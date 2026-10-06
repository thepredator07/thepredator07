package com.ticketfactory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketfactory.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** {@code FACTORY_SECURITY=github}: sign in through GitHub, and only listed logins get in. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"factory.security.mode=github", "factory.security.allowed-users=Alice,bob",
        "factory.security.github-client-id=test-client-id", "factory.security.github-client-secret=test-secret"})
class GitHubSignInTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void pagesSendYouToGitHub() throws Exception {
        // With GitHub as the only way in, Spring skips the login page and goes straight to it.
        mvc.perform(get("/tickets").accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/oauth2/authorization/github"));
        MvcResult start = mvc.perform(get("/oauth2/authorization/github")).andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(start.getResponse().getRedirectedUrl())
                .startsWith("https://github.com/login/oauth/authorize").contains("client_id=test-client-id");
    }

    @Test
    void onlyListedLoginsAreLetIn() {
        var service = SecurityConfig.allowlisted(Set.of("alice", "bob"), request ->
                new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                        Map.of("login", request.getAdditionalParameters().get("login")), "login"));

        assertThat(service.loadUser(request("ALICE")).getAttributes()).containsEntry("login", "ALICE");
        assertThat(service.loadUser(request("bob")).getName()).isEqualTo("bob");
        assertThatThrownBy(() -> service.loadUser(request("mallory")))
                .isInstanceOf(OAuth2AuthenticationException.class).hasMessageContaining("not allowed");
    }

    private static OAuth2UserRequest request(String login) {
        var registration = CommonOAuth2Provider.GITHUB.getBuilder("github").clientId("x").clientSecret("y").build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", null, null);
        return new OAuth2UserRequest(registration, token, Map.of("login", login));
    }
}
