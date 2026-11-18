package com.example.reserva_pecas.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Busca de pecas: 20 por pagina, cursor, saldo e isolamento")
class BuscaPecasIT extends BaseIntegracao {

    @BeforeEach
    void semearPecas() {
        for (int i = 1; i <= 45; i++) {
            novaPeca(OFICINA, String.format("P-%03d", i), 20);
        }
        for (int i = 1; i <= 5; i++) {
            novaPeca(OUTRA_OFICINA, String.format("Q-%03d", i), 20);
        }
    }

    private boolean temMais(String json) {
        return Boolean.TRUE.equals(com.jayway.jsonpath.JsonPath.read(json, "$.temMais"));
    }

    @Test
    @DisplayName("20 por pagina, cursor leva ate o fim sem repetir itens")
    void paginacao() {
        var p1 = get("/api/v1/pecas", OFICINA);
        assertThat(p1.getStatusCode().value()).isEqualTo(200);
        assertThat(lista(p1.getBody(), "$.itens")).hasSize(20);
        assertThat(temMais(p1.getBody())).isTrue();
        String cursor1 = texto(p1.getBody(), "$.proximoCursor");
        assertThat(cursor1).isNotBlank();

        var p2 = get("/api/v1/pecas?cursor=" + cursor1, OFICINA);
        assertThat(lista(p2.getBody(), "$.itens")).hasSize(20);
        assertThat(temMais(p2.getBody())).isTrue();
        String cursor2 = texto(p2.getBody(), "$.proximoCursor");

        var p3 = get("/api/v1/pecas?cursor=" + cursor2, OFICINA);
        assertThat(lista(p3.getBody(), "$.itens")).hasSize(5);
        assertThat(temMais(p3.getBody())).isFalse();

        int primeiroDaPagina2 = numero(p2.getBody(), "$.itens[0].id");
        int ultimoDaPagina1 = numero(p1.getBody(), "$.itens[19].id");
        assertThat(primeiroDaPagina2).isGreaterThan(ultimoDaPagina1);
    }

    @Test
    @DisplayName("busca por trecho do codigo e por trecho da descricao")
    void buscaPorTrecho() {
        var porCodigo = get("/api/v1/pecas?busca=P-01", OFICINA);
        assertThat(lista(porCodigo.getBody(), "$.itens")).hasSize(10);   // P-010 .. P-019

        var porDescricao = get("/api/v1/pecas?busca=teste P-00", OFICINA);
        assertThat(lista(porDescricao.getBody(), "$.itens")).hasSize(9);  // P-001 .. P-009
    }

    @Test
    @DisplayName("R6: a busca nao ve pecas de outra oficina")
    void isolamentoNaBusca() {
        assertThat(lista(get("/api/v1/pecas?busca=Q-", OFICINA).getBody(), "$.itens")).isEmpty();
        assertThat(lista(get("/api/v1/pecas", OUTRA_OFICINA).getBody(), "$.itens")).hasSize(5);
    }

    @Test
    @DisplayName("saldo da listagem desconta reservas ativas")
    void saldoNaListagem() {
        long peca = jdbc.queryForObject("SELECT id FROM peca WHERE oficina_id = ? AND codigo = 'P-001'",
                Long.class, OFICINA);
        criarReserva(OFICINA, "OS-BUSCA", peca, 3);

        var r = get("/api/v1/pecas?busca=P-001", OFICINA);
        assertThat(numero(r.getBody(), "$.itens[0].estoqueFisico")).isEqualTo(20);
        assertThat(numero(r.getBody(), "$.itens[0].saldoDisponivel")).isEqualTo(17);
    }

    @Test
    @DisplayName("cursor invalido devolve 422")
    void cursorInvalido() {
        var r = get("/api/v1/pecas?cursor=lixo", OFICINA);
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(texto(r.getBody(), "$.code")).isEqualTo("CURSOR_INVALIDO");
    }
}