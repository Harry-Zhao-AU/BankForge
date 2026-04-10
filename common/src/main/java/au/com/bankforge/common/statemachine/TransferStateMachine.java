package au.com.bankforge.common.statemachine;

import au.com.bankforge.common.enums.TransferEvent;
import au.com.bankforge.common.enums.TransferState;
import org.springframework.stereotype.Component;

import java.util.Map;

import static au.com.bankforge.common.enums.TransferEvent.*;
import static au.com.bankforge.common.enums.TransferState.*;

/**
 * Enum-based transfer state machine.
 *
 * Replaces Spring State Machine (incompatible with Spring Boot 4 / Spring Framework 7).
 * The transition table is immutable (static final Map) — invalid transitions throw
 * InvalidStateTransitionException. No external input can modify the transition rules (T-1-01).
 *
 * Transition table:
 *   PENDING             + INITIATE          → PAYMENT_PROCESSING
 *   PAYMENT_PROCESSING  + PROCESS           → PAYMENT_PROCESSING  (idempotent re-entry)
 *   PAYMENT_PROCESSING  + PAYMENT_COMPLETE  → PAYMENT_DONE
 *   PAYMENT_PROCESSING  + FAIL              → COMPENSATING
 *   PAYMENT_DONE        + POST              → POSTING
 *   PAYMENT_DONE        + FAIL              → COMPENSATING        (exception after PAYMENT_COMPLETE but before POST)
 *   POSTING             + CONFIRM           → CONFIRMED
 *   POSTING             + FAIL              → COMPENSATING
 *   COMPENSATING        + COMPENSATE        → CANCELLED
 */
@Component
public class TransferStateMachine {

    /**
     * Immutable transition table.
     * Outer key: current state.
     * Inner key: event.
     * Value: next state.
     */
    private static final Map<TransferState, Map<TransferEvent, TransferState>> TRANSITIONS =
            Map.of(
                    PENDING, Map.of(
                            INITIATE, PAYMENT_PROCESSING
                    ),
                    PAYMENT_PROCESSING, Map.of(
                            PROCESS, PAYMENT_PROCESSING,           // idempotent re-entry for retries
                            PAYMENT_COMPLETE, PAYMENT_DONE,
                            FAIL, COMPENSATING
                    ),
                    PAYMENT_DONE, Map.of(
                            POST, POSTING,
                            FAIL, COMPENSATING                     // exception after PAYMENT_COMPLETE fires but before POST
                    ),
                    POSTING, Map.of(
                            CONFIRM, CONFIRMED,
                            FAIL, COMPENSATING
                    ),
                    COMPENSATING, Map.of(
                            COMPENSATE, CANCELLED
                    )
            );

    /**
     * Apply an event to the current state and return the next state.
     *
     * @param current the current transfer state
     * @param event   the event to apply
     * @return the next state
     * @throws InvalidStateTransitionException if no transition is defined for (current, event)
     */
    public TransferState transition(TransferState current, TransferEvent event) {
        TransferState next = TRANSITIONS.getOrDefault(current, Map.of()).get(event);
        if (next == null) {
            throw new InvalidStateTransitionException(current, event);
        }
        return next;
    }
}
