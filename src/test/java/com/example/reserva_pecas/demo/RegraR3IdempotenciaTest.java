package com.example.reserva_pecas.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.example.reserva_pecas.demo.api.ApiException;
import com.example.reserva_pecas.demo.infra.RegistroIdempotencia;
import com.example.reserva_pecas.demo.servico.RespostaHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("R3 - Idempotencia")
class RegraR3IdempotenciaTest extends CenarioDeRegra {

    private static final String CHAVE = "4f3a2e9c-1d2b-4e7a-9a0f-2b5c6d7e8f90";

    @Test
    @DisplayName("mesma chave + mesmo corpo = mesma resposta, sem segunda reserva")
    void mesmaChaveMesmoCorpo() {
        dadaPeca(10L, 5);
        RespostaHttp primeira = service.criar(OFICINA, USUARIO, CHAVE, pedido(item(10L, 2)));
        assertThat(primeira.status()).isEqualTo(201);

        // reaproveita o hash que o proprio servico calculou na primeira chamada
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(idempotencia).inserirOuBuscar(eq(OFICINA), eq(CHAVE), hash.capture());
        when(idempotencia.inserirOuBuscar(OFICINA, CHAVE, hash.getValue()))
                .thenReturn(Optional.of(new RegistroIdempotencia(hash.getValue(),
                        primeira.status(), primeira.contentType(), primeira.body())));

        RespostaHttp segunda = service.criar(OFICINA, USUARIO, CHAVE, pedido(item(10L, 2)));

        assertThat(segunda.status()).isEqualTo(primeira.status());
        assertThat(segunda.body()).isEqualTo(primeira.body());
        verify(reservas, times(1)).inserir(anyLong(), anyString(), any(), any(), any());
        verify(reservas, times(1)).inserirItens(anyLong(), any());
    }

    @Test
    @DisplayName("o replay do 409 tambem devolve a resposta original")
    void replayDoConflito() {
        dadaPeca(10L, 1);
        RespostaHttp primeira = service.criar(OFICINA, USUARIO, CHAVE, pedido(item(10L, 5)));
        assertThat(primeira.status()).isEqualTo(409);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(idempotencia).inserirOuBuscar(eq(OFICINA), eq(CHAVE), hash.capture());
        when(idempotencia.inserirOuBuscar(OFICINA, CHAVE, hash.getValue()))
                .thenReturn(Optional.of(new RegistroIdempotencia(hash.getValue(),
                        primeira.status(), primeira.contentType(), primeira.body())));

        RespostaHttp segunda = service.criar(OFICINA, USUARIO, CHAVE, pedido(item(10L, 5)));

        assertThat(segunda.status()).isEqualTo(409);
        assertThat(segunda.body()).isEqualTo(primeira.body());
        verify(reservas, never()).inserir(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("mesma chave + corpo diferente = 422")
    void mesmaChaveCorpoDiferente() {
        when(idempotencia.inserirOuBuscar(eq(OFICINA), eq(CHAVE), anyString()))
                .thenReturn(Optional.of(new RegistroIdempotencia(
                        "hash-de-outro-corpo", 201, "application/json", "{\"id\":1}")));

        ApiException e = erro(() -> service.criar(OFICINA, USUARIO, CHAVE, pedido(item(10L, 3))));

        assertThat(e.getStatus().value()).isEqualTo(422);
        assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUTILIZADA");
        verify(reservas, never()).inserir(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("sem Idempotency-Key = 400")
    void semChave() {
        ApiException e = erro(() -> service.criar(OFICINA, USUARIO, "   ", pedido(item(10L, 1))));

        assertThat(e.getStatus().value()).isEqualTo(400);
        assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_AUSENTE");
        verify(reservas, never()).inserir(anyLong(), anyString(), any(), any(), any());
    }
}
