package com.example.reserva_pecas.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import com.example.reserva_pecas.demo.dominio.ItemSolicitado;
import com.example.reserva_pecas.demo.servico.RespostaHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R1 - Tudo ou nada")
class RegraR1TudoOuNadaTest extends CenarioDeRegra {

    @Test
    @DisplayName("se UM item nao tem saldo, nada é reservado e o 409 aponta o item")
    void negaAReservaInteiraQuandoUmItemFalta() {
        dadaPeca(10L, 5);   // saldo 5, pedido 2 -> ok
        dadaPeca(11L, 1);   // saldo 1, pedido 3 -> falta

        RespostaHttp r = service.criar(OFICINA, USUARIO, "chave-1", pedido(item(10L, 2), item(11L, 3)));

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.contentType()).isEqualTo("application/problem+json");
        assertThat(texto(r.body(), "$.code")).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(lista(r.body(), "$.itensFaltantes")).hasSize(1);
        assertThat(numero(r.body(), "$.itensFaltantes[0].pecaId")).isEqualTo(11);
        assertThat(numero(r.body(), "$.itensFaltantes[0].solicitado")).isEqualTo(3);
        assertThat(numero(r.body(), "$.itensFaltantes[0].disponivel")).isEqualTo(1);

        verify(reservas, never()).inserir(anyLong(), anyString(), any(), any(), any());
        verify(reservas, never()).inserirItens(anyLong(), any());
    }

    @Test
    @DisplayName("o 409 lista TODOS os itens que faltam, com o saldo de cada um")
    void listaTodosOsItensQueFaltam() {
        dadaPeca(10L, 5);
        dadaPeca(11L, 2);
        dadoReservado(Map.of(10L, 4, 11L, 2));   // saldo 10 = 1, saldo 11 = 0

        RespostaHttp r = service.criar(OFICINA, USUARIO, "chave-2", pedido(item(10L, 2), item(11L, 1)));

        assertThat(r.status()).isEqualTo(409);
        assertThat(lista(r.body(), "$.itensFaltantes")).hasSize(2);
        assertThat(numero(r.body(), "$.itensFaltantes[0].pecaId")).isEqualTo(10);
        assertThat(numero(r.body(), "$.itensFaltantes[0].disponivel")).isEqualTo(1);
        assertThat(numero(r.body(), "$.itensFaltantes[1].pecaId")).isEqualTo(11);
        assertThat(numero(r.body(), "$.itensFaltantes[1].disponivel")).isEqualTo(0);
        verify(reservas, never()).inserirItens(anyLong(), any());
    }

    @Test
    @DisplayName("com saldo para todos, reserva todos os itens")
    void reservaQuandoHaSaldoParaTodos() {
        dadaPeca(10L, 5);
        dadaPeca(11L, 3);

        RespostaHttp r = service.criar(OFICINA, USUARIO, "chave-3", pedido(item(10L, 5), item(11L, 3)));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.contentType()).isEqualTo("application/json");
        assertThat(texto(r.body(), "$.status")).isEqualTo("ATIVA");
        verify(reservas).inserirItens(eq(RESERVA),
                eq(List.of(new ItemSolicitado(10L, 5), new ItemSolicitado(11L, 3))));
    }

    @Test
    @DisplayName("saldo = estoque fisico - reservas ativas")
    void saldoDescontaReservasAtivas() {
        dadaPeca(10L, 10);
        dadoReservado(Map.of(10L, 7));   // saldo = 3

        RespostaHttp r = service.criar(OFICINA, USUARIO, "chave-4", pedido(item(10L, 4)));

        assertThat(r.status()).isEqualTo(409);
        assertThat(numero(r.body(), "$.itensFaltantes[0].disponivel")).isEqualTo(3);
    }
}