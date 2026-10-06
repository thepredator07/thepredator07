package com.ticketfactory.fake;

import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FakeSandboxRunner extends AbstractFake implements SandboxRunner {

    private final Set<String> live = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();

    public FakeSandboxRunner(FakeBehavior configured) {
        super("sandbox", configured);
    }

    @Override
    public Sandbox prepare(TicketContext ticket) {
        if (nextCallFails(ticket)) {
            throw new StepFailedException("fake sandbox: container failed to start (simulated)");
        }
        // Idempotent per ticket, like the real runner must be: a sandbox left behind by a crashed run is reused.
        String id = "fake-sbx-" + ticket.ticketId();
        if (live.add(id)) {
            created.incrementAndGet();
        }
        return new Sandbox(id, "/workspace/" + ticket.repo() + "@" + ticket.branchName());
    }

    @Override
    public void destroy(String sandboxId) {
        live.remove(sandboxId);
    }

    public boolean isLive(String sandboxId) {
        return live.contains(sandboxId);
    }

    /** How many sandboxes were actually created (reuses don't count). */
    public int createdCount() {
        return created.get();
    }

    public int liveCount() {
        return live.size();
    }

    @Override
    public void reset() {
        super.reset();
        live.clear();
        created.set(0);
    }
}
