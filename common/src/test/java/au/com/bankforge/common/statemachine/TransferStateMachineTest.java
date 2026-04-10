package au.com.bankforge.common.statemachine;

import au.com.bankforge.common.enums.TransferEvent;
import au.com.bankforge.common.enums.TransferState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static au.com.bankforge.common.enums.TransferEvent.*;
import static au.com.bankforge.common.enums.TransferState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the transfer state machine.
 *
 * No Spring context, no containers — pure logic tests.
 */
class TransferStateMachineTest {

    private TransferStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new TransferStateMachine();
    }

    @Test
    @DisplayName("Happy path: PENDING → CONFIRMED through all states")
    void happyPath() {
        TransferState state = PENDING;

        state = stateMachine.transition(state, INITIATE);
        assertThat(state).isEqualTo(PAYMENT_PROCESSING);

        state = stateMachine.transition(state, PAYMENT_COMPLETE);
        assertThat(state).isEqualTo(PAYMENT_DONE);

        state = stateMachine.transition(state, POST);
        assertThat(state).isEqualTo(POSTING);

        state = stateMachine.transition(state, CONFIRM);
        assertThat(state).isEqualTo(CONFIRMED);
    }

    @Test
    @DisplayName("Idempotent PROCESS re-entry: PAYMENT_PROCESSING + PROCESS → PAYMENT_PROCESSING")
    void idempotentProcessReentry() {
        TransferState state = stateMachine.transition(PENDING, INITIATE);
        assertThat(state).isEqualTo(PAYMENT_PROCESSING);

        // PROCESS is idempotent — stays in same state
        state = stateMachine.transition(state, PROCESS);
        assertThat(state).isEqualTo(PAYMENT_PROCESSING);
    }

    @Test
    @DisplayName("Compensation from PAYMENT_PROCESSING: PAYMENT_PROCESSING → COMPENSATING → CANCELLED")
    void compensationFromPaymentProcessing() {
        TransferState state = stateMachine.transition(PENDING, INITIATE);
        assertThat(state).isEqualTo(PAYMENT_PROCESSING);

        state = stateMachine.transition(state, FAIL);
        assertThat(state).isEqualTo(COMPENSATING);

        state = stateMachine.transition(state, COMPENSATE);
        assertThat(state).isEqualTo(CANCELLED);
    }

    @Test
    @DisplayName("Compensation from POSTING: POSTING → COMPENSATING → CANCELLED")
    void compensationFromPosting() {
        TransferState state = PENDING;
        state = stateMachine.transition(state, INITIATE);
        state = stateMachine.transition(state, PAYMENT_COMPLETE);
        state = stateMachine.transition(state, POST);
        assertThat(state).isEqualTo(POSTING);

        state = stateMachine.transition(state, FAIL);
        assertThat(state).isEqualTo(COMPENSATING);

        state = stateMachine.transition(state, COMPENSATE);
        assertThat(state).isEqualTo(CANCELLED);
    }

    @Test
    @DisplayName("Compensation from PAYMENT_DONE: exception thrown after PAYMENT_COMPLETE but before POST")
    void compensationFromPaymentDone() {
        // This covers the case where an exception occurs in the catch block after
        // PAYMENT_COMPLETE fires but before POST is applied — PaymentService.FAIL applies
        // to whatever the current state is, which may be PAYMENT_DONE.
        TransferState state = PENDING;
        state = stateMachine.transition(state, INITIATE);
        state = stateMachine.transition(state, PAYMENT_COMPLETE);
        assertThat(state).isEqualTo(PAYMENT_DONE);

        state = stateMachine.transition(state, FAIL);
        assertThat(state).isEqualTo(COMPENSATING);

        state = stateMachine.transition(state, COMPENSATE);
        assertThat(state).isEqualTo(CANCELLED);
    }

    @Test
    @DisplayName("Invalid transition: CONFIRMED + INITIATE throws InvalidStateTransitionException")
    void invalidTransitionThrows() {
        assertThatThrownBy(() -> stateMachine.transition(CONFIRMED, INITIATE))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("CONFIRMED")
                .hasMessageContaining("INITIATE");
    }

    @Test
    @DisplayName("Terminal state CONFIRMED: no further transitions allowed")
    void terminalStateConfirmed() {
        for (TransferEvent event : TransferEvent.values()) {
            assertThatThrownBy(() -> stateMachine.transition(CONFIRMED, event))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Test
    @DisplayName("Terminal state CANCELLED: no further transitions allowed")
    void terminalStateCancelled() {
        for (TransferEvent event : TransferEvent.values()) {
            assertThatThrownBy(() -> stateMachine.transition(CANCELLED, event))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Test
    @DisplayName("All 7 states are represented in enum")
    void allStatesPresent() {
        assertThat(TransferState.values()).containsExactlyInAnyOrder(
                PENDING, PAYMENT_PROCESSING, PAYMENT_DONE, POSTING,
                CONFIRMED, COMPENSATING, CANCELLED
        );
    }
}
