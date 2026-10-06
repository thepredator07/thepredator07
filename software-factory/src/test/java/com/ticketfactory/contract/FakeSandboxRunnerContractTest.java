package com.ticketfactory.contract;

import com.ticketfactory.fake.FakeBehavior;
import com.ticketfactory.fake.FakeSandboxRunner;
import com.ticketfactory.integration.SandboxRunner;
import java.util.Optional;

class FakeSandboxRunnerContractTest extends SandboxRunnerContract {

    private final FakeSandboxRunner fake = new FakeSandboxRunner(FakeBehavior.SUCCEED);

    @Override
    protected SandboxRunner runner() {
        return fake;
    }

    @Override
    protected boolean exists(String sandboxId) {
        return fake.isLive(sandboxId);
    }

    @Override
    protected Optional<SandboxRunner> failingRunner() {
        return Optional.of(new FakeSandboxRunner(FakeBehavior.FAIL));
    }
}
