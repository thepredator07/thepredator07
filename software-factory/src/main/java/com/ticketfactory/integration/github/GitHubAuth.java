package com.ticketfactory.integration.github;

/** Produces the {@code Authorization} header value for GitHub API calls. */
public interface GitHubAuth {

    String authorizationHeader();

    /** Static bearer token (fine-grained personal access token, or a token issued elsewhere). */
    static GitHubAuth token(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("GITHUB_TOKEN is empty");
        }
        String header = "Bearer " + token.trim();
        return () -> header;
    }
}
