package com.ticketfactory.security;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Who may use the dashboard, bound from {@code factory.security.*}.
 *
 * @param mode           {@code github} (sign in with GitHub, only {@code allowedUsers}), {@code basic} (one admin
 *                       account) or {@code none} (open; allowed in fake mode only). Empty: {@code none} in fake mode,
 *                       and startup fails in real mode
 * @param allowedUsers   GitHub logins allowed in {@code github} mode (case-insensitive)
 * @param adminUser      user name in {@code basic} mode
 * @param adminPassword  password in {@code basic} mode, at least 12 characters
 * @param githubClientId     OAuth app client id for {@code github} mode
 * @param githubClientSecret OAuth app client secret for {@code github} mode
 */
@ConfigurationProperties("factory.security")
public record SecurityProperties(
        @DefaultValue("") String mode,
        @DefaultValue List<String> allowedUsers,
        @DefaultValue("admin") String adminUser,
        @DefaultValue("") String adminPassword,
        @DefaultValue("") String githubClientId,
        @DefaultValue("") String githubClientSecret) {

    public enum Mode { GITHUB, BASIC, NONE }

    /** The mode in effect, given whether the factory runs on fakes. */
    public Mode effectiveMode(boolean fakeMode) {
        if (mode.isBlank()) {
            if (fakeMode) {
                return Mode.NONE;
            }
            throw new IllegalStateException("factory.integrations=real needs dashboard sign-in: set FACTORY_SECURITY="
                    + "github (with GITHUB_OAUTH_CLIENT_ID, GITHUB_OAUTH_CLIENT_SECRET, FACTORY_ALLOWED_USERS) or "
                    + "basic (with FACTORY_ADMIN_PASSWORD).");
        }
        Mode m;
        try {
            m = Mode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("FACTORY_SECURITY must be github, basic or none, not '" + mode + "'");
        }
        if (m == Mode.NONE && !fakeMode) {
            throw new IllegalStateException("FACTORY_SECURITY=none is only allowed with fake integrations: the real"
                    + " dashboard can cancel tickets that spend money and touch real repositories.");
        }
        if (m == Mode.GITHUB && (githubClientId.isBlank() || githubClientSecret.isBlank())) {
            throw new IllegalStateException("FACTORY_SECURITY=github needs GITHUB_OAUTH_CLIENT_ID and"
                    + " GITHUB_OAUTH_CLIENT_SECRET (a GitHub OAuth app; callback URL"
                    + " <factory URL>/login/oauth2/code/github).");
        }
        if (m == Mode.GITHUB && normalizedAllowedUsers().isEmpty()) {
            throw new IllegalStateException("FACTORY_SECURITY=github needs FACTORY_ALLOWED_USERS (GitHub logins,"
                    + " comma-separated); without it nobody could sign in.");
        }
        if (m == Mode.BASIC && adminPassword.length() < 12) {
            throw new IllegalStateException("FACTORY_SECURITY=basic needs FACTORY_ADMIN_PASSWORD with at least 12"
                    + " characters.");
        }
        return m;
    }

    public List<String> normalizedAllowedUsers() {
        return allowedUsers.stream().map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }
}
