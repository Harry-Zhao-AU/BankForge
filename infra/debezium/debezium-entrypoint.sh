#!/bin/sh
# Start Debezium Connect, then register all outbox connectors once the REST API is ready.
set -e

# Start the standard Debezium Connect process in the background
/docker-entrypoint.sh start &

echo "Waiting for Kafka Connect REST API..."
until curl -sf http://localhost:8083/connectors > /dev/null 2>&1; do
  sleep 2
done
echo "Kafka Connect is ready. Registering connectors..."

for f in /connectors/*-connector.json; do
  name=$(grep -o '"name":"[^"]*"' "$f" | head -1 | cut -d'"' -f4)
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST -H "Content-Type: application/json" \
    --data "@$f" http://localhost:8083/connectors)
  case "$code" in
    201) echo "[OK]   $name created" ;;
    409) echo "[SKIP] $name already exists" ;;
    *)   echo "[FAIL] $name — HTTP $code"; exit 1 ;;
  esac
done

echo "All connectors registered."

# Keep the container alive by waiting on the background Connect process
wait
