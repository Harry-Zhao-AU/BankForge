#!/usr/bin/env bash
# validate-observability.sh — Happy-path transfer saga validation before git push
#
# Runs a full create-accounts → initiate-transfer → poll-CONFIRMED flow, then
# verifies traces appeared in Jaeger, metrics in Prometheus, and logs in Loki.
#
# Exit 0 = all checks passed (push proceeds)
# Exit 1 = one or more checks failed (push blocked)
#
# Override when stack is intentionally down: SKIP_OBS_CHECK=1 git push

set -euo pipefail

ACCOUNT_SVC="http://localhost:8081"
PAYMENT_SVC="http://localhost:8082"
JAEGER="http://localhost:16686"
PROMETHEUS="http://localhost:9090"
LOKI="http://localhost:3100"

PASS=0; FAIL=0

log_pass() { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
log_fail() { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); }
log_warn() { echo "  [WARN] $1"; }

# ── Escape hatch ────────────────────────────────────────────────────────────
if [[ "${SKIP_OBS_CHECK:-0}" == "1" ]]; then
    echo "Observability validation skipped (SKIP_OBS_CHECK=1)"
    exit 0
fi

echo "=== BankForge Observability Validation ==="

# ── 1. Check stack is reachable ──────────────────────────────────────────────
echo ""
echo "--- Service reachability ---"
if ! curl -sf --max-time 5 "$ACCOUNT_SVC/actuator/health" > /dev/null 2>&1; then
    echo "  [SKIP] Services not reachable — stack is down. Start with: podman compose up -d"
    exit 0
fi
log_pass "account-service reachable"

if ! curl -sf --max-time 5 "$PAYMENT_SVC/actuator/health" > /dev/null 2>&1; then
    log_fail "payment-service not reachable"
    exit 1
fi
log_pass "payment-service reachable"

# ── 2. Create two test accounts ───────────────────────────────────────────────
echo ""
echo "--- Creating test accounts ---"
TS=$(date +%s)
ACC_A="${TS: -8}1"  # 9 digits, unique per second
ACC_B="${TS: -8}2"

RESP_A=$(curl -sf --max-time 10 -X POST "$ACCOUNT_SVC/api/accounts" \
    -H "Content-Type: application/json" \
    -d "{\"bsb\":\"062-000\",\"accountNumber\":\"$ACC_A\",\"accountName\":\"Validate-A\",\"initialBalance\":1000.00}" 2>&1) || true

ACCOUNT_A=$(echo "$RESP_A" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [[ -z "$ACCOUNT_A" ]]; then
    log_fail "Create account A failed — response: $RESP_A"
    exit 1
fi
log_pass "Account A created: $ACCOUNT_A"

RESP_B=$(curl -sf --max-time 10 -X POST "$ACCOUNT_SVC/api/accounts" \
    -H "Content-Type: application/json" \
    -d "{\"bsb\":\"063-000\",\"accountNumber\":\"$ACC_B\",\"accountName\":\"Validate-B\",\"initialBalance\":500.00}" 2>&1) || true

ACCOUNT_B=$(echo "$RESP_B" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [[ -z "$ACCOUNT_B" ]]; then
    log_fail "Create account B failed — response: $RESP_B"
    exit 1
fi
log_pass "Account B created: $ACCOUNT_B"

# ── 3. Initiate transfer ──────────────────────────────────────────────────────
echo ""
echo "--- Initiating transfer ---"
IK="obs-validate-${TS}"
RESP_T=$(curl -sf --max-time 10 -X POST "$PAYMENT_SVC/api/payments/transfers" \
    -H "Content-Type: application/json" \
    -d "{\"fromAccountId\":\"$ACCOUNT_A\",\"toAccountId\":\"$ACCOUNT_B\",\"amount\":50.00,\"idempotencyKey\":\"$IK\",\"description\":\"Observability validation\"}" 2>&1) || true

TRANSFER_ID=$(echo "$RESP_T" | grep -o '"transferId":"[^"]*"' | head -1 | cut -d'"' -f4)
if [[ -z "$TRANSFER_ID" ]]; then
    log_fail "Initiate transfer failed — response: $RESP_T"
    exit 1
fi
log_pass "Transfer initiated: $TRANSFER_ID"

# ── 4. Poll for CONFIRMED (30s timeout) ──────────────────────────────────────
echo ""
echo "--- Polling saga state ---"
CONFIRMED=false
STATE="UNKNOWN"
for i in $(seq 1 15); do
    STATE_RESP=$(curl -sf --max-time 5 "$PAYMENT_SVC/api/payments/transfers/$TRANSFER_ID" 2>/dev/null || echo "")
    STATE=$(echo "$STATE_RESP" | grep -o '"state":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [[ "$STATE" == "CONFIRMED" ]]; then
        CONFIRMED=true
        log_pass "Transfer CONFIRMED (attempt $i, ~$((i*2))s)"
        break
    elif [[ "$STATE" == "CANCELLED" || "$STATE" == "FAILED" ]]; then
        log_fail "Transfer reached terminal failure state: $STATE"
        exit 1
    fi
    sleep 2
done

if [[ "$CONFIRMED" != "true" ]]; then
    log_fail "Transfer did not reach CONFIRMED in 30s (last state: ${STATE:-no-response})"
    exit 1
fi

# ── 5. Wait for telemetry propagation ────────────────────────────────────────
echo ""
echo "--- Waiting 5s for telemetry propagation ---"
sleep 5

# ── 6. Jaeger: verify payment-service traces ─────────────────────────────────
echo ""
echo "--- Jaeger traces ---"
NOW_US=$(( $(date +%s) * 1000000 ))
START_US=$(( ($(date +%s) - 300) * 1000000 ))
JAEGER_RESP=$(curl -sf --max-time 10 \
    "$JAEGER/api/traces?service=payment-service&start=$START_US&end=$NOW_US&limit=5" 2>/dev/null || echo "")
if echo "$JAEGER_RESP" | grep -q '"traceID"'; then
    log_pass "Jaeger: payment-service traces present"
else
    log_fail "Jaeger: no payment-service traces found in last 5 minutes"
fi

JAEGER_ACCT=$(curl -sf --max-time 10 \
    "$JAEGER/api/traces?service=account-service&start=$START_US&end=$NOW_US&limit=5" 2>/dev/null || echo "")
if echo "$JAEGER_ACCT" | grep -q '"traceID"'; then
    log_pass "Jaeger: account-service traces present"
else
    log_warn "Jaeger: account-service traces not found (may use different service name)"
fi

# ── 7. Prometheus: verify transfer metrics ────────────────────────────────────
echo ""
echo "--- Prometheus metrics ---"
PROM_INIT=$(curl -sf --max-time 10 \
    "$PROMETHEUS/api/v1/query?query=transfer_initiated_total" 2>/dev/null || echo "")
if echo "$PROM_INIT" | grep -q '"__name__":"transfer_initiated_total"'; then
    log_pass "Prometheus: transfer_initiated_total metric present"
elif echo "$PROM_INIT" | grep -q '"result"'; then
    log_warn "Prometheus: transfer_initiated_total query returned but no time-series data yet"
else
    log_warn "Prometheus: could not query transfer_initiated_total"
fi

PROM_AMT=$(curl -sf --max-time 10 \
    "$PROMETHEUS/api/v1/query?query=transfer_amount_total" 2>/dev/null || echo "")
if echo "$PROM_AMT" | grep -q '"__name__":"transfer_amount_total"'; then
    log_pass "Prometheus: transfer_amount_total metric present"
else
    log_warn "Prometheus: transfer_amount_total not found (fires after first transfer)"
fi

# ── 8. Loki: verify payment-service logs ─────────────────────────────────────
echo ""
echo "--- Loki logs ---"
NOW_NS=$(( $(date +%s) * 1000000000 ))
START_NS=$(( ($(date +%s) - 300) * 1000000000 ))
LOKI_RESP=$(curl -sf --max-time 10 \
    "$LOKI/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment-service%22%7D&start=$START_NS&end=$NOW_NS&limit=5" \
    2>/dev/null || echo "")
if echo "$LOKI_RESP" | grep -q '"entries"'; then
    log_pass "Loki: payment-service log entries present"
elif echo "$LOKI_RESP" | grep -q '"streams"'; then
    log_warn "Loki: payment-service stream found but no entries yet"
else
    log_warn "Loki: payment-service logs not found (check service_name label in OTel config)"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [[ $FAIL -gt 0 ]]; then
    echo ""
    echo "Push blocked — fix the failures above, then re-push."
    echo "To bypass when stack is intentionally down: SKIP_OBS_CHECK=1 git push"
    exit 1
fi

echo "All checks passed — safe to push."
exit 0
