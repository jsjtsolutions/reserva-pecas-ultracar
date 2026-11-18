-- Cria/reset a peca do cenario de disputa: estoque fisico 5, sem reservas ativas.
DELETE FROM reserva_item WHERE peca_id IN (SELECT id FROM peca WHERE oficina_id = 42 AND codigo = 'P-DISPUTA-K6');
INSERT INTO peca (oficina_id, codigo, descricao, estoque_fisico)
VALUES (42, 'P-DISPUTA-K6', 'peca para teste de concorrencia', 5)
ON CONFLICT (oficina_id, codigo) DO UPDATE SET estoque_fisico = 5;
SELECT id FROM peca WHERE oficina_id = 42 AND codigo = 'P-DISPUTA-K6';