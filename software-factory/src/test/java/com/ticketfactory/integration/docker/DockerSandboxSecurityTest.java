package com.ticketfactory.integration.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.ticketfactory.integration.SandboxRunner.Sandbox;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.docker.DockerSandboxRunner.ExecResult;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What a sandbox can and cannot do, checked from inside a real container. */
class DockerSandboxSecurityTest {

    private static DockerSandboxFixture fx;
    private static TicketContext ticket;
    private static Sandbox sandbox;

    @BeforeAll
    static void start() throws Exception {
        fx = new DockerSandboxFixture();
        long id = fx.id(1);
        ticket = new TicketContext(id, DockerSandboxFixture.REPO, 1, "Security", "", "factory/" + id);
        sandbox = fx.runner.prepare(ticket);
    }

    @AfterAll
    static void stop() throws Exception {
        fx.close();
    }

    private static ExecResult sh(String script) {
        return fx.runner.exec(sandbox.id(), Duration.ofSeconds(30), "sh", "-c", script);
    }

    @Test
    void runsAsAnUnprivilegedUser() {
        assertThat(sh("id -u").output().strip()).isEqualTo("1000");
        // Effective caps are zero for any non-root user; the bounding set is what limits privilege escalation.
        assertThat(sh("grep CapEff /proc/self/status").output()).contains("0000000000000000");
        assertThat(sh("grep CapBnd /proc/self/status").output()).contains("0000000000000000");
        assertThat(sh("grep NoNewPrivs /proc/self/status").output()).contains("1");
    }

    @Test
    void hasNoNetworkAtAll() {
        assertThat(sh("ls /sys/class/net").output().strip()).isEqualTo("lo");
        ExecResult fetch = sh("git ls-remote https://github.com/git/git 2>&1");
        assertThat(fetch.ok()).isFalse();
    }

    @Test
    void rootFilesystemIsReadOnlyButTheWorkspaceIsWritable() {
        assertThat(sh("touch /etc/evil").ok()).isFalse();
        assertThat(sh("touch /usr/bin/evil").ok()).isFalse();
        assertThat(sh("touch /workspace/repo/ok && touch /tmp/ok").ok()).isTrue();
    }

    @Test
    void holdsNoCredentials() {
        ExecResult env = sh("env");
        assertThat(env.output()).doesNotContain(DockerSandboxFixture.FAKE_TOKEN)
                .doesNotContainIgnoringCase("token=").doesNotContain("GITHUB").doesNotContain("ANTHROPIC");
        assertThat(sh("grep -rs " + DockerSandboxFixture.FAKE_TOKEN + " /workspace /tmp; echo done").output().strip())
                .isEqualTo("done");
        assertThat(sh("git -C /workspace/repo remote").output().strip()).as("no remote to push to").isEmpty();
    }

    @Test
    void resourceLimitsAreApplied() {
        InspectContainerResponse info = fx.runner.inspect(sandbox.id());
        assertThat(info.getHostConfig().getMemory()).isEqualTo(1024L * 1024 * 1024);
        assertThat(info.getHostConfig().getMemorySwap()).isEqualTo(1024L * 1024 * 1024);
        assertThat(info.getHostConfig().getNanoCPUs()).isEqualTo(1_000_000_000L);
        assertThat(info.getHostConfig().getPidsLimit()).isEqualTo(256L);
        assertThat(info.getHostConfig().getNetworkMode()).isEqualTo("none");
        assertThat(info.getHostConfig().getReadonlyRootfs()).isTrue();
        assertThat(info.getHostConfig().getCapDrop()).containsExactly(com.github.dockerjava.api.model.Capability.ALL);
        assertThat(info.getHostConfig().getSecurityOpts()).contains("no-new-privileges:true");
        assertThat(info.getConfig().getUser()).isEqualTo("1000:1000");
        assertThat(info.getConfig().getLabels()).containsEntry(DockerSandboxRunner.LABEL_MANAGED, "true")
                .containsEntry(DockerSandboxRunner.LABEL_TICKET, Long.toString(ticket.ticketId()));
    }

    @Test
    void workspaceStartsOnTheTicketBranchFromBase() {
        assertThat(sh("git -C /workspace/repo rev-parse --abbrev-ref HEAD").output().strip())
                .isEqualTo(ticket.branchName());
        assertThat(sh("cat /workspace/repo/README.md").output()).contains("# acme/app");
    }

    @Test
    void theAgentsCommitsReachTheRemoteBranchAndMainIsUntouched() {
        String mainBefore = fx.remoteHead("main");
        assertThat(sh("cd /workspace/repo && echo 'fixed' > FIX.md && git add FIX.md && git commit -qm 'Fix it'")
                .ok()).isTrue();

        fx.runner.publishBranch(ticket, sandbox.id());

        assertThat(fx.remoteFile(ticket.branchName(), "FIX.md")).isEqualTo("fixed\n");
        assertThat(fx.remoteHead("main")).as("main never changes").isEqualTo(mainBefore);
    }

    @Test
    void cannotPublishToMainEvenIfTheBranchIsRenamedInside() {
        TicketContext sneaky = new TicketContext(ticket.ticketId(), ticket.repo(), 1, "x", "", "main");
        assertThatThrownBy(() -> fx.runner.publishBranch(sneaky, sandbox.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
