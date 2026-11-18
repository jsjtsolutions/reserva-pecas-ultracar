package com.example.reserva_pecas.demo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.example.reserva_pecas.demo.dominio.ItemReserva;
import com.example.reserva_pecas.demo.dominio.Peca;
import com.example.reserva_pecas.demo.dominio.Reserva;
import com.example.reserva_pecas.demo.dominio.StatusReserva;
import com.example.reserva_pecas.demo.infra.IdempotenciaRepository;
import com.example.reserva_pecas.demo.infra.PecaRepository;
import com.example.reserva_pecas.demo.infra.ReservaRepository;
import com.example.reserva_pecas.demo.servico.ReservaService;
import com.example.reserva_pecas.demo.servico.Respostas;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;


/**
 * Base dos testes unitarios: monta o servico com mocks e relogio fixo.
 * Mocks criados com Mockito.mock() (sem extension) = sem erro de stub nao usado.
 */
abstract class CenarioDeRegra {

    static final long OFICINA = 42L;
    static final long OUTRA_OFICINA = 43L;
    static final long RESERVA = 7L;
    static final long USUARIO = 1001L;
    static final Instant AGORA = Instant.parse("2026-11-18T12:00:00Z");
    static final Duration TTL = Duration.ofMinutes(30);

    PecaRepository pecas;
    ReservaRepository reservas;
    IdempotenciaRepository idempotencia;
    ReservaService service;

    @BeforeEach
    void montarCenario() {
        pecas = mock(PecaRepository.class);
        reservas = mock(ReservaRepository.class);
        idempotencia = mock(IdempotenciaRepository.class);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        service = new ReservaService(pecas, reservas, idempotencia, new Respostas(mapper),
                new ReservaProperties(TTL, 500), Clock.fixed(AGORA, ZoneOffset.UTC));

        // cenario "feliz" padrao; cada teste sobrescreve so o que interessa
        when(idempotencia.inserirOuBuscar(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        when(reservas.reservadoAtivo(anyLong(), any(), any())).thenReturn(Map.of());
        when(reservas.inserir(anyLong(), anyString(), any(), any(), any())).thenReturn(RESERVA);
        when(reservas.mudarStatus(anyLong(), any(), any(), any())).thenReturn(1);
        when(reservas.itens(RESERVA)).thenReturn(List.of(new ItemReserva(10L, "P10", 2)));
        when(pecas.buscarParaAtualizacao(OFICINA, 10L)).thenReturn(Optional.of(peca(10L, 10)));
        when(pecas.baixarEstoque(anyLong(), anyLong(), anyInt())).thenReturn(1);
    }

    Peca peca(long id, int estoque) {
        return new Peca(id, OFICINA, "P" + id, "peca " + id, estoque);
    }

    void dadaPeca(long id, int estoque) {
        when(pecas.buscarParaAtualizacao(OFICINA, id)).thenReturn(Optional.of(peca(id, estoque)));
    }

    void dadoReservado(Map<Long, Integer> porPeca) {
        when(reservas.reservadoAtivo(eq(OFICINA), any(), any())).thenReturn(porPeca);
    }

    void dadaReserva(StatusReserva status, Instant expiraEm) {
        when(reservas.buscarParaAtualizacao(OFICINA, RESERVA)).thenReturn(Optional.of(
                new Reserva(RESERVA, OFICINA, "OS-1", status, AGORA.minusSeconds(60), expiraEm, USUARIO)));
    }

    ItemRequest item(long pecaId, int quantidade) {
        return new ItemRequest(pecaId, quantidade);
    }

    CriarReservaRequest pedido(ItemRequest... itens) {
        return new CriarReservaRequest("OS-1", List.of(itens));
    }

    static ApiException erro(Runnable chamada) {
        try {
            chamada.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("esperava ApiException, mas nada foi lancado");
    }

    static int numero(String json, String caminho) {
        return ((Number) JsonPath.read(json, caminho)).intValue();
    }

    static String texto(String json, String caminho) {
        return JsonPath.read(json, caminho);
    }

    static List<?> lista(String json, String caminho) {
        return JsonPath.read(json, caminho);
    }
}