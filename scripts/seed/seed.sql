\timing on
-- D1: 200.000 pecas em 50 oficinas (4.000 por oficina) + 2.000.000 itens historicos
-- Snapshot coerente: reservas CONFIRMADA ja tiveram baixa aplicada no estoque_fisico.

TRUNCATE reserva_item, reserva, idempotencia, peca RESTART IDENTITY CASCADE;

SET LOCAL synchronous_commit = off;

INSERT INTO peca (oficina_id, codigo, descricao, estoque_fisico)
SELECT ((g - 1) % 50) + 1,
       'P' || lpad(g::text, 7, '0'),
       (ARRAY['Pastilha de freio','Filtro de oleo','Correia dentada','Amortecedor','Vela de ignicao',
              'Bomba de agua','Disco de freio','Rolamento','Sensor de rotacao','Junta do cabecote'])[1 + (g % 10)]
       || ' ' || (ARRAY['dianteiro','traseiro','esquerdo','direito','superior','inferior'])[1 + (g % 6)]
       || ' aplicacao ' || ((g % 900) + 100) || ' linha ' || ((g % 37) + 1),
       50 + (g % 150)
FROM generate_series(1, 200000) AS g;

-- 400.000 reservas historicas (5 itens cada = 2.000.000 itens)
INSERT INTO reserva (oficina_id, ordem_servico_id, status, criada_em, expira_em, criada_por, atualizada_em)
SELECT ((g - 1) % 50) + 1,
       'OS-' || (100000 + g),
       (ARRAY['CONFIRMADA','CANCELADA','EXPIRADA'])[1 + (g % 3)],
       now() - ((g % 540) + 1) * interval '1 day',
       now() - ((g % 540) + 1) * interval '1 day' + interval '30 minutes',
       1000 + (g % 20),
       now() - ((g % 540) + 1) * interval '1 day' + interval '10 minutes'
FROM generate_series(1, 400000) AS g;

-- peca_id pertence a MESMA oficina da reserva: id = oficina + 50*k
INSERT INTO reserva_item (reserva_id, peca_id, quantidade)
SELECT r.id,
       ((r.id - 1) % 50) + 1 + 50 * (((r.id * 7 + s * 131) % 4000)),
       1 + ((r.id + s) % 4)
FROM reserva r
CROSS JOIN generate_series(0, 4) AS s
WHERE r.status <> 'ATIVA';

-- 1.000 reservas ATIVAS (2 itens de 1 unidade) para o saldo nao ser trivialmente zero
INSERT INTO reserva (oficina_id, ordem_servico_id, status, criada_em, expira_em, criada_por, atualizada_em)
SELECT ((g - 1) % 50) + 1, 'OS-ATIVA-' || g, 'ATIVA',
       now() - interval '5 minutes', now() + interval '25 minutes', 1001, now()
FROM generate_series(1, 1000) AS g;

INSERT INTO reserva_item (reserva_id, peca_id, quantidade)
SELECT r.id,
       ((r.id - 1) % 50) + 1 + 50 * (((r.id * 13 + s * 977) % 4000)),
       1
FROM reserva r
CROSS JOIN generate_series(0, 1) AS s
WHERE r.status = 'ATIVA';

ANALYZE peca;
ANALYZE reserva;
ANALYZE reserva_item;
ANALYZE idempotencia;

SELECT 'pecas' AS tabela, count(*) FROM peca
UNION ALL SELECT 'oficinas', count(DISTINCT oficina_id) FROM peca
UNION ALL SELECT 'reservas', count(*) FROM reserva
UNION ALL SELECT 'reserva_item', count(*) FROM reserva_item
UNION ALL SELECT 'reservas_ativas', count(*) FROM reserva WHERE status = 'ATIVA';

-- sanity: nenhum saldo negativo
SELECT count(*) AS pecas_com_saldo_negativo FROM (
  SELECT p.id
  FROM peca p
  LEFT JOIN (SELECT ri.peca_id, sum(ri.quantidade) q
             FROM reserva_item ri JOIN reserva r ON r.id = ri.reserva_id
             WHERE r.status = 'ATIVA' AND r.expira_em > now()
             GROUP BY ri.peca_id) x ON x.peca_id = p.id
  WHERE p.estoque_fisico - coalesce(x.q, 0) < 0
) t;