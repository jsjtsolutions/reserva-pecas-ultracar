package com.example.reserva_pecas.demo.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.example.reserva_pecas.demo.api.ApiException;
import com.example.reserva_pecas.demo.config.ReservaProperties;
import com.example.reserva_pecas.demo.dominio.ItemReserva;
import com.example.reserva_pecas.demo.dominio.Peca;
import com.example.reserva_pecas.demo.dominio.Reserva;
import com.example.reserva_pecas.demo.dominio.StatusReserva;
import com.example.reserva_pecas.demo.infra.IdempotenciaRepository;
import com.example.reserva_pecas.demo.infra.PecaRepository;
import com.example.reserva_pecas.demo.infra.ReservaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservaServiceTransicaoTest {

    private static final long OFICINA = 42;
    private static final long RESERVA = 7;
    private static final Instant AGORA = Instant.parse("2026-11-18T12:00:00Z");

    @Mock private PecaRepository pecas;
    @Mock private ReservaRepository reservas;
    @Mock private IdempotenciaRepository idempotencia;

    private ReservaService service;

    @BeforeEach
    void setup() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ReservaService(pecas, reservas, idempotencia, new Respostas(mapper),
                new ReservaProperties(Duration.ofMinutes(30), 500), Clock.fixed(AGORA, ZoneOffset.UTC));
        when(reservas.itens(RESERVA)).thenReturn(List.of(new ItemReserva(10L, "P10", 2)));
        when(pecas.buscarParaAtualizacao(OFICINA, 10L))
                .thenReturn(Optional.of(new Peca(10L, OFICINA, "P10", "peca 10", 10)));
        when(pecas.baixarEstoque(OFICINA, 10L, 2)).thenReturn(1);
        when(reservas.mudarStatus(anyLong(), any(), any(), any())).thenReturn(1);
    }

    private void reserva(StatusReserva status, Instant expiraEm) {
        when(reservas.buscarParaAtualizacao(OFICINA, RESERVA)).thenReturn(Optional.of(
                new Reserva(RESERVA, OFICINA, "OS-1", status, AGORA.minusSeconds(60), expiraEm, 1001L)));
    }

    @Test
    @DisplayName("R5: ATIVA -> CONFIRMADA da baixa no estoque fisico")
    void confirmaAtiva() {
        reserva(StatusReserva.ATIVA, AGORA.plusSeconds(600));

        var resposta = service.confirmar(OFICINA, RESERVA);

        assertThat(resposta.status()).isEqualTo(200);
        assertThat(resposta.body()).contains("\"status\":\"CONFIRMADA\"");
        verify(pecas).baixarEstoque(OFICINA, 10L, 2);
        verify(reservas).mudarStatus(RESERVA, StatusReserva.ATIVA, StatusReserva.CONFIRMADA, AGORA);
    }

    @Test
    @DisplayName("R5: ATIVA -> CANCELADA libera saldo sem tocar no estoque fisico")
    void cancelaAtiva() {
        reserva(StatusReserva.ATIVA, AGORA.plusSeconds(600));

        var resposta = service.cancelar(OFICINA, RESERVA);

        assertThat(resposta.status()).isEqualTo(200);
        assertThat(resposta.body()).contains("\"status\":\"CANCELADA\"");
        verify(pecas, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = StatusReserva.class, names = {"CONFIRMADA", "CANCELADA", "EXPIRADA"})
    @DisplayName("R5: confirmar reserva fora de ATIVA -> 409 TRANSICAO_INVALIDA")
    void confirmarInvalido(StatusReserva status) {
        reserva(status, AGORA.plusSeconds(600));

        assertThatThrownBy(() -> service.confirmar(OFICINA, RESERVA))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("TRANSICAO_INVALIDA");
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(409);
                });
        verify(pecas, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = StatusReserva.class, names = {"CONFIRMADA", "CANCELADA", "EXPIRADA"})
    @DisplayName("R5: cancelar reserva fora de ATIVA -> 409 TRANSICAO_INVALIDA")
    void cancelarInvalido(StatusReserva status) {
        reserva(status, AGORA.plusSeconds(600));

        assertThatThrownBy(() -> service.cancelar(OFICINA, RESERVA))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("TRANSICAO_INVALIDA"));
    }

    @Test
    @DisplayName("R4+R5: confirmar reserva expirada -> 409 RESERVA_EXPIRADA e marca EXPIRADA")
    void confirmarExpirada() {
        reserva(StatusReserva.ATIVA, AGORA.minusSeconds(1));

        var resposta = service.confirmar(OFICINA, RESERVA);

        assertThat(resposta.status()).isEqualTo(409);
        assertThat(resposta.body()).contains("RESERVA_EXPIRADA");
        verify(reservas).mudarStatus(RESERVA, StatusReserva.ATIVA, StatusReserva.EXPIRADA, AGORA);
        verify(pecas, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("R4: no limite exato de expiracao a reserva ja esta expirada")
    void expiracaoNoLimite() {
        reserva(StatusReserva.ATIVA, AGORA);

        assertThat(service.confirmar(OFICINA, RESERVA).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("R6: reserva de outra oficina -> 404")
    void reservaAlheia() {
        when(reservas.buscarParaAtualizacao(OFICINA, RESERVA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmar(OFICINA, RESERVA))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("Baixa que falha reverte tudo (rollback via excecao)")
    void baixaFalha() {
        reserva(StatusReserva.ATIVA, AGORA.plusSeconds(600));
        when(pecas.baixarEstoque(OFICINA, 10L, 2)).thenReturn(0);

        assertThatThrownBy(() -> service.confirmar(OFICINA, RESERVA))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ESTOQUE_FISICO_INSUFICIENTE"));
        verify(reservas, never()).mudarStatus(eq(RESERVA), any(), eq(StatusReserva.CONFIRMADA), any());
    }
}
