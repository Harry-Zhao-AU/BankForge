#!/usr/bin/env bash
# smoke-test-obs.sh -- Validates Phase 2 observability stack health
# Usage: ./scripts/smoke-test-obs.sh [--full]
set -euo pipefail

FULL_MODE="${1:-}"
PASS=0
FAIL=0

check() {
    local name="$1" cmd="$2"
    if eval "$cmd" > /dev/null 2>&1; then
        echo "  [PASS] $name"
        ((PASS++))
    else
        echo "  [FAIL] $name"
        ((FAIL++))
    fi
}

echo "=== BankForge Observability Smoke Test ==="
echo ""
echo "--- Service Health ---"
check "OTel Collector healthy" "curl -sf http://localhost:4318/v1/traces > /dev/null 2>&1 || curl -sf http://localhost:8888/metrics > /dev/null 2>&1"
check "Jaeger UI reachable" "curl -sf http://localhost:16686/api/services"
check "Prometheus healthy" "curl -sf http://localhost:9090/-/healthy"
check "Loki ready" "curl -sf http://localhost:3100/ready"
check "Grafana healthy" "curl -sf http://localhost:3000/api/health"

echo ""
echo "--- Data Sources ---"
check "Prometheus has scrape targets" "curl -sf http://localhost:9090/api/v1/targets | grep -q 'otel-collector'"
check "Grafana datasources provisioned" "curl -sf http://localhost:3000/api/datasources | grep -q 'Prometheus'"

if [ "$FULL_MODE" = "--full" ]; then
    echo ""
    echo "--- Full Verification (requires running services + traffic) ---"
    check "Jaeger has services" "curl -sf http://localhost:16686/api/services | grep -q 'data'"
    check "Loki has labels" "curl -sf http://localhost:3100/loki/api/v1/labels | grep -q 'service_name'"
    check "Prometheus has metrics" "curl -sf 'http://localhost:9090/api/v1/query?query=up' | grep -q '\"result\"'"
    check "Grafana dashboard exists" "curl -sf http://localhost:3000/api/dashboards/uid/bankforge-banking-overview | grep -q 'Banking Overview'"
fi

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
