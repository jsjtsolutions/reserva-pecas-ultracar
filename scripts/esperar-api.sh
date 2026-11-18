#!/usr/bin/env bash
set -euo pipefail
URL="${1:-http://localhost:8080/actuator/health}"
for i in $(seq 1 120); do
  if curl -fs "$URL" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "API pronta em $URL"; exit 0
  fi
  sleep 1
done
echo "API nao ficou pronta a tempo" >&2
exit 1