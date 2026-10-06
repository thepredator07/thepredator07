package com.ticketfactory.integration;

/**
 * Creates an isolated workspace with the repo checked out on the ticket branch. Phase 1: {@code FakeSandboxRunner}.
 * TODO(phase-2): Docker implementation (one container per ticket, no network except GitHub + model API).
 */
public interface SandboxRunner {

    Sandbox prepare(TicketContext ticket);

    void destroy(String sandboxId);

    record Sandbox(String id, String workdir) {
    }
}
