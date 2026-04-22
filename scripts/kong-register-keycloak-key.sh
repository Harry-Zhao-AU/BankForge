#!/usr/bin/env bash
# Register Keycloak RS256 public key with Kong via KongConsumer + K8s Secret.
# KIC (DB-less mode) reads K8s resources directly — no Admin API curl needed.
# Safe to re-run — deletes existing credential before re-creating.
# Requires: kubectl on PATH, wsl with cryptography installed (pip install cryptography).
set -euo pipefail

REALM="bankforge"
LOCAL_PORT="18090"   # temp port — avoids collision with any existing port-forward
KEYCLOAK_URL="http://127.0.0.1:${LOCAL_PORT}"
ISSUER="http://keycloak.bankforge.svc.cluster.local:8080/realms/${REALM}"

echo "=== Kong: Register Keycloak RS256 public key ==="

# ── Start temporary port-forward to Keycloak ──────────────────────────────

echo "[1/4] Starting temporary port-forward to Keycloak..."
kubectl port-forward -n bankforge svc/keycloak "${LOCAL_PORT}:8080" &>/tmp/pf-kc-reg.log &
PF_PID=$!
trap "kill $PF_PID 2>/dev/null || true" EXIT

for i in $(seq 1 20); do
  STATUS=$(curl -so /dev/null -w "%{http_code}" "${KEYCLOAK_URL}/realms/${REALM}" 2>/dev/null || echo "000")
  if [ "$STATUS" = "200" ]; then
    echo "  Keycloak reachable."
    break
  fi
  if [ "$i" -eq 20 ]; then
    echo "ERROR: Keycloak not reachable after 20s (last HTTP status: ${STATUS})."
    cat /tmp/pf-kc-reg.log
    exit 1
  fi
  sleep 1
done

# ── Fetch RS256 public key from JWKS ──────────────────────────────────────

echo "[2/4] Fetching RS256 public key from Keycloak JWKS..."
JWKS=$(curl -sf "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/certs")

PEM=$(echo "$JWKS" | wsl python3 -c "
import sys, json, base64
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicNumbers
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import serialization

data = json.load(sys.stdin)
key = next(k for k in data['keys'] if k['alg'] == 'RS256' and k['use'] == 'sig')

def b64_to_int(b64):
    b = base64.urlsafe_b64decode(b64 + '==')
    return int.from_bytes(b, 'big')

pub = RSAPublicNumbers(b64_to_int(key['e']), b64_to_int(key['n'])) \
        .public_key(default_backend())
pem = pub.public_bytes(
    serialization.Encoding.PEM,
    serialization.PublicFormat.SubjectPublicKeyInfo
)
print(pem.decode().strip())
")

if [ -z "$PEM" ]; then
  echo "ERROR: Failed to extract RS256 public key."
  exit 1
fi
echo "  Public key extracted ($(echo "$PEM" | wc -l) lines)."

# ── Apply KongConsumer + JWT credential Secret ─────────────────────────────

echo "[3/4] Deleting existing credential (if any)..."
kubectl delete secret keycloak-jwt-credential -n bankforge 2>/dev/null || true
kubectl delete kongconsumer keycloak-issuer -n bankforge 2>/dev/null || true
sleep 1

echo "[4/4] Applying KongConsumer and JWT credential Secret..."

PEM_INDENTED=$(echo "$PEM" | sed 's/^/    /')

kubectl apply -f - <<YAML
apiVersion: v1
kind: Secret
metadata:
  name: keycloak-jwt-credential
  namespace: bankforge
  labels:
    konghq.com/credential: jwt
stringData:
  kongCredType: jwt
  algorithm: RS256
  key: "${ISSUER}"
  rsa_public_key: |
${PEM_INDENTED}
---
apiVersion: configuration.konghq.com/v1
kind: KongConsumer
metadata:
  name: keycloak-issuer
  namespace: bankforge
  annotations:
    kubernetes.io/ingress.class: kong
username: keycloak-issuer
credentials:
  - keycloak-jwt-credential
YAML

echo ""
echo "=== Done ==="
echo "KongConsumer 'keycloak-issuer' registered with Keycloak RS256 public key."
echo "Issuer (JWT key): ${ISSUER}"
echo ""
echo "Next: run bash scripts/validate-auth-k8s.sh"
