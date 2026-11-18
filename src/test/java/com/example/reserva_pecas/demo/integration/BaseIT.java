package com.example.reserva_pecas.demo.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIT {

    // singleton container: sobe no class-load, antes do contexto Spring
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("reservas").withUsername("reservas").withPassword("reservas");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // pool > 20 para que as 20 threads disputem de verdade no BANCO
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 30);
        // evita o job agendado interferir nos cenarios de expiracao
        r.add("reservas.expiracao.intervalo-ms", () -> 3_600_000);
    }

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;

    protected void limpar() {
        jdbc.execute("TRUNCATE reserva_item, reserva, idempotencia, peca RESTART IDENTITY CASCADE");
    }

    protected long inserirPeca(long oficinaId, String codigo, int estoque) {
        return jdbc.queryForObject("""
                INSERT INTO peca (oficina_id, codigo, descricao, estoque_fisico)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, oficinaId, codigo, "peca de teste " + codigo, estoque);
    }

    protected HttpHeaders headers(long oficinaId, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Oficina-Id", String.valueOf(oficinaId));
        h.set("X-User-Id", "1001");
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    protected ResponseEntity<String> post(String url, long oficinaId, String chave, String corpo) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(corpo, headers(oficinaId, chave)), String.class);
    }

    protected ResponseEntity<String> get(String url, long oficinaId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Oficina-Id", String.valueOf(oficinaId));
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    protected String corpoReserva(String os, long pecaId, int quantidade) {
        return "{\"ordemServicoId\":\"" + os + "\",\"itens\":[{\"pecaId\":" + pecaId + ",\"quantidade\":" + quantidade + "}]}";
    }
}
