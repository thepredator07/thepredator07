package com.ticketfactory.integration.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketfactory.integration.ChecksRunner.ChecksResult;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.UnrecoverableStepException;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.HostGit;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link SandboxChecksRunner} on real Docker, with a local bare repo standing in for GitHub. */
class SandboxChecksRunnerTest {

    private DockerSandboxFixture fx;
    private SandboxChecksRunner checks;
    private int nextTicket = 1;

    @BeforeEach
    void start() throws Exception {
        fx = new DockerSandboxFixture();
        checks = runner(new ChecksProperties(".factory.yml", "", Duration.ofMinutes(10), Duration.ofMinutes(30)));
    }

    @AfterEach
    void stop() throws Exception {
        fx.close();
    }

    private SandboxChecksRunner runner(ChecksProperties props) {
        HostGit git = new HostGit(fx.root.resolve("checks-host"), "file://" + fx.root.resolve("remotes") + "/{repo}.git",
                null, Duration.ofMinutes(2));
        return new SandboxChecksRunner(fx.runner, git, props, "main");
    }

    private TicketContext ticket() {
        long id = fx.id(nextTicket++);
        return new TicketContext(id, DockerSandboxFixture.REPO, (int) id, "Checks", "", "factory/" + id);
    }

    /** A sandbox on the current main, as the pipeline would have prepared it. */
    private String sandbox(TicketContext t) {
        return fx.runner.prepare(t).id();
    }

    private void inSandbox(String sandboxId, String script) {
        var r = fx.runner.exec(sandboxId, Duration.ofSeconds(30), "sh", "-c", "cd /workspace/repo && " + script);
        assertThat(r.ok()).as(r.output()).isTrue();
    }

    @Test
    void aRepoWithAKnownFailingTestFailsAndTheOutputNamesTheTest() {
        fx.commitToMain(SampleRepo.withKnownFailingTest(), "sample with a bug");
        TicketContext t = ticket();

        ChecksResult r = checks.run(t, sandbox(t));

        assertThat(r.passed()).isFalse();
        assertThat(r.output())
                .startsWith("$ python3 -m unittest -v")
                .contains("FAIL: test_add_negative")
                .contains("AssertionError: -1 != 5")
                .contains("test_add_small (test_calc.CalcTest")
                .endsWith("[checks exited with code 1 after " + r.output().replaceAll("(?s).*after (\\d+)s]$", "$1")
                        + "s]");
    }

    @Test
    void checksRunOnTheAgentsWorkSoFixingTheBugMakesThemPass() {
        fx.commitToMain(SampleRepo.withKnownFailingTest(), "sample with a bug");
        TicketContext t = ticket();
        String sbx = sandbox(t);
        assertThat(checks.run(t, sbx).passed()).isFalse();

        // What the agent would do: change the code (left uncommitted on purpose; checks see the working tree).
        inSandbox(sbx, "sed -i 's/abs(a) + b/a + b/' calc.py");
        ChecksResult r = checks.run(t, sbx);

        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(r.output()).contains("Ran 2 tests").contains("OK").endsWith("s]");
    }

    @Test
    void theAgentCannotWeakenTheChecksByEditingItsCopyOfTheConfig() {
        fx.commitToMain(SampleRepo.withKnownFailingTest(), "sample with a bug");
        TicketContext t = ticket();
        String sbx = sandbox(t);

        inSandbox(sbx, "printf 'checks:\\n  command: \"true\"\\n' > .factory.yml && git commit -qam 'make checks pass'"
                + " && git branch -f main HEAD");
        ChecksResult r = checks.run(t, sbx);

        assertThat(r.passed()).as("the command comes from main on the host, not from the sandbox").isFalse();
        assertThat(r.output()).startsWith("$ python3 -m unittest -v").contains("FAIL: test_add_negative");
    }

    @Test
    void aChangeToTheConfigOnMainIsUsedByTheNextRun() {
        fx.commitToMain(SampleRepo.passing(), "sample");
        TicketContext t = ticket();
        String sbx = sandbox(t);
        assertThat(checks.run(t, sbx).passed()).isTrue();

        fx.commitToMain(Map.of(".factory.yml", "checks:\n  command: echo stricter checks && exit 3\n"), "stricter");
        ChecksResult r = checks.run(t, sbx);

        assertThat(r.passed()).isFalse();
        assertThat(r.output()).contains("stricter checks").contains("exited with code 3");
    }

    @Test
    void aHangingBuildIsStoppedAtTheTimeoutReportedAsFailedAndLeavesNoProcessBehind() {
        fx.commitToMain(Map.of(".factory.yml", "checks:\n  command: echo starting; sleep 600 & sleep 500\n"
                + "  timeout: 3s\n"), "hangs");
        TicketContext t = ticket();
        String sbx = sandbox(t);

        long started = System.nanoTime();
        ChecksResult r = checks.run(t, sbx);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(r.passed()).isFalse();
        assertThat(r.output()).contains("starting").endsWith("[checks timed out after PT3S and were stopped]");
        assertThat(took).isLessThan(Duration.ofSeconds(20));
        assertThat(fx.runner.exec(sbx, Duration.ofSeconds(10), "ps", "-eo", "args").output())
                .as("the build and the background process it started are gone, not even zombies")
                .doesNotContain("sleep 600", "sleep 500", "defunct");
    }

    @Test
    void aBuildThatIgnoresSigtermIsKilled() {
        fx.commitToMain(Map.of(".factory.yml", "checks:\n  command: trap '' TERM; while true; do sleep 1; done\n"
                + "  timeout: 2s\n"), "stubborn");
        TicketContext t = ticket();
        String sbx = sandbox(t);

        long started = System.nanoTime();
        ChecksResult r = checks.run(t, sbx);

        assertThat(r.passed()).isFalse();
        assertThat(r.output()).contains("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
        assertThat(fx.runner.exec(sbx, Duration.ofSeconds(10), "ps", "-eo", "args").output())
                .doesNotContain("sleep 1", "while", "defunct");
    }

    @Test
    void floodingOutputIsCutToTheLimitKeepingTheStartAndTheEnd() {
        fx.commitToMain(Map.of(".factory.yml", "checks:\n  command: echo FIRST-LINE; yes 'noise noise noise'"
                + " | head -c 30000000; echo; echo LAST-LINE; exit 1\n"), "verbose");
        TicketContext t = ticket();

        ChecksResult r = checks.run(t, sandbox(t));

        assertThat(r.passed()).isFalse();
        assertThat(r.output()).hasSizeLessThanOrEqualTo(SandboxChecksRunner.MAX_OUTPUT_CHARS)
                .contains("FIRST-LINE").contains("LAST-LINE").contains("bytes of output omitted")
                .endsWith("[checks exited with code 1 after " + r.output().replaceAll("(?s).*after (\\d+)s]$", "$1")
                        + "s]");
    }

    @Test
    void checksHaveNoNetwork() {
        fx.commitToMain(Map.of(".factory.yml", "checks:\n  command: git ls-remote https://github.com/git/git HEAD\n"),
                "needs network");
        TicketContext t = ticket();

        ChecksResult r = checks.run(t, sandbox(t));

        assertThat(r.passed()).isFalse();
        assertThat(r.output()).containsIgnoringCase("could not resolve host");
    }

    @Test
    void aRepoWithoutConfigFailsTheTicketAtOnceWhenNoDefaultIsSet() {
        TicketContext t = ticket(); // main has only a README
        String sbx = sandbox(t);

        assertThatThrownBy(() -> checks.run(t, sbx))
                .isInstanceOf(UnrecoverableStepException.class)
                .hasMessageContaining("no .factory.yml")
                .hasMessageContaining("factory.checks.default-command");
    }

    @Test
    void aRepoWithoutConfigUsesTheDefaultCommandWhenSet() {
        SandboxChecksRunner withDefault = runner(new ChecksProperties(".factory.yml", "test -f README.md",
                Duration.ofMinutes(1), Duration.ofMinutes(30)));
        TicketContext t = ticket();

        ChecksResult r = withDefault.run(t, sandbox(t));

        assertThat(r.passed()).as(r.output()).isTrue();
        assertThat(r.output()).startsWith("$ test -f README.md");
    }

    @Test
    void anInvalidConfigFailsTheTicketAtOnceWithTheReason() {
        fx.commitToMain(Map.of(".factory.yml", "checks:\n  timeout: 5m\n"), "no command");
        TicketContext t = ticket();
        String sbx = sandbox(t);

        assertThatThrownBy(() -> checks.run(t, sbx))
                .isInstanceOf(UnrecoverableStepException.class)
                .hasMessageContaining("checks.command must be a non-empty string");
    }
}
