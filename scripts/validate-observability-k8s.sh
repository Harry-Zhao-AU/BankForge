#!/usr/bin/env bash
# validate-observability-k8s.sh — K8s cluster observability validation
#
# Runs the same create-accounts → initiate-transfer → poll-CONFIRMED saga as
# validate-observability.sh, but executes all calls via kubectl exec inside
# the bankforge namespace (no port-forward required).
#
# Observability checks (Jaeger, Prometheus, Loki) are WARN-only if those
# services are not yet deployed (safe to run before Plan 03-04).
#
# Exit 0 = all critical checks passed
# Exit 1 = saga flow or critical infra check failed
#
# Override: SKIP_OBS_CHECK=1 bash scripts/validate-observability-k8s.sh

set -euo pipefail

NAMESPACE=bankforge
EXEC_POD="deploy/payment-service"   # exec host — can reach all services via mTLS

PASS=0; FAIL=0; WARN=0

log_pass() { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
log_fail() { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); }
log_warn() { echo "  [WARN] $1"; WARN=$((WARN + 1)); }
log_skip() { echo "  [SKIP] $1"; }

kexec() {
  # Run a command inside the payment-service pod; returns stdout, stderr to /dev/null
  kubectl exec -n "$NAMESPACE" "$EXEC_POD" -- "$@" 2>/dev/null
}

kexec_curl() {
  # curl with short timeout; returns empty string on failure rather than exit 1
  kexec curl -sf --max-time 10 "$@" 2>/dev/null || echo ""
}

svc_exists() {
  kubectl get svc -n "$NAMESPACE" "$1" &>/dev/null
}

# ── Escape hatch ─────────────────────────────────────────────────────────────
if [[ "${SKIP_OBS_CHECK:-0}" == "1" ]]; then
  echo "Observability validation skipped (SKIP_OBS_CHECK=1)"
  exit 0
fi

echo "=== BankForge K8s Observability Validation ==="

# ── 1. Cluster + namespace reachability ───────────────────────────────────────
echo ""
echo "--- Cluster connectivity ---"
if ! kubectl get namespace "$NAMESPACE" &>/dev/null; then
  log_skip "Namespace '$NAMESPACE' not found — cluster may be down or not yet bootstrapped"
  exit 0
fi
log_pass "Namespace '$NAMESPACE' exists"

# Check payment-service pod is Running (exec host)
EXEC_READY=$(kubectl get "$EXEC_POD" -n "$NAMESPACE" \
  -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
if [[ "${EXEC_READY:-0}" -lt 1 ]]; then
  log_skip "payment-service not ready — apply k8s/services/ and wait for 2/2 READY"
  exit 0
fi
log_pass "payment-service ready (exec host)"

# ── 2. Service health checks (via exec) ───────────────────────────────────────
echo ""
echo "--- Service reachability ---"
for svc in account-service payment-service ledger-service notification-service; do
  HEALTH=$(kexec_curl "http://${svc}:8080/actuator/health")
  if echo "$HEALTH" | grep -q '"status":"UP"'; then
    log_pass "$svc: UP"
  elif echo "$HEALTH" | grep -q '"status"'; then
    log_warn "$svc: responded but status not UP — $(echo "$HEALTH" | grep -o '"status":"[^"]*"' | head -1)"
  else
    log_fail "$svc: not reachable"
  fi
done

if [[ $FAIL -gt 0 ]]; then
  echo ""
  echo "Critical services not reachable — fix before continuing."
  exit 1
fi

# ── 3. Create two test accounts ───────────────────────────────────────────────
echo ""
echo "--- Creating test accounts ---"
TS=$(date +%s)
ACC_A="${TS: -8}1"
ACC_B="${TS: -8}2"

RESP_A=$(kexec_curl -X POST "http://account-service:8080/api/accounts" \
  -H "Content-Type: application/json" \
  -d "{\"bsb\":\"062-000\",\"accountNumber\":\"$ACC_A\",\"accountName\":\"K8s-Validate-A\",\"initialBalance\":1000.00}")

ACCOUNT_A=$(echo "$RESP_A" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [[ -z "$ACCOUNT_A" ]]; then
  log_fail "Create account A failed — response: $RESP_A"
  exit 1
fi
log_pass "Account A created: $ACCOUNT_A"

RESP_B=$(kexec_curl -X POST "http://account-service:8080/api/accounts" \
  -H "Content-Type: application/json" \
  -d "{\"bsb\":\"063-000\",\"accountNumber\":\"$ACC_B\",\"accountName\":\"K8s-Validate-B\",\"initialBalance\":500.00}")

ACCOUNT_B=$(echo "$RESP_B" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [[ -z "$ACCOUNT_B" ]]; then
  log_fail "Create account B failed — response: $RESP_B"
  exit 1
fi
log_pass "Account B created: $ACCOUNT_B"

# ── 4. Initiate transfer ──────────────────────────────────────────────────────
echo ""
echo "--- Initiating transfer ---"
IK="k8s-obs-validate-${TS}"
RESP_T=$(kexec_curl -X POST "http://payment-service:8080/api/payments/transfers" \
  -H "Content-Type: application/json" \
  -d "{\"fromAccountId\":\"$ACCOUNT_A\",\"toAccountId\":\"$ACCOUNT_B\",\"amount\":50.00,\"idempotencyKey\":\"$IK\",\"description\":\"K8s observability validation\"}")

TRANSFER_ID=$(echo "$RESP_T" | grep -o '"transferId":"[^"]*"' | head -1 | cut -d'"' -f4)
if [[ -z "$TRANSFER_ID" ]]; then
  log_fail "Initiate transfer failed — response: $RESP_T"
  exit 1
fi
log_pass "Transfer initiated: $TRANSFER_ID"

# ── 5. Poll for CONFIRMED (30s timeout) ──────────────────────────────────────
echo ""
echo "--- Polling saga state ---"
CONFIRMED=false
STATE="UNKNOWN"
for i in $(seq 1 15); do
  STATE_RESP=$(kexec_curl "http://payment-service:8080/api/payments/transfers/$TRANSFER_ID")
  STATE=$(echo "$STATE_RESP" | grep -o '"state":"[^"]*"' | head -1 | cut -d'"' -f4)
  if [[ "$STATE" == "CONFIRMED" ]]; then
    CONFIRMED=true
    log_pass "Transfer CONFIRMED (attempt $i, ~$((i * 2))s)"
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

# ── 6. Istio mTLS verification ────────────────────────────────────────────────
echo ""
echo "--- Istio mTLS ---"
PA=$(kubectl get peerauthentication -n "$NAMESPACE" bankforge-strict-mtls \
  -o jsonpath='{.spec.mtls.mode}' 2>/dev/null || echo "")
if [[ "$PA" == "STRICT" ]]; then
  log_pass "PeerAuthentication: STRICT mode active"
elif [[ "$PA" == "PERMISSIVE" ]]; then
  log_warn "PeerAuthentication: still PERMISSIVE — apply k8s/istio/peer-authentication-strict.yaml"
else
  log_warn "PeerAuthentication: not found (apply k8s/istio/ after all pods are 2/2 READY)"
fi

# Sidecar count: all bankforge pods should show 2/2 (app + istio-proxy)
TOTAL=$(kubectl get pods -n "$NAMESPACE" --field-selector=status.phase=Running \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[*].name}{"\n"}{end}' 2>/dev/null \
  | grep -c "istio-proxy" || echo "0")
if [[ "$TOTAL" -gt 0 ]]; then
  log_pass "Istio sidecars injected: $TOTAL pods running istio-proxy"
else
  log_warn "No istio-proxy containers found — check istio-injection label on namespace"
fi

# ── 7. Wait for telemetry propagation ────────────────────────────────────────
echo ""
echo "--- Waiting 5s for telemetry propagation ---"
sleep 5

# ── 8. Jaeger traces (optional — skip if not deployed) ───────────────────────
echo ""
echo "--- Jaeger traces ---"
if svc_exists jaeger; then
  NOW_US=$(( $(date +%s) * 1000000 ))
  START_US=$(( ($(date +%s) - 300) * 1000000 ))
  JAEGER_RESP=$(kexec_curl \
    "http://jaeger:16686/api/traces?service=payment-service&start=$START_US&end=$NOW_US&limit=5")
  if echo "$JAEGER_RESP" | grep -q '"traceID"'; then
    log_pass "Jaeger: payment-service traces present"
  else
    log_warn "Jaeger: no payment-service traces found in last 5 minutes"
  fi

  JAEGER_ACCT=$(kexec_curl \
    "http://jaeger:16686/api/traces?service=account-service&start=$START_US&end=$NOW_US&limit=5")
  if echo "$JAEGER_ACCT" | grep -q '"traceID"'; then
    log_pass "Jaeger: account-service traces present"
  else
    log_warn "Jaeger: account-service traces not found"
  fi
else
  log_skip "Jaeger not deployed (deploy in Plan 03-04)"
fi

# ── 9. Prometheus metrics (optional) ─────────────────────────────────────────
echo ""
echo "--- Prometheus metrics ---"
if svc_exists prometheus; then
  PROM_INIT=$(kexec_curl \
    "http://prometheus:9090/api/v1/query?query=transfer_initiated_total")
  if echo "$PROM_INIT" | grep -q '"__name__":"transfer_initiated_total"'; then
    log_pass "Prometheus: transfer_initiated_total present"
  else
    log_warn "Prometheus: transfer_initiated_total not yet visible"
  fi

  PROM_AMT=$(kexec_curl \
    "http://prometheus:9090/api/v1/query?query=transfer_amount_total")
  if echo "$PROM_AMT" | grep -q '"__name__":"transfer_amount_total"'; then
    log_pass "Prometheus: transfer_amount_total present"
  else
    log_warn "Prometheus: transfer_amount_total not yet visible"
  fi
else
  log_skip "Prometheus not deployed (deploy in Plan 03-04)"
fi

# ── 10. Loki logs (optional) ──────────────────────────────────────────────────
echo ""
echo "--- Loki logs ---"
if svc_exists loki; then
  NOW_NS=$(( $(date +%s) * 1000000000 ))
  START_NS=$(( ($(date +%s) - 300) * 1000000000 ))
  LOKI_RESP=$(kexec_curl \
    "http://loki:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment-service%22%7D&start=$START_NS&end=$NOW_NS&limit=5")
  if echo "$LOKI_RESP" | grep -q '"entries"'; then
    log_pass "Loki: payment-service log entries present"
  elif echo "$LOKI_RESP" | grep -q '"streams"'; then
    log_warn "Loki: payment-service stream found but no entries yet"
  else
    log_warn "Loki: payment-service logs not found"
  fi
else
  log_skip "Loki not deployed (deploy in Plan 03-04)"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Results: $PASS passed, $WARN warned, $FAIL failed ==="

if [[ $FAIL -gt 0 ]]; then
  echo ""
  echo "Validation failed — fix the failures above."
  echo "To skip: SKIP_OBS_CHECK=1 bash scripts/validate-observability-k8s.sh"
  exit 1
fi

echo "All critical checks passed."
[[ $WARN -gt 0 ]] && echo "($WARN warnings — observability stack not yet fully deployed)"
exit 0
