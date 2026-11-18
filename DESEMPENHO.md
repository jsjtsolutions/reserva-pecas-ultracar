# DESEMPENHO — Reserva de Peças (BU Ultracar)

> Todos os números abaixo foram medidos na minha máquina com os comandos deste documento.
> Artefatos brutos em `carga/resultados/` (JSON do k6 + logs) e planos em `carga/resultados/explain-*.txt`.

## 1. Ambiente
| Item | Valor |
|---|---|
| SO / kernel | ⬜ (ex.: Ubuntu 24.04 / WSL2) |
| CPU | ⬜ (modelo, núcleos físicos/lógicos) |
| RAM | ⬜ |
| Docker | ⬜ `docker version` |
| PostgreSQL | 16-alpine (container, `shared_buffers=512MB`, `work_mem=16MB`, `effective_cache_size=1536MB`) |
| API | 1 instância, Temurin 17, `-XX:MaxRAMPercentage=75`, Tomcat 200 threads |
| Hikari | 20 conexões (etapa final: 30) |
| k6 | ⬜ `k6 version` |
| Data | ⬜ |

## 2. Dataset (D1)
`scripts/seed/seed.sql`: **200.000 peças / 50 oficinas** (4.000 por oficina), **400.000 reservas históricas**
(CONFIRMADA/CANCELADA/EXPIRADA) com **2.000.000 de itens**, + **1.000 reservas ATIVAS** (2 itens cada)
para que o cálculo de saldo não seja trivialmente vazio. Conferência automática no fim do seed
(contagens + `pecas_com_saldo_negativo = 0`).

## 3. Cenário (D2)
`carga/busca-pecas.js`: **20 VUs constantes**, 45 s medidos após 10 s de warmup descartado,
`sleep(0.1)`, oficina aleatória 1..50, termo aleatório entre 16 termos (seletivos e amplos),
`GET /api/v1/pecas?busca=<termo>`. Thresholds: **`p(95) < 150 ms`**, falhas < 1 %.

Reproduzir uma etapa:
```bash
FLYWAY_TARGET=<n> BUSCA_SALDO_MODO=<LOTE|N_PLUS_1> DB_POOL=<n> docker compose up -d --force-recreate api
./scripts/esperar-api.sh
k6 run -e DURACAO=45s carga/busca-pecas.js