package com.example.reserva_pecas.demo;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.reserva_pecas.demo.api.ApiException;
import com.example.reserva_pecas.demo.dominio.StatusReserva;
import com.example.reserva_pecas.demo.servico.RespostaHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("R5 - Transicoes de status")
class RegraR5TransicoesTest extends CenarioDeRegra {

    @Test
    @DisplayName("ATIVA -> CONFIRMADA da baixa no estoque fisico")
    void confirmaAtiva() {
        dadaReserva(StatusReserva.ATIVA, AGORA.plusSeconds(600));

        RespostaHttp r = service.confirmar(OFICINA, RESERVA);

        assertThat(r.status()).isEqualTo(200);
        assertThat(texto(r.body(), "$.status")).isEqualTo("CONFIRMADA");
        verify(pecas).baixarEstoque(OFICINA, 10L, 2);
        verify(reservas).mudarStatus(RESERVA, StatusReserva.ATIVA, StatusReserva.CONFIRMADA, AGORA);
    }

    @Test
    @DisplayName("ATIVA -> CANCELADA libera o saldo sem mexer no estoque fisico")
    void cancelaAtiva() {
        dadaReserva(StatusReserva.ATIVA, AGORA.plusSeconds(600));

        RespostaHttp r = service.cancelar(OFICINA, RESERVA);

        assertThat(r.status()).isEqualTo(200);
        assertThat(texto(r.body(), "$.status")).isEqualTo("CANCELADA");
        verify(reservas).mudarStatus(RESERVA, StatusReserva.ATIVA, StatusReserva.CANCELADA, AGORA);
        verify(pecas, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @ParameterizedTest(name = "confirmar reserva {0} = 409")
    @EnumSource(value = StatusReserva.class, names = {"CONFIRMADA", "CANCELADA", "EXPIRADA"})
    void confirmarForaDeAtiva(StatusReserva status) {
        dadaReserva(status, AGORA.plusSeconds(600));

        ApiException e = erro(() -> service.confirmar(OFICINA, RESERVA));

        assertThat(e.getStatus().value()).isEqualTo(409);
        assertThat(e.getCode()).isEqualTo("TRANSICAO_INVALIDA");
        verify(pecas, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @ParameterizedTest(name = "cancelar reserva {0} = 409")
    @EnumSource(value = StatusReserva.class, names = {"CONFIRMADA", "CANCELADA", "EXPIRADA"})
    void cancelarForaDeAtiva(StatusReserva status) {
        dadaReserva(status, AGORA.plusSeconds(600));

        ApiException e = erro(() -> service.cancelar(OFICINA, RESERVA));

        assertThat(e.getStatus().value()).isEqualTo(409);
        assertThat(e.getCode()).isEqualTo("TRANSICAO_INVALIDA");
    }

    @Test
    @DisplayName("baixa que falha lanca excecao para reverter a transacao inteira")
    void baixaQueFalhaReverte() {
        dadaReserva(StatusReserva.ATIVA, AGORA.plusSeconds(600));
        org.mockito.Mockito.when(pecas.baixarEstoque(OFICINA, 10L, 2)).thenReturn(0);

        ApiException e = erro(() -> service.confirmar(OFICINA, RESERVA));

        assertThat(e.getCode()).isEqualTo("ESTOQUE_FISICO_INSUFICIENTE");
        verify(reservas, never()).mudarStatus(RESERVA, StatusReserva.ATIVA, StatusReserva.CONFIRMADA, AGORA);
    }
}
