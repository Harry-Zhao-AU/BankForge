package au.com.bankforge.common.statemachine;

import au.com.bankforge.common.enums.TransferEvent;
import au.com.bankforge.common.enums.TransferState;

/**
 * Thrown when an event is applied to a state that has no defined transition for that event.
 *
 * This is an unrecoverable error in the state machine — it indicates a programming error
 * or an attempt to replay an event out of order.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(TransferState current, TransferEvent event) {
        super("Invalid transition: cannot apply " + event + " to state " + current);
    }
}
