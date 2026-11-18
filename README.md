# Reserva de Peças por Ordem de Serviço

**Desafio Técnico Sênior · Oficina Brasil · BU Ultracar**

Java 17 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · Spring JDBC · Testcontainers · k6 · Docker Compose

API que reserva peças do estoque para uma Ordem de Serviço. Várias pessoas da mesma oficina
reservam ao mesmo tempo e a API roda com mais de uma instância: quando duas reservas disputam a
última unidade, **só uma ganha** — e o saldo nunca fica negativo.

---

## Sumário

1. [Rodar com um comando](#1-rodar-com-um-comando)
2. [Testes](#2-testes)
3. [Endpoints e exemplos](#3-endpoints-e-exemplos)
4. [Regras de negócio: onde estão e como são provadas](#4-regras-de-negócio-onde-estão-e-como-são-provadas)
5. [Desempenho (D1, D2, D3)](#5-desempenho-d1-d2-d3)
6. [Prova de concorrência com várias instâncias](#6-prova-de-concorrência-com-várias-instâncias)
7. [Decisões e trade-offs](#7-decisões-e-trade-offs)
8. [O que eu melhoraria com mais tempo](#8-o-que-eu-melhoraria-com-mais-tempo)
9. [Estrutura, configuração e entrega](#9-estrutura-configuração-e-entrega)

---

## 1. Rodar com um comando

```bash
./scripts/up.sh          # ou: make up
```

Esse comando:

1. `docker compose up --build -d` → sobe **API + PostgreSQL**;
2. espera o `/actuator/health` responder `UP`;
3. carrega o seed **D1** (200 mil peças / 50 oficinas / 2 milhões de itens) na primeira vez.

| Serviço | Endereço |
|---|---|
| API | http://localhost:8080 |
| Health | http://localhost:8080/actuator/health |
| PostgreSQL | `localhost:5432` · db/user/senha: `reservas` |

Outros comandos:

```bash
docker compose up --build     # só API + banco, sem seed (sobe mais rápido)
make seed                     # carrega/recarrega o seed
make logs                     # logs da API
make psql                     # psql dentro do container do banco
make down                     # derruba (mantém os dados)
make reset                    # derruba e apaga o volume (banco limpo)
```

**Pré-requisitos:** Docker + Compose v2. Para build/testes locais: JDK 17 e Maven 3.9.
No Windows, rode os scripts pelo Git Bash ou WSL (ou use `docker compose up --build` direto).

---

## 2. Testes

```bash
mvn test        # testes unitários — NÃO precisa de Docker
mvn verify      # unitários + integração com PostgreSQL real (precisa de Docker)
```

Rodar um arquivo específico:

```bash
mvn -Dtest=RegraR1TudoOuNadaTest test
mvn -Dit.test=ConcorrenciaIT verify
```

### Unitários (regras de negócio, com relógio fixo e mocks)

| Arquivo | O que prova |
|---|---|
| `RegraR1TudoOuNadaTest` | Um item sem saldo → nada reservado; o 409 lista **todos** os itens que faltaram com o saldo de cada um; saldo = físico − reservas ativas |
| `RegraR3IdempotenciaTest` | Mesma chave + mesmo corpo → resposta idêntica sem nova reserva (inclusive no replay do 409); corpo diferente → 422; sem chave → 400 |
| `RegraR4ExpiracaoTest` | `expiraEm = criadaEm + 30min`; confirmar/cancelar reserva expirada → 409 e marca `EXPIRADA`; no instante exato já está expirada; job usa relógio + lote |
| `RegraR5TransicoesTest` | `ATIVA→CONFIRMADA` dá baixa; `ATIVA→CANCELADA` não mexe no físico; confirmar/cancelar a partir de `CONFIRMADA`, `CANCELADA` e `EXPIRADA` → 409 (teste parametrizado) |

`CenarioDeRegra` é a base compartilhada (monta o serviço com `Clock.fixed`). Não é um teste.

### Integração (PostgreSQL real via Testcontainers, HTTP na porta aleatória)

| Arquivo | O que prova |
|---|---|
| **`ConcorrenciaIT`** | **O teste pedido no enunciado:** 20 reservas paralelas de 1 unidade numa peça com estoque 5 → **exatamente 5×201, 15×409 e saldo final 0**. Também: 10 requisições paralelas com a mesma chave → 1 reserva e corpos idênticos; pedidos com itens em ordens opostas → sem deadlock |
| `RegrasDeNegocioIT` | R1, R3, R4, R5, R6 e R7 pela API HTTP, conferindo o banco depois de cada chamada |
| `BuscaPecasIT` | 20 por página, cursor até a última página sem repetir itens, busca por trecho de código e de descrição, saldo na listagem, isolamento por oficina, cursor inválido → 422 |

O teste eliminatório dispara as 20 threads com um `CountDownLatch` (todas no mesmo instante) e
usa pool de 30 conexões, para que a disputa aconteça **no banco**, não na fila do pool.

---

## 3. Endpoints e exemplos

Headers: `X-Oficina-Id` em todas as rotas; `X-User-Id` nos POSTs; `Idempotency-Key` obrigatória
na criação. Autenticação real está fora de escopo — os headers são a identidade.

| Método | Rota | Sucesso | Erros |
|---|---|---|---|
| POST | `/api/v1/reservas` | 201 | 409 `SALDO_INSUFICIENTE` · 422 `IDEMPOTENCY_KEY_REUTILIZADA` / `VALIDACAO_INVALIDA` · 404 · 400 |
| POST | `/api/v1/reservas/{id}/confirmacao` | 200 | 404 · 409 `TRANSICAO_INVALIDA` / `RESERVA_EXPIRADA` |
| POST | `/api/v1/reservas/{id}/cancelamento` | 200 | 404 · 409 |
| GET | `/api/v1/pecas/{id}/saldo` | 200 | 404 |
| GET | `/api/v1/pecas?busca=&cursor=` | 200 (20 por página, com saldo) | 422 `CURSOR_INVALIDO` |
| GET | `/api/v1/reservas/{id}` | 200 | 404 |

> A última rota é um extra de conveniência (não estava no enunciado): serve para ver o status
> `EXPIRADA` materializado nas demonstrações de R4/R5.

No seed, a oficina de uma peça é `oficinaId = ((id - 1) % 50) + 1`. Ou seja, as peças **42** e **92**
pertencem à oficina **42** — use-as nos exemplos abaixo.

```bash
# busca por trecho de código ou descrição (20 por página, com saldo)
curl -s -H 'X-Oficina-Id: 42' 'http://localhost:8080/api/v1/pecas?busca=Pastilha'

# próxima página: use o proximoCursor devolvido
curl -s -H 'X-Oficina-Id: 42' 'http://localhost:8080/api/v1/pecas?cursor=<proximoCursor>'

# saldo disponível de uma peça
curl -s -H 'X-Oficina-Id: 42' http://localhost:8080/api/v1/pecas/42/saldo

# criar reserva
curl -i -X POST http://localhost:8080/api/v1/reservas \
  -H 'Content-Type: application/json' \
  -H 'X-Oficina-Id: 42' -H 'X-User-Id: 1001' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"ordemServicoId":"OS-1532","itens":[{"pecaId":42,"quantidade":2},{"pecaId":92,"quantidade":1}]}'

# R3 na prática: repita o comando ACIMA com a mesma chave e veja a MESMA resposta (201, mesmo id)
# e mude só a quantidade mantendo a chave -> 422

# confirmar (dá baixa no estoque físico) / cancelar (libera o saldo)
curl -i -X POST http://localhost:8080/api/v1/reservas/1/confirmacao \
  -H 'X-Oficina-Id: 42' -H 'X-User-Id: 1001'
curl -i -X POST http://localhost:8080/api/v1/reservas/1/cancelamento \
  -H 'X-Oficina-Id: 42' -H 'X-User-Id: 1001'

# R6: a mesma peça vista por outra oficina -> 404
curl -i -H 'X-Oficina-Id: 43' http://localhost:8080/api/v1/pecas/42/saldo
```

### Formato de erro (R7 · RFC 7807)

`Content-Type: application/problem+json`, sempre com um `code` de negócio:

```json
{
  "type": "https://ultracar.com.br/erros/saldo-insuficiente",
  "title": "Saldo insuficiente",
  "status": 409,
  "detail": "Sem saldo para 1 item(ns); nada foi reservado.",
  "code": "SALDO_INSUFICIENTE",
  "itensFaltantes": [
    { "pecaId": 92, "codigo": "P0000092", "solicitado": 5, "disponivel": 2 }
  ]
}
```

| Código | HTTP | Quando |
|---|---|---|
| `SALDO_INSUFICIENTE` | 409 | falta saldo para um ou mais itens (lista todos, com o saldo de cada) |
| `TRANSICAO_INVALIDA` | 409 | a reserva não está `ATIVA` |
| `RESERVA_EXPIRADA` | 409 | passou de `expiraEm`; a reserva é marcada `EXPIRADA` |
| `ESTOQUE_FISICO_INSUFICIENTE` | 409 | defesa final na baixa; toda a operação é revertida |
| `IDEMPOTENCY_KEY_REUTILIZADA` | 422 | mesma chave, corpo diferente |
| `VALIDACAO_INVALIDA` / `CORPO_INVALIDO` / `CURSOR_INVALIDO` | 422 | corpo/cursor inválido |
| `PECA_NAO_ENCONTRADA` / `RESERVA_NAO_ENCONTRADA` | 404 | inexistente **ou de outra oficina** |
| `IDEMPOTENCY_KEY_AUSENTE` / `HEADER_OBRIGATORIO_AUSENTE` | 400 | header obrigatório ausente |

---

## 4. Regras de negócio: onde estão e como são provadas

| Regra | Implementação | Prova |
|---|---|---|
| **R1** Tudo ou nada | `ReservaService.criar` calcula o saldo de **todos** os itens sob lock e só escreve se todos couberem; caso contrário devolve 409 com `itensFaltantes` | `RegraR1TudoOuNadaTest`, `RegrasDeNegocioIT.r1SemSaldo` |
| **R2** Concorrência | `PecaRepository.buscarParaAtualizacao` (`SELECT … FOR UPDATE`) chamado **em loop, em ordem crescente de `pecaId`**, dentro da transação. Nenhum lock em memória | `ConcorrenciaIT` (5×201 / 15×409 / saldo 0), `make concorrencia`, `make cluster-test` |
| **R3** Idempotência | `IdempotenciaRepository.inserirOuBuscar` (`INSERT … ON CONFLICT DO NOTHING` na **mesma transação** da reserva) + hash SHA-256 do corpo canônico + resposta armazenada para replay | `RegraR3IdempotenciaTest`, `RegrasDeNegocioIT.r3Idempotencia`, `ConcorrenciaIT.mesmaChaveEmParalelo` |
| **R4** Expiração | `expiraEm = criadaEm + 30min`; todo cálculo de saldo filtra `expira_em > :agora` (liberação imediata) + `ExpiracaoService` materializa o status em lote com `FOR UPDATE SKIP LOCKED` | `RegraR4ExpiracaoTest`, `RegrasDeNegocioIT.r4Expiracao` e `r4Job` |
| **R5** Transições | `confirmar`/`cancelar` exigem `ATIVA`; expirada → 409 + `EXPIRADA`; qualquer outro status → 409 `TRANSICAO_INVALIDA` | `RegraR5TransicoesTest` (parametrizado nos 3 status), `RegrasDeNegocioIT.r5Transicoes` |
| **R6** Isolamento | **todo** SQL filtra `oficina_id`; recurso inexistente ou alheio → 404 (nunca 403, para não vazar existência) | `RegrasDeNegocioIT.r6Isolamento`, `BuscaPecasIT.isolamentoNaBusca` |
| **R7** Erros | `Erros` + `ApiExceptionHandler` (`ProblemDetail` do Spring, `application/problem+json`, `code` + `type`) | `RegrasDeNegocioIT.r7FormatoDoErro` |

Não há `synchronized`, `ReentrantLock`, `lock` ou equivalente em lugar nenhum do código — pode
conferir:

```bash
grep -rn "synchronized\|ReentrantLock\|SemaphoreSlim" src/main/java || echo "OK: nenhum lock em memoria"
```

---

## 5. Desempenho (D1, D2, D3)

**Todos os números medidos, o cenário completo e os planos de execução estão em
[DESEMPENHO.md](DESEMPENHO.md).** Aqui fica só o que cada coisa é e como reproduzir.

### D1 · Carga

`scripts/seed/seed.sql` gera, em SQL puro (`generate_series`, rápido):

- **200.000 peças** em **50 oficinas** (4.000 por oficina);
- **400.000 reservas históricas** (`CONFIRMADA`/`CANCELADA`/`EXPIRADA`) com **2.000.000 de itens**;
- **1.000 reservas ATIVAS** de 2 itens, para o cálculo de saldo não ser trivialmente vazio.

O script termina conferindo as contagens e rodando uma query de sanidade:
`pecas_com_saldo_negativo` tem que ser **0**.

### D2 · Carga de leitura com 20 usuários simultâneos

```bash
make carga                       # k6: 20 VUs, 60s, GET /api/v1/pecas?busca=
# ou: k6 run -e DURACAO=60s carga/busca-pecas.js
```

O script tem threshold **`p(95) < 150 ms`** — se não bater, o k6 sai com código de erro.

### D3 · Antes e depois de cada otimização

```bash
make reset && make up            # banco limpo + seed
make medir                       # roda as 6 etapas e salva JSON/log em carga/resultados/
make explain                     # EXPLAIN (ANALYZE, BUFFERS) da consulta principal
```

`scripts/medir.sh` usa `SPRING_FLYWAY_TARGET` para **limitar as migrações aplicadas**, medindo o
estado intermediário do banco sem apagar código:

| Etapa | Estado medido |
|---|---|
| 1 | baseline: só índices de FK/oficina (`target=2`) |
| 2 | + `pg_trgm` GIN em `codigo` e `descricao` (V3) |
| 3 | + partial index de reservas `ATIVA` e covering de `reserva_item` (V4) |
| 4 | + covering index da paginação keyset (V5) |
| 5 | regressão proposital: saldo em **N+1** (`BUSCA_SALDO_MODO=N_PLUS_1`) com todos os índices |
| 6 | final: saldo em **lote** + pool de 30 conexões |

Cada etapa faz 10 s de warmup descartado e 45 s medidos. Os planos `EXPLAIN ANALYZE` antes/depois
estão colados no `DESEMPENHO.md`.

---

## 6. Prova de concorrência com várias instâncias

R2 exige que o saldo nunca fique negativo **com várias instâncias da API**. Além do teste de
integração, o repositório sobe um cluster de verdade:

```bash
make cluster-up      # 3 instâncias da API + PostgreSQL + nginx balanceando na porta 8080
make cluster-test    # 20 reservas paralelas de 1 unidade contra estoque 5
make cluster-down
```

O script `scripts/teste-concorrencia-cluster.sh`:

1. cria uma peça com `estoque_fisico = 5`;
2. dispara 20 `curl` em paralelo, cada um com sua `Idempotency-Key`;
3. asserts **5×201, 15×409** e **saldo final 0**;
4. imprime quantas instâncias atenderam, pelo header de resposta **`X-Instancia`** — a prova de que
   a disputa passou por processos diferentes e ainda assim o saldo fechou em zero.

Para a versão com uma instância só (k6, com thresholds `reservas_201 == 5` e `reservas_409 == 15`):

```bash
make concorrencia
```

---

## 7. Decisões e trade-offs

### 7.1 Lock pessimista no banco, por peça, em ordem crescente (R2)

`SELECT … FOR UPDATE` na linha da `peca`, dentro da transação READ COMMITTED, **uma peça por vez,
sempre em ordem crescente de `pecaId`**.

- **Por que lock no banco:** é o único ponto comum a todas as instâncias. Lock em memória não
  resolve nada com N processos (e é eliminatório no enunciado).
- **Por que um loop e não um `WHERE id IN (…) ORDER BY id FOR UPDATE`:** o `ORDER BY` **não garante a
  ordem de aquisição dos locks** — o planner pode escolher Bitmap Heap Scan e travar as linhas em
  ordem física. Duas reservas com os mesmos itens em ordens diferentes poderiam se deadlockar.
  O loop ordenado torna a ordem determinística. `ConcorrenciaIT.semDeadlockComOrdensOpostas`
  existe exatamente para provar isso.
- **Por que READ COMMITTED e não SERIALIZABLE:** `SERIALIZABLE` exigiria tratar `40001` com retry em
  toda a API, e o retry reabre a discussão de idempotência. Com lock pessimista a corretude é local
  e óbvia.
- **Custo:** N round-trips por reserva (N = itens, tipicamente 1–5) e serialização **por peça**.
  É aceitável: a contenção que importa é justamente por peça, e a leitura (D2) não é afetada.
- **Alternativa descartada:** advisory locks (`pg_advisory_xact_lock`) funcionariam, mas o lock fica
  longe do dado e não protege contra um writer que esqueça de pedir o lock.

### 7.2 Saldo disponível é derivado, nunca armazenado

`saldo = estoque_fisico − Σ quantidade das reservas ATIVAS e não expiradas`. Fonte única de
verdade: não existe coluna redundante para dessincronizar.

O invariante `saldo ≥ 0` se sustenta em todas as transições:

| Operação | efeito | saldo |
|---|---|---|
| criar (sob lock) | só grava se `quantidade ≤ saldo` | continua ≥ 0 |
| confirmar | `físico −= q` **e** `reservado −= q` | inalterado |
| cancelar / expirar | `reservado −= q` | aumenta |

Defesa final no banco: `CHECK (estoque_fisico >= 0)` e
`UPDATE … WHERE estoque_fisico >= :qtd` (se atualizar 0 linhas, a transação inteira é revertida).

**Custo:** cada cálculo de saldo é um aggregate. Resolvido com **partial index** em
`reserva … WHERE status = 'ATIVA'` + covering em `reserva_item` — no meio de 2 milhões de itens
históricos, só as ~mil reservas ativas entram na conta.

### 7.3 Idempotência transacional, com a resposta armazenada (R3)

Tabela `idempotencia` com PK `(oficina_id, chave)`. O `INSERT … ON CONFLICT DO NOTHING` roda
**na mesma transação** da reserva: uma requisição concorrente com a mesma chave **bloqueia no
índice único** até a dona commitar; depois lê o registro e faz replay.

- A resposta (status, content-type e corpo) é guardada, então o replay é **byte-idêntico**,
  incluindo `id` e timestamps.
- O **409 de saldo também é memorizado**: repetir a mesma chave devolve a mesma resposta, como pede
  R3, mesmo que o estoque tenha mudado entre as chamadas.
- O hash é do corpo **canônico** (itens consolidados e ordenados por `pecaId`). Reordenar o array de
  itens não gera 422 — decisão consciente: o que identifica a intenção é o conteúdo semântico.
- **Custo/trade-off:** a tabela cresce sem limite (falta job de expurgo) e uma chave fica "presa" ao
  primeiro resultado, inclusive ao 409. É o comportamento especificado, mas vale documentar para
  quem consome a API.

### 7.4 O 409 é valor de retorno, não exceção

`criar`, `confirmar` e `cancelar` devolvem `RespostaHttp`. O motivo é concreto: confirmar uma reserva
expirada precisa **commitar** a transição `ATIVA → EXPIRADA` e responder 409. Se eu lançasse exceção
dentro de `@Transactional`, o rollback apagaria a transição.

Descartei a alternativa de marcar `EXPIRADA` numa transação `REQUIRES_NEW`: a transação externa já
tem a linha travada com `FOR UPDATE`, então a transação interna esperaria um lock que nunca é
liberado — **auto-deadlock**. Erros que não escrevem nada (404, 422, transição inválida) continuam
sendo exceção, tratadas pelo `@RestControllerAdvice`.

### 7.5 Expiração preguiçosa + job em lote (R4)

O saldo é liberado **no instante** em que `expira_em` passa, porque toda query de saldo filtra
`expira_em > :agora`. O `ExpiracaoService` apenas materializa o status, em lote de 500, com
`FOR UPDATE SKIP LOCKED` — roda em todas as instâncias sem coordenação extra e sem ShedLock.

**Trade-off:** o campo `status` pode continuar `ATIVA` por até 30 s depois de expirar. Isso não afeta
saldo nem transições (ambos checam `expira_em`), só a leitura direta do status. Em troca, nenhuma
operação de negócio depende do job estar vivo.

### 7.6 Cancelar e expirar não travam as peças

Sair de `ATIVA` já libera o saldo derivado; não há escrita em `peca`. Isso mantém a ordem de locks
simples (só `criar` e `confirmar` travam peças, sempre em ordem crescente).

Uma leitura concorrente pode ver o saldo **defasado para menos** (ainda contando uma reserva que
acabou de ser cancelada). Esse erro é **conservador**: pode negar uma reserva que caberia, nunca
criar saldo negativo. `confirmar`, que reduz o físico, trava as peças — então não há janela perigosa.

### 7.7 Relógio da aplicação injetado (`Clock`)

`Clock.systemUTC()` em produção, `Clock.fixed()` nos testes — R4 e R5 ficam determinísticos, sem
`sleep` nem flakiness. **Trade-off:** depende de NTP entre as instâncias; a alternativa é usar
`now()` do banco em todos os filtros, ao custo de um round-trip ou de SQL menos testável.

### 7.8 Spring JDBC em vez de JPA

Preciso de `FOR UPDATE`, `SKIP LOCKED`, partial indexes e SQL estável para o `EXPLAIN ANALYZE`.
Com `NamedParameterJdbcTemplate` o SQL que eu escrevo é o SQL que roda — sem lazy loading, dirty
checking ou surpresa de flush no caminho crítico. **Custo:** mappers manuais e mais linhas de
repositório.

### 7.9 Paginação por keyset, não por OFFSET

`WHERE oficina_id = ? AND id > :cursor ORDER BY id LIMIT 21`. Custo constante em qualquer página
(`OFFSET 100000` varreria 100 mil linhas) e sem `COUNT(*)` para saber se há próxima página — busco
21 e devolvo 20. O cursor é opaco (base64url de `v1:<id>`); inválido → 422, não 500.

### 7.10 SQL da busca montado em runtime

Escrever `(:busca IS NULL OR codigo ILIKE …)` faria o Postgres cair em generic plan e ignorar os
índices GIN. Monto duas variantes (com e sem termo) e escapo `%` e `_` do usuário, para um curinga
acidental não destruir a seletividade. A etapa 5 do `DESEMPENHO.md` mostra o custo do N+1 no
cálculo do saldo, que foi substituído por **uma query em lote por página**.

### 7.11 Isolamento por oficina com 404 (R6)

Todo acesso filtra `oficina_id`. Recurso de outra oficina devolve **404**, não 403: 403 confirmaria
que o id existe em outra oficina. Os headers são a identidade (autenticação real está fora de
escopo), mas o filtro é aplicado no SQL, não na camada de apresentação — não dá para esquecer.

---

## 8. O que eu melhoraria com mais tempo

**Corretude e operação**
- Coluna `saldo_disponivel` com `CHECK >= 0` e `UPDATE … WHERE saldo >= qtd`: 1 round-trip por item,
  sem lock explícito, mais throughput de escrita — ao custo de redundância. Faria com reconciliação
  periódica contra o saldo derivado e métrica de divergência.
- `CREATE INDEX CONCURRENTLY` em migração não transacional: as migrações V3–V5 travam escrita
  durante a criação do índice, o que é inaceitável em produção com tráfego.
- Expurgo da tabela `idempotencia` (TTL de 24 h) e particionamento de `reserva`/`reserva_item` por mês.
- `lock_timeout` + retry com backoff para evitar esperas longas numa peça muito disputada, e fila
  justo-por-peça se a contenção virar problema real.

**Segurança e contrato**
- JWT com claim de oficina, autorização por escopo e auditoria de quem confirmou/cancelou.
- OpenAPI/contract tests, versionamento de erro e `traceId` no `problem+json`.

**Observabilidade**
- Métricas Micrometer por regra: `reservas_criadas_total`, `reservas_negadas_total{code}`,
  `reserva_lock_wait_seconds`, `reservas_expiradas_total`; log estruturado com `oficinaId`/`reservaId`.
- Tracing distribuído para enxergar a espera por lock entre instâncias.

**Desempenho**
- Réplica de leitura para a busca; cache com ETag; índice GIN por oficina se a cardinalidade crescer.
- Teste de carga de **escrita** (não só leitura) com curva de contenção por peça.

**Testes**
- Property-based (jqwik) para o invariante "saldo nunca negativo" sob sequências aleatórias de
  criar/confirmar/cancelar/expirar.
- Teste de caos: matar uma instância no meio da disputa e verificar que o saldo fecha em zero.
- Subir o cluster de 3 instâncias no CI, não só localmente.

---

## 9. Estrutura, configuração e entrega

### Estrutura

```
src/main/java/br/com/ultracar/reservas_pecas/demo
├── api/            controllers, DTOs e erros RFC 7807
├── servico/        regras de negócio (ReservaService, PecaService, ExpiracaoService)
├── infra/          repositórios JDBC (FOR UPDATE, SKIP LOCKED, idempotência)
├── dominio/        Peca, Reserva, ItemReserva, StatusReserva
└── config/         Clock, properties, filtro de instância
src/main/resources/db/migration/
├── V1__esquema.sql            tabelas, checks e FKs
├── V2__indices_base.sql       índices de oficina/FK
├── V3__busca_trgm.sql         pg_trgm + GIN (busca por trecho)
├── V4__saldo_ativas.sql       partial index de ATIVAS + covering de itens
└── V5__cobertura_busca.sql    covering index da paginação keyset
src/test/java/.../servico/     unitários de R1, R3, R4, R5
src/test/java/.../it/          integração (Testcontainers): concorrência, regras, busca
carga/                         k6: busca (p95) e disputa de estoque
scripts/                       up, seed, medir, explain, concorrência, cluster
```

### Configuração

| Variável | Padrão | Uso |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/reservas` | conexão |
| `DB_USER` / `DB_PASSWORD` | `reservas` / `reservas` | credenciais |
| `DB_POOL` | `20` | tamanho do pool Hikari |
| `FLYWAY_TARGET` | `latest` | limita as migrações aplicadas (usado nas medições) |
| `BUSCA_SALDO_MODO` | `LOTE` | `N_PLUS_1` reproduz a regressão medida no `DESEMPENHO.md` |

TTL da reserva (30 min), lote e intervalo do job de expiração ficam em `application.yml`
(`reservas.ttl`, `reservas.lote-expiracao`, `reservas.expiracao.intervalo-ms`).

### Documentos da entrega

| Documento | Conteúdo |
|---|---|
| `README.md` | este arquivo: um comando, decisões, trade-offs, melhorias |
| [`DESEMPENHO.md`](DESEMPENHO.md) | ferramenta, cenário, antes/depois de cada otimização, `EXPLAIN ANALYZE` |
| [`IA.md`](IA.md) | ferramentas usadas, os 3 melhores prompts, erro da IA e como validei |
| `CLAUDE.md`, `.cursorrules`, `.github/copilot-instructions.md` | arquivos de contexto versionados |

Commits pequenos e frequentes, sem squash: `git log --oneline`.

---

**Autor:** Jeferson Guedes Dos
