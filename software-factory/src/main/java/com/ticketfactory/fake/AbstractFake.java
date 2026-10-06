package com.ticketfactory.fake;

import com.ticketfactory.integration.TicketContext;

/** Shared plumbing: global default behaviour (overridable at runtime), per-ticket script, per-ticket call count. */
abstract class AbstractFake {

    private final String scriptKey;
    private final FakeBehavior configured;
    private volatile FakeBehavior override;
    protected final CallCounter calls = new CallCounter();

    AbstractFake(String scriptKey, FakeBehavior configured) {
        this.scriptKey = scriptKey;
        this.configured = configured;
    }

    /** Overrides the configured default for all tickets (tests). Pass null to go back to config. */
    public void setDefaultBehavior(FakeBehavior behavior) {
        this.override = behavior;
    }

    public void reset() {
        override = null;
        calls.reset();
    }

    public int callCount(long ticketId) {
        return calls.count(ticketId);
    }

    /** Records a call and decides whether it fails. */
    protected boolean nextCallFails(TicketContext ticket) {
        FakeBehavior fallback = override != null ? override : configured;
        FakeBehavior behavior = FakeScript.parse(ticket.body()).behavior(scriptKey, fallback);
        return behavior.shouldFail(calls.next(ticket.ticketId()));
    }
}
