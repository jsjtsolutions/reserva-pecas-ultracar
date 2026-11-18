package com.example.reserva_pecas.demo.api.dto;

public record PecaBuscaResponse(long id, String codigo, String descricao, int estoqueFisico, int saldoDisponivel) {
}
