package com.ticketfactory.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketfactory.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** {@code FACTORY_SECURITY=basic}: one admin account; everything but health and the webhook needs it. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"factory.security.mode=basic", "factory.security.admin-user=boss",
        "factory.security.admin-password=correct-horse-battery"})
class BasicSignInTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void pagesNeedSigningIn() throws Exception {
        // A browser is sent to the login form; a script gets 401.
        mvc.perform(get("/tickets").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/stats").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/fake/pull-requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void theAdminGetsInAndSeesTheirNameAndASignOutButton() throws Exception {
        mvc.perform(get("/tickets").with(httpBasic("boss", "correct-horse-battery"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("boss")))
                .andExpect(content().string(containsString("Sign out")));
    }

    @Test
    void aWrongPasswordIsRefused() throws Exception {
        mvc.perform(get("/tickets").with(httpBasic("boss", "wrong-password-123"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/tickets").with(httpBasic("admin", "correct-horse-battery")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedInActionsStillNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/tickets/1/cancel").with(httpBasic("boss", "correct-horse-battery")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/tickets/999999/cancel").with(httpBasic("boss", "correct-horse-battery")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theWebhookEndpointIsNotBehindSignInOrCsrf() throws Exception {
        // 404 because no webhook secret is set here, not 401/403: the endpoint checks GitHub's signature itself.
        mvc.perform(post("/webhooks/github").content("{}")).andExpect(status().isNotFound());
    }

    @Test
    void healthStaysOpenForProbes() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /** With actuator on the main port (as in this test), only health is open; the rest needs sign-in. */
    @Test
    void otherActuatorEndpointsNeedSignInOnTheMainPort() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info").with(httpBasic("boss", "correct-horse-battery"))).andExpect(status().isOk());
    }
}
