#!/bin/bash
# Forward cluster services to localhost for MCP server and local debugging.
# Usage: bash scripts/port-forward.sh
# Stop: Ctrl+C (cleans up all background kubectl processes)

set -euo pipefail

NAMESPACE=bankforge
PIDS=()

forward() {
  local name=$1
  local local_port=$2
  local svc=$3
  local remote_port=$4

  kubectl port-forward -n "$NAMESPACE" "svc/$svc" "${local_port}:${remote_port}" &>/dev/null &
  PIDS+=($!)
  echo "  ✓ $name  localhost:${local_port} → $svc:${remote_port}"
}

cleanup() {
  echo ""
  echo "Stopping port-forwards..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  echo "Done."
}

trap cleanup SIGINT SIGTERM EXIT

echo "Starting port-forwards (Ctrl+C to stop)..."
echo ""

# Banking services
forward "account-service    " 8081 account-service     8080
forward "payment-service    " 8082 payment-service     8080
forward "ledger-service     " 8083 ledger-service      8080
forward "notification-service" 8084 notification-service 8080

# Observability
forward "otel-collector     " 4318 otel-collector      4318
forward "jaeger             " 16686 jaeger             16686
forward "prometheus         " 9090 prometheus          9090
forward "grafana            " 3000 grafana             3000
kubectl port-forward -n istio-system "svc/kiali" "20001:20001" &>/dev/null &
PIDS+=($!)
echo "  ✓ kiali              localhost:20001 → kiali:20001 (istio-system)"

# Auth (Plan 03-04)
forward "keycloak           " 8090 keycloak            8080

# Kong (kong namespace — forwarded separately)
kubectl port-forward -n kong "svc/kong-gateway-proxy" "8000:80" &>/dev/null &
PIDS+=($!)
echo "  ✓ kong-proxy        localhost:8000 → kong-gateway-proxy:80"

echo ""
echo "All port-forwards running. Press Ctrl+C to stop."

wait
