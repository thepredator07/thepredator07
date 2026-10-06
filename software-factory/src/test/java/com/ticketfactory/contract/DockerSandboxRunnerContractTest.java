package com.ticketfactory.contract;

import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** The real Docker sandbox meets the same contract as the fake. Needs Docker (as all DB tests do). */
class DockerSandboxRunnerContractTest extends SandboxRunnerContract {

    private DockerSandboxFixture fx;

    @BeforeEach
    void start() throws Exception {
        fx = new DockerSandboxFixture();
    }

    @AfterEach
    void stop() throws Exception {
        fx.close();
    }

    @Override
    protected SandboxRunner runner() {
        return fx.runner;
    }

    @Override
    protected String repo() {
        return DockerSandboxFixture.REPO;
    }

    @Override
    protected TicketContext ticket(long id) {
        long real = fx.id(id);
        return new TicketContext(real, repo(), (int) id, "Contract", "", "factory/" + real);
    }

    @Override
    protected boolean exists(String sandboxId) {
        return fx.runner.inspect(sandboxId) != null;
    }

    /** An image that cannot exist: the pull fails, so the sandbox cannot start. */
    @Override
    protected java.util.Optional<SandboxRunner> failingRunner() {
        return java.util.Optional.of(fx.runnerWithImage("factory-test.invalid/no-such-image:missing"));
    }

    @Override
    protected boolean isPublished(String repo, String branch) {
        return fx.remoteHead(branch) != null;
    }

    /** Same name is not enough: a recreated container has the same name. The work inside must survive. */
    @org.junit.jupiter.api.Test
    void preparingAgainReusesTheContainerAndKeepsWorkInProgress() {
        TicketContext t = ticket(201);
        var first = fx.runner.prepare(t);
        String containerId = fx.runner.inspect(first.id()).getId();
        fx.runner.exec(first.id(), java.time.Duration.ofSeconds(10), "sh", "-c", "echo wip > /workspace/repo/WIP");

        var again = fx.runner.prepare(t);

        org.assertj.core.api.Assertions.assertThat(fx.runner.inspect(again.id()).getId()).isEqualTo(containerId);
        org.assertj.core.api.Assertions.assertThat(fx.runner.exec(again.id(), java.time.Duration.ofSeconds(10),
                "cat", "/workspace/repo/WIP").output()).isEqualTo("wip\n");
        fx.runner.destroy(again.id());
    }

    /** A sandbox that stopped (Docker restart) has lost its tmpfs workspace, so it is replaced, not reused. */
    @org.junit.jupiter.api.Test
    void aStoppedSandboxIsReplacedWithAFreshOne() {
        TicketContext t = ticket(202);
        var first = fx.runner.prepare(t);
        String containerId = fx.runner.inspect(first.id()).getId();
        fx.docker.stopContainerCmd(first.id()).withTimeout(1).exec();

        var again = fx.runner.prepare(t);

        org.assertj.core.api.Assertions.assertThat(fx.runner.inspect(again.id()).getId()).isNotEqualTo(containerId);
        org.assertj.core.api.Assertions.assertThat(fx.runner.exec(again.id(), java.time.Duration.ofSeconds(10),
                "git", "-C", "/workspace/repo", "rev-parse", "--abbrev-ref", "HEAD").output().strip())
                .isEqualTo(t.branchName());
        fx.runner.destroy(again.id());
    }
}
