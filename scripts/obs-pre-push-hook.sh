#!/usr/bin/env bash
# Claude Code PreToolUse hook wrapper — called before git push
# Runs validate-observability.sh and outputs the deny JSON if it fails.
# Claude Code prepends "bash" to the command string, so this must be a file path.
SCRIPT="$(dirname "$0")/validate-observability.sh"
if bash "$SCRIPT" >&2; then
    exit 0
else
    printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Observability validation failed — run scripts/validate-observability.sh to debug, or set SKIP_OBS_CHECK=1 to bypass"}}'
    exit 1
fi
