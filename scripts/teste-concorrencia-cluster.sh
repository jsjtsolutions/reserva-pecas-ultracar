#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${BASE_URL:-http://localhost:8080}"
OFICINA=42
COMPOSE="docker compose -f docker-compose.cluster.yml"

PECA_ID=$($COMPOSE exec -T db psql -U reservas -d reservas -tAc \
  "INSERT INTO peca (oficina_id,codigo,descricao,estoque_fisico) VALUES ($OFICINA,'P-CLUSTER','disputa cluster',5)
   ON CONFLICT (oficina_id,codigo) DO UPDATE SET estoque_fisico=5 RETURNING id" | tr -d '[:space:]')
$COMPOSE exec -T db psql -U reservas -d reservas -c \
  "DELETE FROM reserva_item WHERE peca_id=$PECA_ID" >/dev/null

TMP=$(mktemp -d); echo "peca=$PECA_ID"
for i in $(seq 1 20); do
  ( curl -s -D "$TMP/h$i" -o "$TMP/b$i" -w '%{http_code}' -X POST "$BASE/api/v1/reservas" \
      -H 'Content-Type: application/json' -H "X-Oficina-Id: $OFICINA" -H 'X-User-Id: 1001' \
      -H "Idempotency-Key: cluster-$i-$(date +%s%N)-$RANDOM" \
      -d "{\"ordemServicoId\":\"OS-CL-$i\",\"itens\":[{\"pecaId\":$PECA_ID,\"quantidade\":1}]}" > "$TMP/c$i" ) &
done
wait

C201=$(grep -l '^201$' "$TMP"/c* | wc -l | tr -d ' ')
C409=$(grep -l '^409$' "$TMP"/c* | wc -l | tr -d ' ')
echo "201=$C201  409=$C409  (esperado 5 / 15)"
echo "instancias que atenderam:"; grep -ih '^x-instancia:' "$TMP"/h* | sort | uniq -c
echo -n "saldo final: "; curl -s -H "X-Oficina-Id: $OFICINA" "$BASE/api/v1/pecas/$PECA_ID/saldo"; echo
rm -rf "$TMP"
[ "$C201" = "5" ] && [ "$C409" = "15" ] || { echo "FALHOU"; exit 1; }
echo "OK: saldo nunca negativo com 3 instancias"