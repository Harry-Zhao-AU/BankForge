#!/usr/bin/env bash
set -euo pipefail

CONNECT_URL="${1:-http://localhost:8083}"
CONNECTOR_NAME="bankforge-outbox-connector"

echo "Waiting for Kafka Connect to be ready..."
until curl -sf "$CONNECT_URL/connectors" > /dev/null 2>&1; do
  echo "  ...Kafka Connect not ready, retrying in 5s"
  sleep 5
done

STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$CONNECT_URL/connectors/$CONNECTOR_NAME" 2>/dev/null || echo "000")
if [ "$STATUS" == "200" ]; then
  echo "Updating existing connector '$CONNECTOR_NAME'..."
  curl -s -X PUT -H "Content-Type: application/json" \
    --data "$(jq '.config' infra/debezium/outbox-connector.json)" \
    "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" | jq .
else
  echo "Creating connector '$CONNECTOR_NAME'..."
  curl -s -X POST -H "Content-Type: application/json" \
    --data @infra/debezium/outbox-connector.json \
    "$CONNECT_URL/connectors" | jq .
fi

echo ""
echo "Connector status:"
curl -s "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" | jq .
