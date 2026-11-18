package com.example.reserva_pecas.demo.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.example.reserva_pecas.demo.config.ReservaProperties;
import com.example.reserva_pecas.demo.infra.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


@ExtendWith(MockitoExtension.class)
class ExpiracaoServiceTest {

    private static final Instant AGORA = Instant.parse("2026-11-18T12:00:00Z");

    @Mock private ReservaRepository reservas;

    @Test
    @DisplayName("R4: job expira vencidas usando o relogio da aplicacao e o lote configurado")
    void expiraVencidas() {
        when(reservas.expirarLote(AGORA, 500)).thenReturn(3);
        var service = new ExpiracaoService(reservas, new ReservaProperties(Duration.ofMinutes(30), 500),
                Clock.fixed(AGORA, ZoneOffset.UTC));

        assertThat(service.expirarVencidas()).isEqualTo(3);
        verify(reservas).expirarLote(AGORA, 500);
    }
}