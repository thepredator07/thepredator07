package com.ticketfactory.integration.phase2;

/**
 * TODO(phase-2): One Docker container per ticket with the repo cloned on factory/<ticket-id>.
 *
 * <p>Deliberately not implemented and not a Spring bean in Phase 1. Implement
 * {@link com.ticketfactory.integration.SandboxRunner} and register it in place of the fake
 * (see {@code com.ticketfactory.fake.FakeIntegrationsConfig}).
 */
final class DockerSandboxRunner {

    private DockerSandboxRunner() {
        throw new UnsupportedOperationException("Phase 2: DockerSandboxRunner is not implemented yet");
    }
}
