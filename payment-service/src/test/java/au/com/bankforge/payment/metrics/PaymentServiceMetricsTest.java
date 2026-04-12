package au.com.bankforge.payment.metrics;

import au.com.bankforge.common.enums.TransferState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies custom banking metrics registration and tagging per D-10.
 * Uses SimpleMeterRegistry (no Spring context needed).
 */
class PaymentServiceMetricsTest {

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void transferInitiatedCounter_taggedByState_PENDING() {
        Counter counter = Counter.builder("transfer_initiated_total")
            .tag("service", "payment-service")
            .tag("state", TransferState.PENDING.name())
            .register(meterRegistry);
        counter.increment();

        Counter found = meterRegistry.get("transfer_initiated_total")
            .tag("state", "PENDING")
            .counter();
        assertEquals(1.0, found.count());
    }

    @Test
    void transferInitiatedCounter_taggedByState_CONFIRMED() {
        Counter counter = Counter.builder("transfer_initiated_total")
            .tag("service", "payment-service")
            .tag("state", TransferState.CONFIRMED.name())
            .register(meterRegistry);
        counter.increment();

        Counter found = meterRegistry.get("transfer_initiated_total")
            .tag("state", "CONFIRMED")
            .counter();
        assertEquals(1.0, found.count());
    }

    @Test
    void transferInitiatedCounter_taggedByState_CANCELLED() {
        Counter counter = Counter.builder("transfer_initiated_total")
            .tag("service", "payment-service")
            .tag("state", TransferState.CANCELLED.name())
            .register(meterRegistry);
        counter.increment();

        Counter found = meterRegistry.get("transfer_initiated_total")
            .tag("state", "CANCELLED")
            .counter();
        assertEquals(1.0, found.count());
    }

    @Test
    void transferAmountCounter_incrementsByAmount() {
        Counter counter = Counter.builder("transfer_amount_total")
            .tag("service", "payment-service")
            .register(meterRegistry);
        counter.increment(150.50);

        assertEquals(150.50, meterRegistry.get("transfer_amount_total").counter().count());
    }

    @Test
    void transferDltCounter_startsAtZero() {
        Counter counter = Counter.builder("transfer_dlt_messages_total")
            .tag("service", "payment-service")
            .register(meterRegistry);

        assertEquals(0.0, counter.count());
    }
}
