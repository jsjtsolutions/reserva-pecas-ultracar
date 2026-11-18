.PHONY: up down reset seed test verify logs psql cluster-up cluster-down concorrencia cluster-test medir explain carga

up:            ; ./scripts/up.sh
down:          ; docker compose down
reset:         ; docker compose down -v
seed:          ; docker compose --profile seed run --rm seed
logs:          ; docker compose logs -f api
psql:          ; docker compose exec db psql -U reservas -d reservas
test:          ; mvn -B test
verify:        ; mvn -B verify
carga:         ; k6 run -e DURACAO=60s carga/busca-pecas.js
medir:         ; ./scripts/medir.sh
explain:       ; docker compose exec -T db psql -U reservas -d reservas -f - < scripts/sql/explain_busca.sql
concorrencia:  ; ./scripts/concorrencia.sh
cluster-up:    ; docker compose -f docker-compose.cluster.yml up --build -d && ./scripts/esperar-api.sh
cluster-down:  ; docker compose -f docker-compose.cluster.yml down -v
cluster-test:  ; ./scripts/teste-concorrencia-cluster.sh