\pset pager off
-- Consulta principal da busca (oficina 7, termo seletivo)
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT p.id, p.codigo, p.descricao, p.estoque_fisico
FROM peca p
WHERE p.oficina_id = 7
  AND (p.codigo ILIKE '%Pastilha%' OR p.descricao ILIKE '%Pastilha%')
ORDER BY p.id
LIMIT 21;

-- Consulta do saldo em lote (20 pecas da oficina 7)
EXPLAIN (ANALYZE, BUFFERS)
SELECT ri.peca_id, SUM(ri.quantidade) AS reservado
FROM reserva_item ri
JOIN reserva r ON r.id = ri.reserva_id
WHERE r.oficina_id = 7
  AND r.status = 'ATIVA'
  AND r.expira_em > now()
  AND ri.peca_id IN (7,57,107,157,207,257,307,357,407,457,507,557,607,657,707,757,807,857,907,957)
GROUP BY ri.peca_id;