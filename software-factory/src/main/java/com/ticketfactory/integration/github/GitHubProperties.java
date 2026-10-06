package com.ticketfactory.integration.github;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the real GitHub client ({@code factory.integrations=real}), bound from {@code factory.github.*}.
 * Authenticate with either a GitHub App (recommended: narrow permissions, tokens that expire after an hour) or a
 * token. All secrets come from the environment.
 *
 * @param token                  {@code GITHUB_TOKEN}: fine-grained personal access token (or any bearer token)
 * @param appId                  {@code GITHUB_APP_ID}
 * @param appInstallationId      {@code GITHUB_APP_INSTALLATION_ID}
 * @param appPrivateKeyPath      {@code GITHUB_APP_PRIVATE_KEY_PATH}: the .pem GitHub generated for the App
 * @param minLabelerPermission   who may trigger the factory: issues labeled by someone with less are ignored
 * @param pageSize               items per page when listing (GitHub's max is 100)
 */
@ConfigurationProperties("factory.github")
public record GitHubProperties(
        @DefaultValue("https://api.github.com") String apiUrl,
        String token,
        String appId,
        String appInstallationId,
        String appPrivateKeyPath,
        @DefaultValue("WRITE") Permission minLabelerPermission,
        @DefaultValue("100") int pageSize,
        @DefaultValue("PT20S") Duration requestTimeout,
        @DefaultValue("PT10M") Duration permissionCacheTtl) {

    /** GitHub's repository permission levels, lowest first (the {@code permission} field of the collaborator API). */
    public enum Permission {
        NONE, READ, WRITE, ADMIN;

        public static Permission fromApi(String value) {
            return switch (value == null ? "none" : value) {
                case "admin" -> ADMIN;
                case "maintain", "write" -> WRITE;
                case "triage", "read" -> READ;
                default -> NONE;
            };
        }

        public boolean atLeast(Permission other) {
            return compareTo(other) >= 0;
        }
    }

    public boolean usesApp() {
        return notBlank(appId) || notBlank(appInstallationId) || notBlank(appPrivateKeyPath);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
