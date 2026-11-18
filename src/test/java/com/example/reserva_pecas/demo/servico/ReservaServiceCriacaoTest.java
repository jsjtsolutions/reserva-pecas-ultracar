package com.example.reserva_pecas.demo.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.reserva_pecas.demo.api.ApiException;
import com.example.reserva_pecas.demo.api.dto.CriarReservaRequest;
import com.example.reserva_pecas.demo.api.dto.ItemRequest;
import com.example.reserva_pecas.demo.config.ReservaProperties;
import com.example.reserva_pecas.demo.dominio.ItemSolicitado;
import com.example.reserva_pecas.demo.dominio.Peca;
import com.example.reserva_pecas.demo.infra.IdempotenciaRepository;
import com.example.reserva_pecas.demo.infra.PecaRepository;
import com.example.reserva_pecas.demo.infra.RegistroIdempotencia;
import com.example.reserva_pecas.demo.infra.ReservaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservaServiceCriacaoTest {

    private static final long OFICINA = 42;
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
        when(idempotencia.inserirOuBuscar(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        when(reservas.inserir(anyLong(), anyString(), any(), any(), any())).thenReturn(99L);
    }

    private void peca(long id, int estoque) {
        when(pecas.buscarParaAtualizacao(OFICINA, id))
                .thenReturn(Optional.of(new Peca(id, OFICINA, "P" + id, "peca " + id, estoque)));
    }

    private CriarReservaRequest req(ItemRequest... itens) {
        return new CriarReservaRequest("OS-1532", List.of(itens));
    }

    @Test
    @DisplayName("R1: falta saldo em um item -> 409 com itens faltantes e nada reservado")
    void r1_tudoOuNada() {
        peca(10, 5);
        peca(11, 0);
        when(reservas.reservadoAtivo(eq(OFICINA), any(), any())).thenReturn(Map.of(10L, 4)); // saldo 10 = 1

        var resposta = service.criar(OFICINA, 1001L, "chave-1", req(new ItemRequest(10L, 2), new ItemRequest(11L, 1)));

        assertThat(resposta.status()).isEqualTo(409);
        assertThat(resposta.contentType()).isEqualTo("application/problem+json");
        assertThat(resposta.body()).contains("SALDO_INSUFICIENTE")
                .contains("\"pecaId\":10").contains("\"disponivel\":1")
                .contains("\"pecaId\":11").contains("\"disponivel\":0");
        verify(reservas, never()).inserir(anyLong(), anyString(), any(), any(), any());
        verify(reservas, never()).inserirItens(anyLong(), any());
    }

    @Test
    @DisplayName("R1: saldo suficiente -> 201, expira em 30 minutos (R4)")
    void r1_caminhoFeliz() {
        peca(10, 5);
        when(reservas.reservadoAtivo(eq(OFICINA), any(), any())).thenReturn(Map.of());

        var resposta = service.criar(OFICINA, 1001L, "chave-2", req(new ItemRequest(10L, 5)));

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.body()).contains("\"status\":\"ATIVA\"")
                .contains("2026-11-18T12:30:00Z");
        verify(reservas).inserirItens(eq(99L), any());
    }

    @Test
    @DisplayName("R2/R6: peca de outra oficina (nao encontrada) -> 404 e nenhuma escrita")
    void r6_pecaAlheia() {
        when(pecas.buscarParaAtualizacao(OFICINA, 77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.criar(OFICINA, 1001L, "chave-3", req(new ItemRequest(77L, 1))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("PECA_NAO_ENCONTRADA");
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(404);
                });
        verify(reservas, never()).inserir(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("R3: mesma chave + mesmo corpo -> replay identico, sem nova reserva")
    void r3_replay() {
        peca(10, 5);
        when(reservas.reservadoAtivo(eq(OFICINA), any(), any())).thenReturn(Map.of());
        var primeira = service.criar(OFICINA, 1001L, "chave-4", req(new ItemRequest(10L, 1)));

        var itens = List.of(new ItemSolicitado(10L, 1));
        String hash = HashCorpo.de(OFICINA, "OS-1532", itens);
        when(idempotencia.inserirOuBuscar(OFICINA, "chave-4", hash))
                .thenReturn(Optional.of(new RegistroIdempotencia(hash, primeira.status(), primeira.contentType(), primeira.body())));

        var replay = service.criar(OFICINA, 1001L, "chave-4", req(new ItemRequest(10L, 1)));

        assertThat(replay.status()).isEqualTo(primeira.status());
        assertThat(replay.body()).isEqualTo(primeira.body());
        verify(reservas).inserir(anyLong(), anyString(), any(), any(), any()); // apenas a 1a vez
        verify(reservas, never()).inserirItens(eq(100L), any());
    }

    @Test
    @DisplayName("R3: mesma chave + corpo diferente -> 422 IDEMPOTENCY_KEY_REUTILIZADA")
    void r3_corpoDiferente() {
        when(idempotencia.inserirOuBuscar(eq(OFICINA), eq("chave-5"), anyString()))
                .thenReturn(Optional.of(new RegistroIdempotencia("hash-de-outro-corpo", 201, "application/json", "{}")));

        assertThatThrownBy(() -> service.criar(OFICINA, 1001L, "chave-5", req(new ItemRequest(10L, 3))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("IDEMPOTENCY_KEY_REUTILIZADA");
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(422);
                });
        verify(reservas, never()).inserir(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("R3: sem Idempotency-Key -> 400")
    void r3_semChave() {
        assertThatThrownBy(() -> service.criar(OFICINA, 1001L, "  ", req(new ItemRequest(10L, 1))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("IDEMPOTENCY_KEY_AUSENTE"));
    }

    @Test
    @DisplayName("R1: quantidade invalida -> 422")
    void quantidadeInvalida() {
        assertThatThrownBy(() -> service.criar(OFICINA, 1001L, "chave-6", req(new ItemRequest(10L, 0))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(422));
        verify(pecas, never()).buscarParaAtualizacao(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Itens com pecaId repetido sao consolidados (soma)")
    void consolidaItens() {
        peca(10, 5);
        when(reservas.reservadoAtivo(eq(OFICINA), any(), any())).thenReturn(Map.of());

        var resposta = service.criar(OFICINA, 1001L, "chave-7", req(new ItemRequest(10L, 2), new ItemRequest(10L, 4)));

        assertThat(resposta.status()).isEqualTo(409); // 2+4=6 > 5
        assertThat(resposta.body()).contains("\"solicitado\":6");
        verify(reservas, never()).inserirItens(anyLong(), any());
       // assertThat(anyInt()).isNotNull(); // mantem import utilitario usado
    }
}
