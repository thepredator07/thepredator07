package com.ticketfactory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.ChecksRunner.ChecksResult;
import com.ticketfactory.integration.TicketContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What every {@link ChecksRunner} must do. The sandbox checks runner (M4) subclasses this with a sample repo. */
public abstract class ChecksRunnerContract {

    /** Output is fed back to the agent and stored per ticket, so implementations must truncate it. */
    public static final int MAX_OUTPUT_CHARS = 64 * 1024;

    protected abstract ChecksRunner runner();

    /** A ticket and sandbox whose checks pass. */
    protected abstract TicketContext passingTicket();

    protected abstract String sandboxId();

    /** A runner (or setup) whose checks fail, if the implementation can provide one. */
    protected Optional<ChecksRunner> failingRunner() {
        return Optional.empty();
    }

    @Test
    void passingChecksReportPassedWithOutput() {
        ChecksResult r = runner().run(passingTicket(), sandboxId());
        assertThat(r.passed()).isTrue();
        assertThat(r.output()).isNotNull().hasSizeLessThanOrEqualTo(MAX_OUTPUT_CHARS);
    }

    @Test
    void failingChecksReportFailedWithUsefulBoundedOutput() {
        Optional<ChecksRunner> failing = failingRunner();
        assumeTrue(failing.isPresent(), "implementation cannot provide failing checks");
        ChecksResult r = failing.get().run(passingTicket(), sandboxId());
        assertThat(r.passed()).isFalse();
        assertThat(r.output()).isNotBlank().hasSizeLessThanOrEqualTo(MAX_OUTPUT_CHARS);
    }
}
