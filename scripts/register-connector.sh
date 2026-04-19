#!/usr/bin/env bash
# register-connector.sh — Register all Debezium outbox connectors
#
# Usage: ./register-connector.sh [connect-url]
# Default connect-url: http://localhost:8085

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECTOR_DIR="$SCRIPT_DIR/../infra/debezium"
CONNECT_URL="${1:-http://localhost:8085}"

echo "Waiting for Kafka Connect to be ready at $CONNECT_URL..."
until curl -sf "$CONNECT_URL/connectors" > /dev/null 2>&1; do
  echo "  ...not ready, retrying in 5s"
  sleep 5
done
echo "Kafka Connect is ready."

register_or_update() {
  local json_file="$1"
  local name
  name=$(python3 -c "import json,sys; print(json.load(open('$json_file'))['name'])")

  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" "$CONNECT_URL/connectors/$name" 2>/dev/null || echo "000")

  if [ "$status" == "200" ]; then
    echo "Updating connector '$name'..."
    local config
    config=$(python3 -c "import json,sys; d=json.load(open('$json_file')); print(json.dumps(d.get('config', d)))")
    curl -s -X PUT -H "Content-Type: application/json" \
      --data "$config" \
      "$CONNECT_URL/connectors/$name/config" | python3 -m json.tool
  else
    echo "Creating connector '$name'..."
    curl -s -X POST -H "Content-Type: application/json" \
      --data @"$json_file" \
      "$CONNECT_URL/connectors" | python3 -m json.tool
  fi

  echo "Status of '$name':"
  curl -s "$CONNECT_URL/connectors/$name/status" | python3 -m json.tool
  echo ""
}

for json_file in "$CONNECTOR_DIR"/*-connector.json; do
  register_or_update "$json_file"
done
