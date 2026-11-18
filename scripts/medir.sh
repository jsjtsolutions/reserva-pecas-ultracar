#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p carga/resultados

etapa() {
  local alvo="$1" nome="$2" modo="${3:-LOTE}" pool="${4:-20}"
  echo "=================== $nome (flyway=$alvo saldo=$modo pool=$pool) ==================="
  FLYWAY_TARGET="$alvo" BUSCA_SALDO_MODO="$modo" DB_POOL="$pool" docker compose up -d --force-recreate api
  ./scripts/esperar-api.sh
  echo "-- warmup 10s (descartado)"
  k6 run -e DURACAO=10s carga/busca-pecas.js >/dev/null 2>&1 || true
  k6 run -e DURACAO=45s carga/busca-pecas.js | tee "carga/resultados/$nome.log" || true
  cp carga/resultado-busca.json "carga/resultados/$nome.json"
}

etapa 2 etapa1-base            # sem trgm, sem partial index, sem covering
etapa 3 etapa2-trgm            # + GIN pg_trgm
etapa 4 etapa3-saldo           # + partial/covering do saldo
etapa 5 etapa4-cobertura       # + covering da busca
etapa 5 etapa5-nmais1 N_PLUS_1 # regressao proposital: saldo N+1
etapa 5 etapa6-final LOTE 30   # ajuste de pool

echo; echo "Resumo:"; grep -H 'p95' carga/resultados/*.log
echo "Agora capture os planos: make explain > carga/resultados/explain-final.txt"