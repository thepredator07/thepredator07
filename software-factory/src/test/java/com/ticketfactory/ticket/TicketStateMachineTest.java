package com.ticketfactory.ticket;

import static com.ticketfactory.ticket.TicketState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TicketStateMachineTest {

    /** Written out independently of the production table so a typo there fails here. */
    private static final Map<TicketState, Set<TicketState>> EXPECTED = Map.of(
            RECEIVED, EnumSet.of(SANDBOX_READY, FAILED, CANCELLED),
            SANDBOX_READY, EnumSet.of(CODING, FAILED, CANCELLED),
            CODING, EnumSet.of(CHECKS, FAILED, CANCELLED),
            CHECKS, EnumSet.of(PR_OPENED, CODING, FAILED, CANCELLED),
            PR_OPENED, EnumSet.of(AWAITING_APPROVAL, FAILED, CANCELLED),
            AWAITING_APPROVAL, EnumSet.of(DONE, FAILED, CANCELLED),
            DONE, EnumSet.noneOf(TicketState.class),
            FAILED, EnumSet.noneOf(TicketState.class),
            CANCELLED, EnumSet.noneOf(TicketState.class));

    static Stream<Arguments> allPairs() {
        return Stream.of(TicketState.values())
                .flatMap(from -> Stream.of(TicketState.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairMatchesTheSpecifiedTable(TicketState from, TicketState to) {
        boolean expected = EXPECTED.get(from).contains(to);
        assertThat(TicketStateMachine.canTransition(from, to)).isEqualTo(expected);
        if (expected) {
            assertThatCode(() -> TicketStateMachine.validate(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> TicketStateMachine.validate(from, to))
                    .isInstanceOf(InvalidTransitionException.class)
                    .hasMessageContaining(from + " -> " + to);
        }
    }

    @Test
    void happyPathIsAValidChain() {
        TicketState[] path = {RECEIVED, SANDBOX_READY, CODING, CHECKS, PR_OPENED, AWAITING_APPROVAL, DONE};
        for (int i = 0; i + 1 < path.length; i++) {
            TicketStateMachine.validate(path[i], path[i + 1]);
        }
    }

    @Test
    void checksFailureLoopsBackToCoding() {
        assertThat(TicketStateMachine.canTransition(CHECKS, CODING)).isTrue();
    }

    @Test
    void cannotSkipStepsOrGoBackwards() {
        assertThat(TicketStateMachine.canTransition(RECEIVED, CODING)).isFalse();
        assertThat(TicketStateMachine.canTransition(CODING, PR_OPENED)).isFalse();
        assertThat(TicketStateMachine.canTransition(AWAITING_APPROVAL, CODING)).isFalse();
        assertThat(TicketStateMachine.canTransition(RECEIVED, DONE)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TicketState.class, names = {"DONE", "FAILED", "CANCELLED"})
    void terminalStatesHaveNoExits(TicketState terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(TicketStateMachine.allowedFrom(terminal)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = TicketState.class, names = {"DONE", "FAILED", "CANCELLED"}, mode = EnumSource.Mode.EXCLUDE)
    void everyActiveStateCanFailOrBeCancelled(TicketState active) {
        assertThat(active.isTerminal()).isFalse();
        assertThat(TicketStateMachine.allowedFrom(active)).contains(FAILED, CANCELLED);
    }

    @Test
    void selfTransitionsAreRejected() {
        for (TicketState s : TicketState.values()) {
            assertThat(TicketStateMachine.canTransition(s, s)).as(s.name()).isFalse();
        }
    }

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> TicketStateMachine.validate(null, CODING))
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> TicketStateMachine.validate(CODING, null))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void allowedSetIsReadOnly() {
        assertThatThrownBy(() -> TicketStateMachine.allowedFrom(RECEIVED).add(DONE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
