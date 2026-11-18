-- Otimizacao 3: index-only scan na paginacao keyset (sem heap fetch das 20 linhas).
CREATE INDEX idx_peca_oficina_id_cobertura ON peca (oficina_id, id) INCLUDE (codigo, descricao, estoque_fisico);
DROP INDEX IF EXISTS idx_peca_oficina_id;
ANALYZE peca;