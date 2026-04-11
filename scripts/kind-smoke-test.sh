#!/usr/bin/env bash
# =============================================================================
# kind Networking Spike — Phase 1.1
#
# Validates: kind + Podman rootful mode, single-node cluster creation,
# test pod reachability, and cluster-internal DNS resolution.
#
# Prerequisite gate for Phase 3 (Service Mesh & Auth).
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
kubectl version --client || { echo "ERROR: kubectl not found. Install: curl -LO https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && chmod +x kubectl && sudo mv kubectl /usr/local/bin/kubectl"; exit 1; }

echo "Checking Podman rootful mode..."
ROOTFUL=$(podman machine inspect --format '{{.Rootful}}' 2>/dev/null || echo "unknown")
echo "Podman rootful: $ROOTFUL"
if [[ "$ROOTFUL" != "true" ]]; then
    echo "WARNING: Podman machine may not be rootful. kind requires rootful mode."
    echo "Fix: podman machine stop && podman machine set --rootful && podman machine start"
    echo "Continuing anyway — kind will fail if rootful is truly required..."
fi
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
kubectl get pods -n kube-system
echo ""

echo "=== Step 5: Deploy test pod ==="
kubectl run dns-test --image=busybox:1.36 --restart=Never -- sleep 300
echo "Waiting for pod to be Ready..."
kubectl wait --for=condition=Ready pod/dns-test --timeout=120s
echo "Pod is running."
echo ""

echo "=== Step 6: DNS validation ==="
echo "Resolving kubernetes.default..."
kubectl exec dns-test -- nslookup kubernetes.default
echo ""
echo "Resolving kubernetes.default.svc.cluster.local..."
kubectl exec dns-test -- nslookup kubernetes.default.svc.cluster.local
echo ""

echo "================================================================"
echo "  SPIKE PASSED"
echo ""
echo "  Results:"
echo "    - kind cluster: created via Podman ($ROOTFUL rootful)"
echo "    - Node status: Ready"
echo "    - Pod deployment: successful"
echo "    - DNS resolution: working"
echo ""
echo "  Phase 3 prerequisite: SATISFIED"
echo "================================================================"
