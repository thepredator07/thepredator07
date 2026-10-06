package com.ticketfactory.integration;

/**
 * Runs the repo's build, tests and linters inside the sandbox. Phase 1: {@code FakeChecksRunner}.
 * TODO(phase-2): execute the repo's configured check command in the Docker sandbox and capture output.
 */
public interface ChecksRunner {

    ChecksResult run(TicketContext ticket, String sandboxId);

    record ChecksResult(boolean passed, String output) {
    }
}
