package com.ticketfactory.integration;

/**
 * Runs the repo's build, tests and linters inside the sandbox: {@code FakeChecksRunner} in fake mode,
 * {@code SandboxChecksRunner} in real mode. Output is fed back to the agent, so it must be bounded.
 */
public interface ChecksRunner {

    ChecksResult run(TicketContext ticket, String sandboxId);

    record ChecksResult(boolean passed, String output) {
    }
}
