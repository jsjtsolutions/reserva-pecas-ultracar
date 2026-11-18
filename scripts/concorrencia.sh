#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
PECA_ID=$(docker compose exec -T db psql -U reservas -d reservas -tAc \
  "$(cat scripts/sql/cenario_concorrencia.sql)" | tail -1 | tr -d '[:space:]')
echo "peca de disputa: $PECA_ID (estoque 5)"
PECA_ID="$PECA_ID" k6 run carga/reservas-concorrentes.js
curl -s -H 'X-Oficina-Id: 42' "http://localhost:8080/api/v1/pecas/$PECA_ID/saldo"; echo