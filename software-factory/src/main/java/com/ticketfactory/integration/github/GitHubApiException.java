package com.ticketfactory.integration.github;

import com.ticketfactory.integration.StepFailedException;

/** A GitHub API call returned a client error that the caller may want to handle (404, 422, ...). */
public class GitHubApiException extends StepFailedException {

    private final int status;

    public GitHubApiException(int status, String message) {
        super("GitHub API " + status + ": " + message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
