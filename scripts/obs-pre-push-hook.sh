#!/usr/bin/env bash
# Pre-push hook — validates the active BankForge environment before every push.
# Detects: Podman running → K8s cluster OR Compose stack → runs the right script.
# Override: SKIP_OBS_CHECK=1 git push

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"

# ── Escape hatch ──────────────────────────────────────────────────────────────
if [[ "${SKIP_OBS_CHECK:-0}" == "1" ]]; then
  echo "[pre-push] Observability check skipped (SKIP_OBS_CHECK=1)" >&2
  exit 0
fi

# ── 1. Check Podman is running ────────────────────────────────────────────────
if ! podman info &>/dev/null; then
  echo "" >&2
  echo "[pre-push] ERROR: Podman is not running or not reachable." >&2
  echo "" >&2
  echo "  Start Podman (WSL2):" >&2
  echo "    podman machine start   (if using podman machine)" >&2
  echo "    — or —" >&2
  echo "    sudo service podman start  (WSL2 rootful)" >&2
  echo "" >&2
  echo "  Then retry: git push" >&2
  echo "  Skip check: SKIP_OBS_CHECK=1 git push" >&2
  echo "" >&2
  exit 1
fi

# ── 2. Detect active environment and validate ─────────────────────────────────
if kubectl get namespace bankforge &>/dev/null 2>&1; then
  echo "[pre-push] K8s cluster detected — running validate-observability-k8s.sh" >&2
  SCRIPT="$REPO_ROOT/scripts/validate-observability-k8s.sh"
else
  echo "[pre-push] No K8s cluster — running validate-observability.sh (Compose)" >&2
  SCRIPT="$REPO_ROOT/scripts/validate-observability.sh"
fi

if bash "$SCRIPT" >&2; then
  exit 0
else
  echo "" >&2
  echo "[pre-push] Validation failed. Fix the issues above, then retry." >&2
  echo "  Skip check: SKIP_OBS_CHECK=1 git push" >&2
  exit 1
fi
