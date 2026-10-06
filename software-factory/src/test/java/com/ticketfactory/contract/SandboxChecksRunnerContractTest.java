package com.ticketfactory.contract;

import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.ChecksRunner.ChecksResult;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.checks.ChecksProperties;
import com.ticketfactory.integration.checks.SampleRepo;
import com.ticketfactory.integration.checks.SandboxChecksRunner;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.HostGit;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** The real checks runner meets the same contract as the fake, on the sample repo in a real Docker sandbox. */
class SandboxChecksRunnerContractTest extends ChecksRunnerContract {

    private DockerSandboxFixture fx;
    private SandboxChecksRunner runner;
    private TicketContext ticket;
    private String sandboxId;

    @BeforeEach
    void start() throws Exception {
        fx = new DockerSandboxFixture();
        fx.commitToMain(SampleRepo.passing(), "sample");
        runner = new SandboxChecksRunner(fx.runner,
                new HostGit(fx.root.resolve("checks-host"), "file://" + fx.root.resolve("remotes") + "/{repo}.git",
                        null, Duration.ofMinutes(2)),
                new ChecksProperties(".factory.yml", "", Duration.ofMinutes(10), Duration.ofMinutes(30)), "main");
        ticket = ticket(1);
        sandboxId = fx.runner.prepare(ticket).id();
    }

    @AfterEach
    void stop() throws Exception {
        fx.close();
    }

    private TicketContext ticket(long n) {
        long id = fx.id(n);
        return new TicketContext(id, DockerSandboxFixture.REPO, (int) n, "Contract", "", "factory/" + id);
    }

    @Override
    protected ChecksRunner runner() {
        return runner;
    }

    @Override
    protected TicketContext passingTicket() {
        return ticket;
    }

    @Override
    protected String sandboxId() {
        return sandboxId;
    }

    /** A second ticket whose "agent" broke the code. */
    @Override
    protected Optional<ChecksResult> runFailingChecks() {
        TicketContext broken = ticket(2);
        String sbx = fx.runner.prepare(broken).id();
        fx.runner.exec(sbx, Duration.ofSeconds(30), "sh", "-c",
                "cd /workspace/repo && sed -i 's/a + b/a - b/' calc.py");
        return Optional.of(runner.run(broken, sbx));
    }
}
