#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up --build -d
./scripts/esperar-api.sh
TOTAL=$(docker compose exec -T db psql -U reservas -d reservas -tAc "SELECT count(*) FROM peca" | tr -d '[:space:]')
if [ "${TOTAL:-0}" -lt 200000 ]; then
  echo ">>> carregando seed (200k pecas / 2M itens)..."
  docker compose --profile seed run --rm seed
fi
echo
echo "API:      http://localhost:8080"
echo "Health:   http://localhost:8080/actuator/health"
echo "Exemplo:  curl -H 'X-Oficina-Id: 42' 'http://localhost:8080/api/v1/pecas?busca=Pastilha'"