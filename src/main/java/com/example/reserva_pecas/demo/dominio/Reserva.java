package com.example.reserva_pecas.demo.dominio;

import java.time.Instant;

public record Reserva(long id, long oficinaId, String ordemServicoId, StatusReserva status,
                      Instant criadaEm, Instant expiraEm, Long criadaPor) {
}
