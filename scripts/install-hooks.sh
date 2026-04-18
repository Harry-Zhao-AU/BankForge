#!/usr/bin/env bash
# install-hooks.sh — wire git hooks after cloning
# Run once: bash scripts/install-hooks.sh
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOKS_DIR="$REPO_ROOT/.git/hooks"

cp "$REPO_ROOT/scripts/obs-pre-push-hook.sh" "$HOOKS_DIR/pre-push"
chmod +x "$HOOKS_DIR/pre-push"

echo "Installed: .git/hooks/pre-push"
echo "Pre-push observability validation is now active."
echo "Override when stack is down: SKIP_OBS_CHECK=1 git push"
