package com.ticketfactory.contract;

import com.ticketfactory.fake.FakeAgentRunner;
import com.ticketfactory.fake.FakeProperties;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.TicketContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

class FakeAgentRunnerContractTest extends AgentRunnerContract {

    private static FakeAgentRunner agent(String mode) {
        return new FakeAgentRunner(new FakeProperties.Agent(mode, 1, 6, 4000, 800, new BigDecimal("0.12"),
                Duration.ZERO));
    }

    private static TicketContext ticket(String body) {
        return new TicketContext(1, "example-org/contract", 1, "Contract ticket", body, "factory/1");
    }

    @Override
    protected AgentRunner runner() {
        return agent("succeed");
    }

    @Override
    protected TicketContext easyTicket() {
        return ticket("");
    }

    @Override
    protected TicketContext slowTicket() {
        return ticket("fake-agent-delay: PT30S");
    }

    @Override
    protected Optional<AgentRunner> failingRunner() {
        return Optional.of(agent("fail"));
    }
}
