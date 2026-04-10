package au.com.bankforge.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency service for payment transfers (TXNS-05).
 *
 * Uses StringRedisTemplate (Lettuce driver — bundled in Spring Boot 4).
 * Keys are prefixed with "idempotency:" and stored with a 24-hour TTL.
 * setIfAbsent (SET NX EX) ensures atomic first-write semantics — concurrent
 * requests with the same key will have exactly one winner.
 *
 * SECURITY NOTE: Redis has no auth in Phase 1 (local dev only).
 * Rate limiting is added in Phase 3 via Kong (T-1-09: accepted for now).
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;

    private static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "idempotency:";

    /**
     * Returns the cached JSON response for the given idempotency key, if present.
     *
     * @param key client-provided idempotency key
     * @return Optional containing cached JSON string, or empty if not found
     */
    public Optional<String> getCached(String key) {
        return Optional.ofNullable(
                redisTemplate.opsForValue().get(PREFIX + key));
    }

    /**
     * Atomically stores the response JSON for the given key if not already present.
     *
     * @param key          client-provided idempotency key
     * @param responseJson JSON-serialized InitiateTransferResponse
     * @return true if the key was newly set (first request), false if key already existed (duplicate)
     */
    public boolean setIfNew(String key, String responseJson) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(
                        PREFIX + key, responseJson, TTL));
    }
}
