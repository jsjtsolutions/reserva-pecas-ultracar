package com.example.reserva_pecas.demo.api.dto;

import java.time.Instant;
import java.util.List;

public record ReservaResponse(long id, String ordemServicoId, String status,
                              Instant criadaEm, Instant expiraEm, List<ItemResponse> itens) {
}
