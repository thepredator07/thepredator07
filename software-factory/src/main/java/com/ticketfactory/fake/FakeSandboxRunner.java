package com.ticketfactory.fake;

import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FakeSandboxRunner extends AbstractFake implements SandboxRunner {

    private final Set<String> live = ConcurrentHashMap.newKeySet();

    public FakeSandboxRunner(FakeBehavior configured) {
        super("sandbox", configured);
    }

    @Override
    public Sandbox prepare(TicketContext ticket) {
        if (nextCallFails(ticket)) {
            throw new StepFailedException("fake sandbox: container failed to start (simulated)");
        }
        String id = "fake-sbx-" + ticket.ticketId();
        live.add(id);
        return new Sandbox(id, "/workspace/" + ticket.repo() + "@" + ticket.branchName());
    }

    @Override
    public void destroy(String sandboxId) {
        live.remove(sandboxId);
    }

    public boolean isLive(String sandboxId) {
        return live.contains(sandboxId);
    }

    @Override
    public void reset() {
        super.reset();
        live.clear();
    }
}
