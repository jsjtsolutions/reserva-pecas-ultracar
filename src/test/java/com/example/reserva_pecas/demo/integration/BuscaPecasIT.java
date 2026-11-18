package com.example.reserva_pecas.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuscaPecasIT extends BaseIT {

    private static final long OFICINA = 1;

    @BeforeEach
    void setup() {
        limpar();
        for (int i = 1; i <= 45; i++) {
            inserirPeca(OFICINA, String.format("P-%03d", i), 20);
        }
        for (int i = 1; i <= 5; i++) {
            inserirPeca(2, String.format("Q-%03d", i), 20);
        }
    }

    @Test
    @DisplayName("pagina com 20 itens e cursor keyset ate o fim")
    void paginacao() {
        var p1 = get("/api/v1/pecas", OFICINA);
        assertThat(p1.getStatusCode().value()).isEqualTo(200);
        List<?> itens1 = JsonPath.read(p1.getBody(), "$.itens");
        assertThat(itens1).hasSize(20);
        assertThat((Boolean) JsonPath.read(p1.getBody(), "$.temMais")).isTrue();
        String cursor = JsonPath.read(p1.getBody(), "$.proximoCursor");

        var p2 = get("/api/v1/pecas?cursor=" + cursor, OFICINA);
        assertThat((List<?>) JsonPath.read(p2.getBody(), "$.itens")).hasSize(20);
        String cursor2 = JsonPath.read(p2.getBody(), "$.proximoCursor");

        var p3 = get("/api/v1/pecas?cursor=" + cursor2, OFICINA);
        assertThat((List<?>) JsonPath.read(p3.getBody(), "$.itens")).hasSize(5);
        assertThat((Boolean) JsonPath.read(p3.getBody(), "$.temMais")).isFalse();
        assertThat((Object) JsonPath.read(p3.getBody(), "$.proximoCursor")).isNull();
    }

    @Test
    @DisplayName("busca por trecho de codigo/descricao e isolamento por oficina")
    void busca() {
        var r = get("/api/v1/pecas?busca=P-01", OFICINA);
        assertThat((List<?>) JsonPath.read(r.getBody(), "$.itens")).hasSize(10); // P-010..P-019
        assertThat(r.getBody()).doesNotContain("Q-001");
        assertThat((List<?>) JsonPath.read(get("/api/v1/pecas?busca=peca de teste Q-", OFICINA).getBody(), "$.itens")).isEmpty();
    }

    @Test
    @DisplayName("saldo na listagem desconta reservas ativas")
    void saldoNaListagem() {
        long pecaId = jdbc.queryForObject("SELECT id FROM peca WHERE oficina_id=? AND codigo='P-001'", Long.class, OFICINA);
        post("/api/v1/reservas", OFICINA, UUID.randomUUID().toString(), corpoReserva("OS-B", pecaId, 3));

        var r = get("/api/v1/pecas?busca=P-001", OFICINA);
        assertThat((Integer) JsonPath.read(r.getBody(), "$.itens[0].saldoDisponivel")).isEqualTo(17);
        assertThat((Integer) JsonPath.read(r.getBody(), "$.itens[0].estoqueFisico")).isEqualTo(20);
    }

    @Test
    @DisplayName("cursor invalido -> 422 CURSOR_INVALIDO")
    void cursorInvalido() {
        var r = get("/api/v1/pecas?cursor=lixo", OFICINA);
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody()).contains("CURSOR_INVALIDO");
    }
}
