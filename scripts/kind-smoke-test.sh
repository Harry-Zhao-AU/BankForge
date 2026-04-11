#!/usr/bin/env bash
# =============================================================================
# kind Networking Spike — Phase 1.1
#
# Validates: kind + Podman rootful mode, single-node cluster creation,
# test pod reachability, and cluster-internal DNS resolution.
#
# Prerequisite gate for Phase 3 (Service Mesh & Auth).
#
# REQUIRED WORKAROUNDS (discovered during Phase 1.1 spike):
#   1. Podman network 'kind' must be created with --disable-dns to prevent
#      aardvark-dns from trying to start a transient systemd scope unit
#      (fails in WSL2: "Transport endpoint is not connected").
#   2. Root's Podman log driver must be set to k8s-file (not journald).
#      With journald, `podman logs` returns no output and kind cannot
#      detect that systemd reached multi-user.target.
#   Run these prerequisites as root inside WSL2 before executing this script:
#     sudo podman network rm kind 2>/dev/null || true
#     sudo podman network create --disable-dns kind
#     sudo bash -c 'mkdir -p /root/.config/containers && \
#       printf "[containers]\nlog_driver = \"k8s-file\"\n" \
#       > /root/.config/containers/containers.conf'
# =============================================================================
set -euo pipefail

CLUSTER_NAME="bankforge-spike"

# Cleanup trap — always delete cluster, even on failure
cleanup() {
    echo ""
    echo "=== Cleanup: Deleting kind cluster '$CLUSTER_NAME' ==="
    KIND_EXPERIMENTAL_PROVIDER=podman kind delete cluster --name "$CLUSTER_NAME" 2>/dev/null || true
    echo "Cleanup complete."
}
trap cleanup EXIT

echo "================================================================"
echo "  BankForge kind + Podman Networking Spike"
echo "================================================================"
echo ""

echo "=== Step 1: Verify prerequisites ==="
echo "Checking kind..."
kind version || { echo "ERROR: kind not found. Install: curl -Lo kind https://kind.sigs.k8s.io/dl/v0.27.0/kind-linux-amd64 && chmod +x kind && sudo mv kind /usr/local/bin/kind"; exit 1; }

echo "Checking kubectl..."
kubectl version --client 2>&1 | head -1 || { echo "ERROR: kubectl not found."; exit 1; }

echo "Checking Podman rootful mode..."
ROOTFUL=$(podman machine inspect --format '{{.Rootful}}' 2>/dev/null || echo "unknown")
echo "Podman rootful: $ROOTFUL"
if [[ "$ROOTFUL" != "true" ]]; then
    echo "WARNING: Podman machine may not be rootful. kind requires rootful mode."
    echo "Fix: podman machine stop && podman machine set --rootful && podman machine start"
    echo "Continuing anyway — kind will fail if rootful is truly required..."
fi
echo ""

echo "=== Step 1b: Apply WSL2 workarounds ==="
echo "Recreating 'kind' network with DNS disabled (prevents aardvark-dns failure)..."
podman network rm kind 2>/dev/null || true
podman network create --disable-dns kind
echo "Configuring k8s-file log driver (required for podman logs to work with systemd)..."
CONTAINERS_CONF="/root/.config/containers/containers.conf"
mkdir -p "$(dirname "$CONTAINERS_CONF")"
if ! grep -q 'log_driver' "$CONTAINERS_CONF" 2>/dev/null; then
    printf '[containers]\nlog_driver = "k8s-file"\n' >> "$CONTAINERS_CONF"
    echo "Appended k8s-file log_driver to $CONTAINERS_CONF"
else
    echo "log_driver already configured in $CONTAINERS_CONF — skipping"
fi
echo "Workarounds applied."
echo ""

echo "=== Step 2: Create kind cluster ==="
export KIND_EXPERIMENTAL_PROVIDER=podman
kind create cluster --name "$CLUSTER_NAME" --wait 120s
echo "Cluster created successfully."
echo ""

echo "=== Step 3: Verify node is Ready ==="
kubectl wait --for=condition=Ready node/"$CLUSTER_NAME-control-plane" --timeout=120s
kubectl get nodes -o wide
echo ""

echo "=== Step 4: Check kube-system pods (CoreDNS health) ==="
kubectl wait --for=condition=Ready pod -l k8s-app=kube-dns -n kube-system --timeout=120s
kubectl get pods -n kube-system
echo ""

echo "=== Step 5: Deploy test pod ==="
kubectl run dns-test --image=busybox:1.36 --restart=Never -- sleep 300
echo "Waiting for pod to be Ready..."
kubectl wait --for=condition=Ready pod/dns-test --timeout=120s
echo "Pod is running."
echo ""

echo "=== Step 6: DNS validation ==="
echo "Resolving kubernetes.default.svc.cluster.local (FQDN — authoritative test)..."
kubectl exec dns-test -- nslookup kubernetes.default.svc.cluster.local
echo ""
echo "Note: short-form 'kubernetes.default' may return NXDOMAIN on busybox nslookup"
echo "      due to search domain handling — this is a busybox limitation, not a DNS failure."
echo ""

KIND_VERSION=$(kind version | head -1)
KUBECTL_VERSION=$(kubectl version --client 2>&1 | head -1)
PODMAN_VERSION=$(podman --version 2>/dev/null || echo "unknown")

echo "================================================================"
echo "  SPIKE PASSED"
echo ""
echo "  Results:"
echo "    - kind cluster: created via Podman (rootful=$ROOTFUL)"
echo "    - Node status: Ready"
echo "    - CoreDNS: Running"
echo "    - Pod deployment: successful"
echo "    - DNS resolution (FQDN): working"
echo ""
echo "  Tool versions:"
echo "    - $KIND_VERSION"
echo "    - $KUBECTL_VERSION"
echo "    - $PODMAN_VERSION"
echo ""
echo "  Required workarounds:"
echo "    - Podman 'kind' network recreated with --disable-dns"
echo "    - Root Podman log_driver set to k8s-file"
echo ""
echo "  Phase 3 prerequisite: SATISFIED"
echo "================================================================"
