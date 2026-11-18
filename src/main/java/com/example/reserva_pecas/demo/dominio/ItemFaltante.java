package com.example.reserva_pecas.demo.dominio;

/** R1: item sem saldo, com o saldo disponivel no momento da tentativa. */
public record ItemFaltante(long pecaId, String codigo, int solicitado, int disponivel) {
}
