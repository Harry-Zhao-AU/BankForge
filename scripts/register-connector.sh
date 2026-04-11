#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECTOR_JSON="$SCRIPT_DIR/../infra/debezium/outbox-connector.json"

CONNECT_URL="${1:-http://localhost:8085}"
CONNECTOR_NAME="bankforge-outbox-connector"

echo "Waiting for Kafka Connect to be ready..."
until curl -sf "$CONNECT_URL/connectors" > /dev/null 2>&1; do
  echo "  ...Kafka Connect not ready, retrying in 5s"
  sleep 5
done

STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$CONNECT_URL/connectors/$CONNECTOR_NAME" 2>/dev/null || echo "000")
if [ "$STATUS" == "200" ]; then
  echo "Updating existing connector '$CONNECTOR_NAME'..."
  CONFIG=$(python3 -c "import json,sys; d=json.load(open('$CONNECTOR_JSON')); print(json.dumps(d.get('config', d)))")
  curl -s -X PUT -H "Content-Type: application/json" \
    --data "$CONFIG" \
    "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" | python3 -m json.tool
else
  echo "Creating connector '$CONNECTOR_NAME'..."
  curl -s -X POST -H "Content-Type: application/json" \
    --data @"$CONNECTOR_JSON" \
    "$CONNECT_URL/connectors" | python3 -m json.tool
fi

echo ""
echo "Connector status:"
curl -s "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" | python3 -m json.tool
