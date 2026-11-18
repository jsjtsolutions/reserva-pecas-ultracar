package com.example.reserva_pecas.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.example.reserva_pecas.demo.config.ReservaProperties;
import com.example.reserva_pecas.demo.dominio.StatusReserva;
import com.example.reserva_pecas.demo.servico.ExpiracaoService;
import com.example.reserva_pecas.demo.servico.RespostaHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("R4 - Expiracao em 30 minutos")
class RegraR4ExpiracaoTest extends CenarioDeRegra {

    @Test
    @DisplayName("reserva nova nasce com expiraEm = criadaEm + 30 min")
    void ttlDeTrintaMinutos() {
        dadaPeca(10L, 5);

        service.criar(OFICINA, USUARIO, "chave-1", pedido(item(10L, 1)));

        ArgumentCaptor<Instant> criadaEm = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expiraEm = ArgumentCaptor.forClass(Instant.class);
        verify(reservas).inserir(eq(OFICINA), eq("OS-1"), eq(USUARIO), criadaEm.capture(), expiraEm.capture());

        assertThat(criadaEm.getValue()).isEqualTo(AGORA);
        assertThat(expiraEm.getValue()).isEqualTo(AGORA.plus(TTL));
    }

    @Test
    @DisplayName("confirmar reserva expirada = 409 e marca EXPIRADA, sem baixar estoque")
    void confirmarExpirada() {
        dadaReserva(StatusReserva.ATIVA, AGORA.minusSeconds(1));

        RespostaHttp r = service.confirmar(OFICINA, RESERVA);

        assertThat(r.status()).isEqualTo(409);
        assertThat(texto(r.body(), "$.code")).isEqualTo("RESERVA_EXPIRADA");
        verify(reservas).mudarStatus(RESERVA, StatusReserva.ATIVA, StatusReserva.EXPIRADA, AGORA);
        verify(pecas, never()).baixarEstoque(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("no instante exato da expiracao a reserva ja esta expirada")
    void expiracaoNoLimite() {
        dadaReserva(StatusReserva.ATIVA, AGORA);

        assertThat(service.confirmar(OFICINA, RESERVA).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("cancelar reserva expirada = 409 e marca EXPIRADA")
    void cancelarExpirada() {
        dadaReserva(StatusReserva.ATIVA, AGORA.minusSeconds(1));

        RespostaHttp r = service.cancelar(OFICINA, RESERVA);

        assertThat(r.status()).isEqualTo(409);
        assertThat(texto(r.body(), "$.code")).isEqualTo("RESERVA_EXPIRADA");
        verify(reservas).mudarStatus(RESERVA, StatusReserva.ATIVA, StatusReserva.EXPIRADA, AGORA);
    }

    @Test
    @DisplayName("reserva ainda valida confirma normalmente")
    void reservaValidaNaoExpira() {
        dadaReserva(StatusReserva.ATIVA, AGORA.plusSeconds(1));

        assertThat(service.confirmar(OFICINA, RESERVA).status()).isEqualTo(200);
        verify(reservas, never()).mudarStatus(RESERVA, StatusReserva.ATIVA, StatusReserva.EXPIRADA, AGORA);
    }

    @Test
    @DisplayName("job expira as vencidas usando o relogio e o lote configurado")
    void jobDeExpiracao() {
        when(reservas.expirarLote(AGORA, 500)).thenReturn(3);
        ExpiracaoService job = new ExpiracaoService(reservas, new ReservaProperties(TTL, 500),
                Clock.fixed(AGORA, ZoneOffset.UTC));

        assertThat(job.expirarVencidas()).isEqualTo(3);
        verify(reservas).expirarLote(AGORA, 500);
    }
}
