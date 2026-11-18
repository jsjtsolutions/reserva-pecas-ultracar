-- Otimizacao 2: o saldo so depende de reservas ATIVAS (raras no volume historico).
-- Partial index + covering tornam o calculo do saldo praticamente index-only.
CREATE INDEX idx_reserva_ativa ON reserva (id) INCLUDE (oficina_id, expira_em) WHERE status = 'ATIVA';
CREATE INDEX idx_reserva_item_peca_reserva ON reserva_item (peca_id, reserva_id) INCLUDE (quantidade);
CREATE INDEX idx_reserva_expiracao_ativa ON reserva (expira_em) WHERE status = 'ATIVA';

DROP INDEX IF EXISTS idx_reserva_item_peca;  -- substituido pelo covering acima

ANALYZE reserva;
ANALYZE reserva_item;