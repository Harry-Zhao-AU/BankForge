#!/usr/bin/env bash
# Bootstrap the BankForge kind cluster with Istio.
# Prerequisites: kind v0.27.0, kubectl v1.35.3, Podman rootful running in WSL2.
# Run from repo root: bash scripts/k8s-cluster-up.sh
set -euo pipefail

CLUSTER_NAME="bankforge"
ISTIO_VERSION="1.29.2"
NAMESPACE="bankforge"

echo "=== BankForge kind cluster bootstrap ==="
echo ""

# ── Podman WSL2 pre-flight ─────────────────────────────────────────────────

echo "[1/6] Checking Podman log driver..."
LOG_DRIVER=$(podman info --format '{{.Host.LogDriver}}' 2>/dev/null || echo "unknown")
if [ "$LOG_DRIVER" != "k8s-file" ]; then
  echo "WARNING: Podman log_driver is '$LOG_DRIVER', expected 'k8s-file'."
  echo "kind may fail to read container logs without k8s-file."
  echo "Fix (run as root in WSL2):"
  echo "  echo '[containers]' >> /etc/containers/containers.conf"
  echo "  echo 'log_driver = \"k8s-file\"' >> /etc/containers/containers.conf"
  echo "Then restart Podman and re-run this script."
  echo "Continuing anyway — cluster creation may still succeed..."
fi

echo "[2/6] Ensuring 'kind' Podman network exists (--disable-dns to avoid aardvark-dns conflicts)..."
if podman network exists kind 2>/dev/null; then
  echo "  'kind' network already exists — skipping creation."
else
  podman network create --disable-dns kind
  echo "  Created 'kind' network with --disable-dns."
fi

# ── Cluster creation ───────────────────────────────────────────────────────

echo "[3/6] Creating kind cluster '$CLUSTER_NAME'..."
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "  Cluster '$CLUSTER_NAME' already exists — skipping creation."
else
  KIND_EXPERIMENTAL_PROVIDER=podman kind create cluster \
    --config infra/kind/kind-config.yaml \
    --name "$CLUSTER_NAME" \
    --wait 120s
  echo "  Cluster created."
fi

echo "  Verifying nodes..."
kubectl get nodes --context "kind-${CLUSTER_NAME}"

# ── Istio installation ─────────────────────────────────────────────────────

echo "[4/6] Installing Istio ${ISTIO_VERSION}..."
if kubectl get namespace istio-system --context "kind-${CLUSTER_NAME}" &>/dev/null; then
  echo "  istio-system namespace already exists — skipping Istio install."
else
  if ! command -v istioctl &>/dev/null; then
    echo "ERROR: istioctl not found in PATH. Install with:"
    echo "  curl -Lo istioctl.zip https://github.com/istio/istio/releases/download/${ISTIO_VERSION}/istioctl-${ISTIO_VERSION}-win.zip"
    echo "  unzip istioctl.zip && mv istioctl.exe ~/bin/"
    exit 1
  fi

  istioctl install --set profile=demo --context "kind-${CLUSTER_NAME}" -y

  echo "  Waiting for Istio control plane to be ready..."
  kubectl wait --for=condition=Ready pods --all -n istio-system \
    --timeout=300s --context "kind-${CLUSTER_NAME}"

  echo "  Verifying install..."
  istioctl analyze --context "kind-${CLUSTER_NAME}" -n istio-system 2>&1 | head -5 || true
fi

# ── Namespace ──────────────────────────────────────────────────────────────

echo "[5/6] Creating and labelling '${NAMESPACE}' namespace..."
kubectl apply -f k8s/namespace.yaml --context "kind-${CLUSTER_NAME}"

# ── Secrets ───────────────────────────────────────────────────────────────

echo "[6/6] Creating database secrets in '${NAMESPACE}'..."
for svc in account payment ledger notification; do
  SECRET_NAME="${svc}-db-secret"
  if kubectl get secret "$SECRET_NAME" -n "$NAMESPACE" \
      --context "kind-${CLUSTER_NAME}" &>/dev/null; then
    echo "  Secret $SECRET_NAME already exists — skipping."
  else
    kubectl create secret generic "$SECRET_NAME" \
      --from-literal=password=secret \
      -n "$NAMESPACE" \
      --context "kind-${CLUSTER_NAME}"
    echo "  Created $SECRET_NAME."
  fi
done

echo ""
echo "=== Bootstrap complete ==="
echo ""
echo "Next steps:"
echo "  1. Apply infrastructure:  kubectl apply -f k8s/infrastructure/ -n bankforge"
echo "  2. Verify pods (2/2):     kubectl get pods -n bankforge -w"
echo "  3. Continue to Plan 03-02 (banking services + Istio STRICT)"
