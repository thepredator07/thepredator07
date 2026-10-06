package com.ticketfactory.integration;

import java.util.List;

/**
 * An isolated workspace with the repo checked out on the ticket's branch. Fake: {@code FakeSandboxRunner}. Real
 * (M3): {@code DockerSandboxRunner}, one locked-down container per ticket attempt.
 */
public interface SandboxRunner {

    /** Creates the ticket's sandbox, or returns the existing one (idempotent per ticket). */
    Sandbox prepare(TicketContext ticket);

    /**
     * Pushes the ticket's branch ({@code ticket.branchName()}) from the sandbox to the repo's remote. Idempotent.
     * Must refuse anything outside {@link BranchPolicy}: the factory never pushes to main.
     */
    void publishBranch(TicketContext ticket, String sandboxId);

    /** Removes the sandbox. Idempotent; unknown ids are ignored. */
    void destroy(String sandboxId);

    /** Every sandbox this runner manages right now, so the janitor can remove ones whose ticket is gone or finished. */
    List<Sandbox> list();

    record Sandbox(String id, long ticketId, String workdir) {
    }
}
