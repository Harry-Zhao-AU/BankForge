package au.com.bankforge.payment.detector;

import au.com.bankforge.common.enums.TransferState;
import au.com.bankforge.payment.entity.Transfer;
import au.com.bankforge.payment.repository.TransferRepository;
import au.com.bankforge.payment.service.TransferStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HungTransferDetector.
 *
 * Tests verify the two per-state timeout rules:
 *   PAYMENT_PROCESSING (>5 min): cancel via TransferStateService + publish banking.transfer.failed
 *   POSTING (>15 min): direct setState(FAILED) + save, NO reversal event (D-19)
 */
@ExtendWith(MockitoExtension.class)
class HungTransferDetectorTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransferStateService transferStateService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private HungTransferDetector hungTransferDetector;

    @BeforeEach
    void setUp() {
        hungTransferDetector = new HungTransferDetector(transferRepository, transferStateService, kafkaTemplate);
    }

    /**
     * PAYMENT_PROCESSING timeout: transfer older than 5 minutes is cancelled via
     * TransferStateService.cancel() and a banking.transfer.failed event is published.
     */
    @Test
    void shouldCancelHungPaymentProcessingTransfers() {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = Transfer.builder()
                .fromAccountId(UUID.randomUUID())
                .toAccountId(UUID.randomUUID())
                .amount(BigDecimal.valueOf(100.00))
                .idempotencyKey("idem-key-1")
                .build();
        transfer.setId(transferId);
        transfer.setState(TransferState.PAYMENT_PROCESSING);
        transfer.setCreatedAt(Instant.now().minus(6, ChronoUnit.MINUTES));
        transfer.setUpdatedAt(Instant.now().minus(6, ChronoUnit.MINUTES));

        when(transferRepository.findByStateAndCreatedAtBefore(
                eq(TransferState.PAYMENT_PROCESSING), any(Instant.class)))
                .thenReturn(List.of(transfer));
        when(transferRepository.findByStateAndUpdatedAtBefore(
                eq(TransferState.POSTING), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        hungTransferDetector.detectHungTransfers();

        verify(transferStateService, times(1)).cancel(transfer.getId(), "PAYMENT_PROCESSING_TIMEOUT");
        verify(kafkaTemplate, times(1)).send(
                eq("banking.transfer.failed"),
                eq(transfer.getId().toString()),
                contains("PAYMENT_PROCESSING_TIMEOUT"));
    }

    /**
     * POSTING timeout: transfer with updatedAt older than 15 minutes is marked FAILED
     * directly (FSM bypass — intentional per D-19). No reversal event is published.
     */
    @Test
    void shouldMarkHungPostingAsFailed() {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = Transfer.builder()
                .fromAccountId(UUID.randomUUID())
                .toAccountId(UUID.randomUUID())
                .amount(BigDecimal.valueOf(200.00))
                .idempotencyKey("idem-key-2")
                .build();
        transfer.setId(transferId);
        transfer.setState(TransferState.POSTING);
        transfer.setCreatedAt(Instant.now().minus(20, ChronoUnit.MINUTES));
        transfer.setUpdatedAt(Instant.now().minus(16, ChronoUnit.MINUTES));

        when(transferRepository.findByStateAndCreatedAtBefore(
                eq(TransferState.PAYMENT_PROCESSING), any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(transferRepository.findByStateAndUpdatedAtBefore(
                eq(TransferState.POSTING), any(Instant.class)))
                .thenReturn(List.of(transfer));

        hungTransferDetector.detectHungTransfers();

        // State must be FAILED (direct set, not via FSM)
        assertThat(transfer.getState()).isEqualTo(TransferState.FAILED);
        // Error message must be exactly this string
        assertThat(transfer.getErrorMessage()).isEqualTo("LEDGER_CONFIRM_TIMEOUT -- manual review required");
        // Transfer must be saved
        verify(transferRepository, times(1)).save(transfer);
        // D-19: No reversal event for POSTING timeout
        verify(kafkaTemplate, never()).send(eq("banking.transfer.failed"), any(), any());
        // No FSM cancel path
        verify(transferStateService, never()).cancel(any(), any());
    }

    /**
     * No hung transfers: both timeout queries return empty lists.
     * Verifies zero interactions with cancel or Kafka.
     */
    @Test
    void shouldDoNothingWhenNoHungTransfers() {
        when(transferRepository.findByStateAndCreatedAtBefore(
                eq(TransferState.PAYMENT_PROCESSING), any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(transferRepository.findByStateAndUpdatedAtBefore(
                eq(TransferState.POSTING), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        hungTransferDetector.detectHungTransfers();

        verifyNoInteractions(transferStateService);
        verifyNoInteractions(kafkaTemplate);
    }
}
