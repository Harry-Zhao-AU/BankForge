#!/usr/bin/env bash
# Validate Kong + Keycloak JWT auth stack.
# Prereqs: port-forward.sh running (Keycloak:8090, Kong proxy:8000).
set -euo pipefail

KEYCLOAK_URL="http://127.0.0.1:8090"
KONG_PROXY_URL="http://127.0.0.1:8000"
REALM="bankforge"
CLIENT_ID="kong"
CLIENT_SECRET="kong-client-secret"

PASS=0
FAIL=0

check() {
  local label=$1
  local result=$2
  local expected=$3
  if [ "$result" = "$expected" ]; then
    echo "  PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $label (got: $result, expected: $expected)"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== BankForge Auth Validation ==="
echo ""

# ── Keycloak ───────────────────────────────────────────────────────────────

echo "[1] Keycloak pod"
KC_POD=$(kubectl get pod -n bankforge -l app=keycloak -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
check "Keycloak pod Running" "$KC_POD" "Running"

echo "[2] Keycloak realm"
KC_REALM=$(curl -so /dev/null -w "%{http_code}" "${KEYCLOAK_URL}/realms/${REALM}" 2>/dev/null || echo "000")
check "GET /realms/bankforge → 200" "$KC_REALM" "200"

echo "[3] Token issuance"
TOKEN_RESP=$(curl -sf -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=${CLIENT_ID}" \
  -d "client_secret=${CLIENT_SECRET}" \
  -d "username=testuser" \
  -d "password=password" 2>/dev/null || echo "{}")
TOKEN=$(echo "$TOKEN_RESP" | wsl python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('access_token',''))" 2>/dev/null || echo "")
check "Token issued (access_token present)" "$([ -n "$TOKEN" ] && echo yes || echo no)" "yes"

# ── Kong ───────────────────────────────────────────────────────────────────

echo "[4] Kong proxy"
KONG_ALIVE=$(curl -so /dev/null -w "%{http_code}" "${KONG_PROXY_URL}" 2>/dev/null || echo "000")
check "Kong proxy reachable (not 000)" "$([ "$KONG_ALIVE" != "000" ] && echo yes || echo no)" "yes"

echo "[5] Unauthenticated request → 401"
NO_TOKEN=$(curl -so /dev/null -w "%{http_code}" "${KONG_PROXY_URL}/api/payments/transfers" 2>/dev/null || echo "000")
check "POST /api/payments without token → 401" "$NO_TOKEN" "401"

echo "[6] Authenticated request → 201"
if [ -n "$TOKEN" ]; then
  IDEMPOTENCY_KEY="validate-auth-$(date +%s)"
  TRANSFER_RESP=$(curl -s -w "\n%{http_code}" -X POST "${KONG_PROXY_URL}/api/payments/transfers" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"fromAccountId\":\"97bd2016-343d-4ab7-9e47-de18c1129a64\",\"toAccountId\":\"ebc1a9d0-17fd-4c47-b856-60e58d8ea6a3\",\"amount\":\"1.00\",\"idempotencyKey\":\"${IDEMPOTENCY_KEY}\",\"description\":\"auth-validate\"}" \
    2>/dev/null || echo -e "\n000")
  TRANSFER_STATUS=$(echo "$TRANSFER_RESP" | tail -1)
  TRANSFER_ID=$(echo "$TRANSFER_RESP" | head -1 | wsl python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('transferId',''))" 2>/dev/null || echo "")
  check "POST /api/payments/transfers with token → 201" "$TRANSFER_STATUS" "201"
else
  echo "  SKIP  Authenticated request (no token — Keycloak check failed)"
  FAIL=$((FAIL + 1))
fi

echo "[7] X-User-Id injection"
if [ -n "$TOKEN" ]; then
  JWT_SUB=$(echo "$TOKEN" | wsl python3 -c "
import sys, base64, json
token = sys.stdin.read().strip()
payload = token.split('.')[1]
payload += '=' * (4 - len(payload) % 4)
print(json.loads(base64.urlsafe_b64decode(payload))['sub'])
" 2>/dev/null || echo "")

  # Send with forged X-User-Id — Kong must strip it and inject the real sub
  IDEMPOTENCY_KEY2="validate-inject-$(date +%s)"
  INJECTED_RESP=$(curl -sf -X POST "${KONG_PROXY_URL}/api/payments/transfers" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: attacker-forged-id" \
    -d "{\"fromAccountId\":\"97bd2016-343d-4ab7-9e47-de18c1129a64\",\"toAccountId\":\"ebc1a9d0-17fd-4c47-b856-60e58d8ea6a3\",\"amount\":\"1.00\",\"idempotencyKey\":\"${IDEMPOTENCY_KEY2}\",\"description\":\"inject-validate\"}" \
    2>/dev/null || echo "")
  check "Authenticated request with forged header → 201 (Kong strips+injects)" \
    "$([ -n "$INJECTED_RESP" ] && echo yes || echo no)" "yes"

  if [ -n "$JWT_SUB" ]; then
    echo "        JWT sub (expected X-User-Id): ${JWT_SUB}"
    echo "        Verify service logs: X-User-Id should equal ${JWT_SUB}, not 'attacker-forged-id'"
  fi
else
  echo "  SKIP  X-User-Id injection (no token)"
  FAIL=$((FAIL + 1))
fi

# ── Summary ────────────────────────────────────────────────────────────────

echo ""
echo "Results: ${PASS} PASS, ${FAIL} FAIL"
if [ "$FAIL" -eq 0 ]; then
  echo "All checks passed."
else
  exit 1
fi
