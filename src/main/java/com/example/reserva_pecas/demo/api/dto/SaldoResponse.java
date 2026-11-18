package com.example.reserva_pecas.demo.api.dto;

public record SaldoResponse(long pecaId, String codigo, int estoqueFisico, int reservadoAtivo, int saldoDisponivel) {
}
