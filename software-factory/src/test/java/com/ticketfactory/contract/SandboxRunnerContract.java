package com.ticketfactory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.SandboxRunner.Sandbox;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What every {@link SandboxRunner} must do. The Docker runner (M3) subclasses this and runs it on real Docker. */
public abstract class SandboxRunnerContract {

    protected abstract SandboxRunner runner();

    /** Whether the sandbox with this id currently exists. */
    protected abstract boolean exists(String sandboxId);

    /** Whether {@code branch} of {@code repo} exists on the remote (after a publish). */
    protected abstract boolean isPublished(String repo, String branch);

    /** Repository the tickets point at; the remote must exist for real implementations. */
    protected String repo() {
        return "example-org/example-repo";
    }

    /** A runner whose sandboxes fail to start, if the implementation can simulate it. */
    protected Optional<SandboxRunner> failingRunner() {
        return Optional.empty();
    }

    private final java.util.List<String> toCleanUp = new java.util.ArrayList<>();

    protected TicketContext ticket(long id) {
        return new TicketContext(id, repo(), (int) id, "Contract", "", "factory/" + id);
    }

    private Sandbox prepare(long ticketId) {
        Sandbox s = runner().prepare(ticket(ticketId));
        toCleanUp.add(s.id());
        return s;
    }

    @AfterEach
    void cleanUp() {
        toCleanUp.forEach(id -> runner().destroy(id));
    }

    @Test
    void prepareReturnsAnExistingSandboxWithAWorkdir() {
        Sandbox s = prepare(101);
        assertThat(s.id()).isNotBlank();
        assertThat(s.workdir()).isNotBlank();
        assertThat(exists(s.id())).isTrue();
    }

    @Test
    void prepareIsIdempotentPerTicket() {
        Sandbox first = prepare(102);
        Sandbox again = prepare(102);
        assertThat(again.id()).as("a crashed run's sandbox is reused, not duplicated").isEqualTo(first.id());
    }

    @Test
    void differentTicketsGetDifferentSandboxes() {
        assertThat(prepare(103).id()).isNotEqualTo(prepare(104).id());
    }

    @Test
    void destroyRemovesTheSandboxAndIsIdempotent() {
        Sandbox s = prepare(105);
        runner().destroy(s.id());
        assertThat(exists(s.id())).isFalse();
        assertThatCode(() -> runner().destroy(s.id())).doesNotThrowAnyException();
        assertThatCode(() -> runner().destroy("no-such-sandbox")).doesNotThrowAnyException();
    }

    @Test
    void prepareAfterDestroyGivesAWorkingSandbox() {
        Sandbox s = prepare(106);
        runner().destroy(s.id());
        assertThat(exists(prepare(106).id())).isTrue();
    }

    @Test
    void startupFailureSurfacesAsStepFailedException() {
        Optional<SandboxRunner> failing = failingRunner();
        assumeTrue(failing.isPresent(), "implementation cannot simulate a startup failure");
        assertThatThrownBy(() -> failing.get().prepare(ticket(107))).isInstanceOf(StepFailedException.class);
    }

    @Test
    void publishPushesTheTicketBranchAndIsIdempotent() {
        TicketContext t = ticket(108);
        Sandbox s = prepare(108);
        runner().publishBranch(t, s.id());
        assertThat(isPublished(repo(), t.branchName())).isTrue();
        assertThatCode(() -> runner().publishBranch(t, s.id())).as("publishing again is a no-op")
                .doesNotThrowAnyException();
    }

    @Test
    void publishRefusesBranchesOutsideTheFactoryNamespace() {
        Sandbox s = prepare(109);
        for (String bad : new String[]{"main", "master", "feature/x", "factory/"}) {
            TicketContext t = new TicketContext(109, repo(), 109, "Contract", "", bad);
            assertThatThrownBy(() -> runner().publishBranch(t, s.id())).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(isPublished(repo(), "feature/x")).isFalse();
    }

    @Test
    void listShowsPreparedSandboxesWithTheirTicketAndForgetsDestroyedOnes() {
        Sandbox a = prepare(110);
        Sandbox b = prepare(111);
        runner().destroy(b.id());
        assertThat(runner().list()).extracting(SandboxRunner.Sandbox::id).contains(a.id()).doesNotContain(b.id());
        assertThat(runner().list()).filteredOn(x -> x.id().equals(a.id())).singleElement()
                .extracting(SandboxRunner.Sandbox::ticketId).isEqualTo(a.ticketId());
    }
}
