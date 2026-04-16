package au.com.bankforge.payment.detector;

import au.com.bankforge.common.enums.TransferState;
import au.com.bankforge.payment.entity.Transfer;
import au.com.bankforge.payment.repository.TransferRepository;
import au.com.bankforge.payment.service.TransferStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduled job that detects and resolves hung transfers.
 *
 * Two per-state timeout rules (per D-19):
 *
 *   PAYMENT_PROCESSING (>5 min, uses createdAt):
 *     Cancel via TransferStateService.cancel() — goes through FSM compensation path.
 *     Publishes banking.transfer.failed so upstream can observe the failure.
 *
 *   POSTING (>15 min, uses updatedAt):
 *     Direct setState(FAILED) — bypasses FSM intentionally (D-19: ledger has already posted,
 *     compensation would double-reverse). No reversal event published.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HungTransferDetector {

    static final long PAYMENT_PROCESSING_TIMEOUT_MINUTES = 5;
    static final long POSTING_TIMEOUT_MINUTES = 15;
    static final String TRANSFER_FAILED_TOPIC = "banking.transfer.failed";

    private final TransferRepository transferRepository;
    private final TransferStateService transferStateService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Runs every 60 seconds. Detects transfers stuck in PAYMENT_PROCESSING or POSTING
     * states beyond their respective timeout thresholds and resolves them.
     */
    @Scheduled(fixedDelayString = "${payment.hung-transfer-detector.fixed-delay-ms:60000}")
    public void detectHungTransfers() {
        resolveHungPaymentProcessing();
        resolveHungPosting();
    }

    private void resolveHungPaymentProcessing() {
        Instant threshold = Instant.now().minus(PAYMENT_PROCESSING_TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        List<Transfer> hung = transferRepository.findByStateAndCreatedAtBefore(
                TransferState.PAYMENT_PROCESSING, threshold);

        for (Transfer transfer : hung) {
            log.warn("Hung PAYMENT_PROCESSING transfer detected: id={}, createdAt={}",
                    transfer.getId(), transfer.getCreatedAt());
            try {
                transferStateService.cancel(transfer.getId(), "PAYMENT_PROCESSING_TIMEOUT");
                kafkaTemplate.send(
                        TRANSFER_FAILED_TOPIC,
                        transfer.getId().toString(),
                        "{\"transferId\":\"" + transfer.getId() + "\",\"reason\":\"PAYMENT_PROCESSING_TIMEOUT\"}");
                log.info("Cancelled hung PAYMENT_PROCESSING transfer: id={}", transfer.getId());
            } catch (Exception e) {
                log.error("Failed to cancel hung PAYMENT_PROCESSING transfer: id={}, error={}",
                        transfer.getId(), e.getMessage());
            }
        }
    }

    private void resolveHungPosting() {
        Instant threshold = Instant.now().minus(POSTING_TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        List<Transfer> hung = transferRepository.findByStateAndUpdatedAtBefore(
                TransferState.POSTING, threshold);

        for (Transfer transfer : hung) {
            log.warn("Hung POSTING transfer detected: id={}, updatedAt={}",
                    transfer.getId(), transfer.getUpdatedAt());
            // D-19: FSM bypass — ledger has already posted, reversal would double-reverse.
            // Mark FAILED directly and save. No reversal event.
            transfer.setState(TransferState.FAILED);
            transfer.setErrorMessage("LEDGER_CONFIRM_TIMEOUT -- manual review required");
            transferRepository.save(transfer);
            log.info("Marked hung POSTING transfer as FAILED (no reversal): id={}", transfer.getId());
        }
    }
}
