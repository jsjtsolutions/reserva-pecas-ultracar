# Contexto do projeto — Reserva de Peças (Ultracar)

## Stack
Java 17, Spring Boot 3.3, PostgreSQL 16, Flyway, **Spring JDBC (sem JPA)**, Testcontainers, k6.
Pacotes: `api` (controllers/dto/erro), `servico`, `infra` (repositórios), `dominio`, `config`.

## Regras invioláveis
1. **PROIBIDO lock em memória**: `synchronized`, `ReentrantLock`, `lock`, `SemaphoreSlim`,
   `AtomicReference` como mutex, cache local de saldo. A API roda em N instâncias → só lock no banco.
2. Lock pessimista é `SELECT … FOR UPDATE` **por peça, em ordem crescente de pecaId**, dentro da
   transação. Nunca um único `WHERE id IN (…) ORDER BY … FOR UPDATE` (a ordem de lock não é garantida).
3. Saldo é **derivado**: `estoque_fisico − Σ quantidade de reservas ATIVAS com expira_em > agora`.
   Nunca criar coluna de saldo sem atualizar este documento.
4. Toda query filtra `oficina_id`. Recurso de outra oficina → **404** (nunca 403).
5. Erros: RFC 7807 `application/problem+json` com propriedade `code` (ex.: `SALDO_INSUFICIENTE`).
6. Transições válidas: ATIVA→CONFIRMADA, ATIVA→CANCELADA, ATIVA→EXPIRADA. Resto → 409.
7. **409 é retorno, não exceção**, quando a operação precisa commitar algo (ex.: marcar EXPIRADA).
   Exceção dentro de `@Transactional` causa rollback e apagaria a transição.
8. Idempotência: `INSERT … ON CONFLICT DO NOTHING` na tabela `idempotencia` **na mesma transação**
   da reserva; hash SHA-256 do corpo canônico; resposta armazenada para replay byte-idêntico.
9. Tempo vem de `java.time.Clock` injetado (testes usam `Clock.fixed`). Nunca `Instant.now()` direto.
10. `@Transactional(readOnly = true)` em leituras. Sem N+1: saldo da página em 1 query.

## Ao gerar código
- Sem lombok. Records para DTOs e domínio. Construtor explícito (sem `@Autowired` em campo).
- Não inventar dependência nem endpoint fora do enunciado.
- Todo código novo precisa de teste (unitário para regra, integração para SQL/concorrência).
- Migração nova = arquivo `V<n>__descricao.sql` (não editar migração já aplicada).
- Explique o trade-off antes de sugerir índice novo.