package com.example.reserva_pecas.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("R2 - Concorrencia com banco real")
class ConcorrenciaIT extends BaseIntegracao {

    @Test
    @DisplayName("20 reservas paralelas de 1 unidade com estoque 5: 5x201, 15x409, saldo 0")
    void vinteReservasParalelas() throws Exception {
        long pecaId = novaPeca(OFICINA, "P-DISPUTA", 5);
        int total = 20;

        ExecutorService threads = Executors.newFixedThreadPool(total);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> futuros = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            String os = "OS-" + i;
            String chave = UUID.randomUUID().toString();
            futuros.add(threads.submit(() -> {
                largada.await();   // todas disparam no mesmo instante
                return post("/api/v1/reservas", OFICINA, chave, corpo(os, pecaId, 1)).getStatusCode().value();
            }));
        }
        largada.countDown();
        threads.shutdown();
        assertThat(threads.awaitTermination(90, TimeUnit.SECONDS)).isTrue();

        int criadas = 0;
        int negadas = 0;
        for (Future<Integer> f : futuros) {
            int status = f.get();
            if (status == 201) {
                criadas++;
            } else if (status == 409) {
                negadas++;
            } else {
                fail("status inesperado na disputa: " + status);
            }
        }

        assertThat(criadas).as("reservas criadas").isEqualTo(5);
        assertThat(negadas).as("reservas negadas").isEqualTo(15);
        assertThat(saldoDe(OFICINA, pecaId)).as("saldo final").isZero();
        assertThat(estoqueFisico(pecaId)).as("reserva nao confirmada nao baixa estoque").isEqualTo(5);
        assertThat(contar("SELECT count(*) FROM reserva WHERE status = 'ATIVA'")).isEqualTo(5L);
        assertThat(contar("SELECT coalesce(sum(quantidade),0) FROM reserva_item")).isEqualTo(5L);
    }

    @Test
    @DisplayName("R3: 10 requisicoes paralelas com a MESMA chave criam 1 reserva e respostas identicas")
    void mesmaChaveEmParalelo() throws Exception {
        long pecaId = novaPeca(OFICINA, "P-IDEMP", 50);
        String chave = UUID.randomUUID().toString();
        int total = 10;

        ExecutorService threads = Executors.newFixedThreadPool(total);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String>> futuros = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            futuros.add(threads.submit(() -> {
                largada.await();
                var r = post("/api/v1/reservas", OFICINA, chave, corpo("OS-IGUAL", pecaId, 1));
                return r.getStatusCode().value() + "|" + r.getBody();
            }));
        }
        largada.countDown();
        threads.shutdown();
        assertThat(threads.awaitTermination(90, TimeUnit.SECONDS)).isTrue();

        List<String> respostas = new ArrayList<>();
        for (Future<String> f : futuros) {
            respostas.add(f.get());
        }

        assertThat(respostas).allSatisfy(r -> assertThat(r).startsWith("201|"));
        assertThat(respostas.stream().distinct().count()).as("corpo identico no replay").isEqualTo(1L);
        assertThat(contar("SELECT count(*) FROM reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("pedidos com itens em ordens opostas nao geram deadlock (lock ordenado por pecaId)")
    void semDeadlockComOrdensOpostas() throws Exception {
        long a = novaPeca(OFICINA, "P-A", 1);
        long b = novaPeca(OFICINA, "P-B", 1);
        int total = 20;

        ExecutorService threads = Executors.newFixedThreadPool(total);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> futuros = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            long primeiro = (i % 2 == 0) ? a : b;
            long segundo = (i % 2 == 0) ? b : a;
            String json = "{\"ordemServicoId\":\"OS-" + i + "\",\"itens\":[{\"pecaId\":" + primeiro
                    + ",\"quantidade\":1},{\"pecaId\":" + segundo + ",\"quantidade\":1}]}";
            String chave = UUID.randomUUID().toString();
            futuros.add(threads.submit(() -> {
                largada.await();
                return post("/api/v1/reservas", OFICINA, chave, json).getStatusCode().value();
            }));
        }
        largada.countDown();
        threads.shutdown();
        assertThat(threads.awaitTermination(90, TimeUnit.SECONDS)).isTrue();

        int criadas = 0;
        int negadas = 0;
        for (Future<Integer> f : futuros) {
            int status = f.get();
            if (status == 201) {
                criadas++;
            } else if (status == 409) {
                negadas++;
            } else {
                fail("deadlock ou erro interno na disputa: status " + status);
            }
        }

        assertThat(criadas).isEqualTo(1);
        assertThat(negadas).isEqualTo(19);
        assertThat(saldoDe(OFICINA, a)).isZero();
        assertThat(saldoDe(OFICINA, b)).isZero();
    }
}
