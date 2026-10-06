package com.ticketfactory.contract;

import com.ticketfactory.fake.FakeBehavior;
import com.ticketfactory.fake.FakeChecksRunner;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.TicketContext;
import java.util.Optional;

class FakeChecksRunnerContractTest extends ChecksRunnerContract {

    @Override
    protected ChecksRunner runner() {
        return new FakeChecksRunner(FakeBehavior.SUCCEED);
    }

    @Override
    protected TicketContext passingTicket() {
        return new TicketContext(1, "example-org/contract", 1, "Contract ticket", "", "factory/1");
    }

    @Override
    protected String sandboxId() {
        return "fake-sbx-1";
    }

    @Override
    protected Optional<ChecksRunner> failingRunner() {
        return Optional.of(new FakeChecksRunner(FakeBehavior.FAIL));
    }
}
