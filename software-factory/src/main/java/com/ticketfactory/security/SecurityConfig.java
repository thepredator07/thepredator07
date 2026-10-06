package com.ticketfactory.security;

import com.ticketfactory.FactoryProperties;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dashboard sign-in and CSRF protection.
 *
 * <ul>
 *   <li>{@code github}: sign in with GitHub (OAuth); only logins in {@code FACTORY_ALLOWED_USERS} get in.</li>
 *   <li>{@code basic}: one admin account (form login, or HTTP Basic for scripts).</li>
 *   <li>{@code none}: open, fake mode only (local demo).</li>
 * </ul>
 * In every mode, POST forms need a CSRF token (Thymeleaf adds it to {@code th:action} forms), so another site can't
 * make a signed-in browser cancel tickets. Open: health, the GitHub webhook (it checks its own signature) and static
 * files. Metrics are on the management port, which is not published.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityProperties.Mode securityMode(SecurityProperties security, FactoryProperties factory) {
        SecurityProperties.Mode mode = security.effectiveMode(factory.fakeMode());
        if (mode == SecurityProperties.Mode.NONE) {
            log.warn("Dashboard sign-in is OFF (fake mode). Anyone who can reach this port can use the dashboard.");
        }
        return mode;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityProperties.Mode mode, SecurityProperties security,
                                    org.springframework.core.env.Environment env) throws Exception {
        // On their own port, actuator endpoints are only reachable from inside the deployment (not published).
        boolean separateManagementPort = ManagementPortType.get(env) == ManagementPortType.DIFFERENT;
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/**", "/api/fake/**"));
        http.headers(h -> h.frameOptions(f -> f.deny()));
        http.authorizeHttpRequests(auth -> {
            if (separateManagementPort) {
                auth.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll();
            }
            auth.requestMatchers("/actuator/health", "/actuator/health/**", "/webhooks/github", "/app.css",
                    "/favicon.ico", "/error", "/login", "/login/**").permitAll();
            if (mode == SecurityProperties.Mode.NONE) {
                auth.anyRequest().permitAll();
            } else {
                auth.anyRequest().authenticated();
            }
        });
        switch (mode) {
            case GITHUB -> {
                ClientRegistrationRepository clients = new InMemoryClientRegistrationRepository(
                        CommonOAuth2Provider.GITHUB.getBuilder("github")
                                .clientId(security.githubClientId()).clientSecret(security.githubClientSecret())
                                .build());
                Set<String> allowed = Set.copyOf(security.normalizedAllowedUsers());
                http.oauth2Login(o -> o.clientRegistrationRepository(clients)
                        .userInfoEndpoint(u -> u.userService(allowlisted(allowed))));
            }
            case BASIC -> http.formLogin(f -> { }).httpBasic(b -> { });
            case NONE -> { }
        }
        if (mode != SecurityProperties.Mode.NONE) {
            http.logout(l -> l.logoutSuccessUrl("/login?logout"));
        }
        return http.build();
    }

    @Bean
    UserDetailsService users(SecurityProperties.Mode mode, SecurityProperties security) {
        if (mode != SecurityProperties.Mode.BASIC) {
            return new InMemoryUserDetailsManager(); // nobody: no generated default password either
        }
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(security.adminUser())
                .password(encoder.encode(security.adminPassword())).roles("ADMIN").build());
    }

    /** Loads the GitHub user as usual, then refuses anyone not on the list. */
    static OAuth2UserService<OAuth2UserRequest, OAuth2User> allowlisted(Set<String> allowed) {
        return allowlisted(allowed, new DefaultOAuth2UserService());
    }

    static OAuth2UserService<OAuth2UserRequest, OAuth2User> allowlisted(
            Set<String> allowed, OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
        return request -> {
            OAuth2User user = delegate.loadUser(request);
            Object login = user.getAttribute("login");
            if (login == null || !allowed.contains(login.toString().toLowerCase(Locale.ROOT))) {
                log.warn("Refused dashboard sign-in for GitHub user {}", login);
                throw new OAuth2AuthenticationException(new OAuth2Error("access_denied",
                        "GitHub user " + login + " is not allowed on this factory", null));
            }
            return user;
        };
    }
}
