-- Otimizacao 1: ILIKE '%termo%' nao usa btree. GIN + pg_trgm resolve busca por trecho.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_peca_codigo_trgm    ON peca USING gin (codigo    gin_trgm_ops);
CREATE INDEX idx_peca_descricao_trgm ON peca USING gin (descricao gin_trgm_ops);
ANALYZE peca;