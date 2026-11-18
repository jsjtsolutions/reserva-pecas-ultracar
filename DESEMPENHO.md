# DESEMPENHO — Reserva de Peças (BU Ultracar)

> Os resultados de desempenho devem ser reproduzidos com os comandos deste documento.
> Artefatos brutos em `carga/resultados/` (JSON do k6 + logs) e planos em `carga/resultados/explain-*.txt`.
> O log Maven registrado em 2026-11-18 comprova testes e build bem-sucedidos, mas não contém métricas de carga/latência do k6.

## 1. Ambiente
| Item | Valor |
|---|---|
| SO / kernel | Windows (versão/kernel não identificados no log) |
| CPU | Não informado no log |
| RAM | 3.827 MB disponíveis para Docker (memória física total não informada) |
| Docker | Docker Desktop 29.9.3, API 1.56 |
| PostgreSQL | 16.16; testes de integração via Testcontainers `postgres:16-alpine`. Configurações de `shared_buffers`, `work_mem` e `effective_cache_size` não constam no log. |
| API | Spring Boot 3.3.5; Java 21.0.13 nos testes; Tomcat 10.1.31 nos testes de integração |
| Hikari | Pool iniciado nos testes; tamanho não informado no log |
| k6 | Não informado; o log não mostra execução do k6 |
| Data da execução registrada | 2026-11-18 |

## 2. Dataset (D1)
`scripts/seed/seed.sql`: **200.000 peças / 50 oficinas** (4.000 por oficina), **400.000 reservas históricas**
(CONFIRMADA/CANCELADA/EXPIRADA) com **2.000.000 de itens**, + **1.000 reservas ATIVAS** (2 itens cada)
para que o cálculo de saldo não seja trivialmente vazio. Conferência automática no fim do seed
(contagens + `pecas_com_saldo_negativo = 0`).

> O log de testes informado não executa nem valida esse seed; os números acima descrevem o dataset definido no script.

## 3. Cenário (D2)
`carga/busca-pecas.js`: **20 VUs constantes**, 45 s medidos após 10 s de warmup descartado,
`sleep(0.1)`, oficina aleatória 1..50, termo aleatório entre 16 termos (seletivos e amplos),
`GET /api/v1/pecas?busca=<termo>`. Thresholds: **`p(95) < 150 ms`**, falhas < 1 %.

Reproduzir uma etapa:
```bash
FLYWAY_TARGET=<n> BUSCA_SALDO_MODO=<LOTE|N_PLUS_1> DB_POOL=<n> docker compose up -d --force-recreate api
./scripts/esperar-api.sh
k6 run -e DURACAO=45s carga/busca-pecas.js
```

> O log de 2026-11-18 não contém resultado desse cenário (p95, taxa de falhas ou throughput). Não inferir aprovação dos thresholds de desempenho a partir dos testes automatizados abaixo.

## 4. Evidência de testes e build (2026-11-18)

Execução Maven concluída com **BUILD SUCCESS** em **49,686 s**. O log reporta:

| Tipo | Testes | Falhas | Erros | Ignorados |
|---|---:|---:|---:|---:|
| Testes unitários (Surefire) | 24 | 0 | 0 | 0 |
| Testes de integração (Failsafe) | 17 | 0 | 0 | 0 |
| **Total** | **41** | **0** | **0** | **0** |

Os testes de integração iniciaram PostgreSQL 16.16 via Testcontainers e aplicaram com sucesso as cinco migrações Flyway (v1 a v5). O tempo total e os resultados acima são da execução de testes/build, não uma medição de desempenho da API.
