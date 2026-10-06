package com.ticketfactory.fake;

import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.TicketContext;

public class FakeChecksRunner extends AbstractFake implements ChecksRunner {

    public FakeChecksRunner(FakeBehavior configured) {
        super("checks", configured);
    }

    @Override
    public ChecksResult run(TicketContext ticket, String sandboxId) {
        if (nextCallFails(ticket)) {
            return new ChecksResult(false, """
                    [ERROR] Tests run: 48, Failures: 1
                    [ERROR] LoginServiceTest.rejectsExpiredToken:57 expected 401 but was 200 (simulated)""");
        }
        return new ChecksResult(true, "[INFO] Tests run: 48, Failures: 0 (simulated)\n[INFO] BUILD SUCCESS");
    }
}
