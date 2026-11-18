-- paginacao keyset e isolamento por oficina
CREATE INDEX idx_peca_oficina_id   ON peca   (oficina_id, id);
CREATE INDEX idx_reserva_oficina_id ON reserva (oficina_id, id);
-- join do saldo
CREATE INDEX idx_reserva_item_peca ON reserva_item (peca_id);
-- reserva_item PK (reserva_id, peca_id) ja cobre o acesso por reserva_id

ANALYZE peca;
ANALYZE reserva;
ANALYZE reserva_item;