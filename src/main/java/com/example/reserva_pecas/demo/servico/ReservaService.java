package com.example.reserva_pecas.demo.servico;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.reserva_pecas.demo.api.ApiException;
import com.example.reserva_pecas.demo.api.dto.CriarReservaRequest;
import com.example.reserva_pecas.demo.api.dto.ItemRequest;
import com.example.reserva_pecas.demo.api.dto.ItemResponse;
import com.example.reserva_pecas.demo.api.dto.ReservaResponse;
import com.example.reserva_pecas.demo.api.erro.Erros;
import com.example.reserva_pecas.demo.config.ReservaProperties;
import com.example.reserva_pecas.demo.dominio.*;
import com.example.reserva_pecas.demo.infra.IdempotenciaRepository;
import com.example.reserva_pecas.demo.infra.PecaRepository;
import com.example.reserva_pecas.demo.infra.ReservaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservaService {

    private final PecaRepository pecas;
    private final ReservaRepository reservas;
    private final IdempotenciaRepository idempotencia;
    private final Respostas respostas;
    private final ReservaProperties props;
    private final Clock clock;

    public ReservaService(PecaRepository pecas, ReservaRepository reservas, IdempotenciaRepository idempotencia,
                          Respostas respostas, ReservaProperties props, Clock clock) {
        this.pecas = pecas;
        this.reservas = reservas;
        this.idempotencia = idempotencia;
        this.respostas = respostas;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public RespostaHttp criar(long oficinaId, Long userId, String chave, CriarReservaRequest req) {
        if (chave == null || chave.isBlank()) {
            throw Erros.chaveAusente();
        }
        if (chave.length() > 80) {
            throw Erros.validacao("Idempotency-Key deve ter no maximo 80 caracteres.", Map.of());
        }

        List<ItemSolicitado> itens = consolidar(req.itens());
        String hash = HashCorpo.de(oficinaId, req.ordemServicoId(), itens);

        // ---- R3 -------------------------------------------------------------
        var anterior = idempotencia.inserirOuBuscar(oficinaId, chave, hash);
        if (anterior.isPresent()) {
            var reg = anterior.get();
            if (!reg.hashCorpo().equals(hash)) {
                throw Erros.chaveReutilizada();               // 422
            }
            if (reg.concluida()) {
                return new RespostaHttp(reg.respostaStatus(), reg.respostaContentType(), reg.respostaBody());
            }
            // Defensivo: no desenho atual a linha so e visivel ja concluida (mesma transacao).
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "REQUISICAO_EM_ANDAMENTO",
                    "Requisicao em andamento", "A mesma Idempotency-Key esta sendo processada; repita em instantes.", Map.of());
        }

        Instant agora = clock.instant();
        List<Long> idsOrdenados = itens.stream().map(ItemSolicitado::pecaId).sorted().toList();

        // ---- R2 + R6: lock no banco, em ordem crescente; peca de outra oficina => 404
        Map<Long, Peca> travadas = new LinkedHashMap<>();
        List<Long> ausentes = new ArrayList<>();
        for (Long id : idsOrdenados) {
            pecas.buscarParaAtualizacao(oficinaId, id)
                    .ifPresentOrElse(p -> travadas.put(id, p), () -> ausentes.add(id));
        }
        if (!ausentes.isEmpty()) {
            throw Erros.pecaNaoEncontrada(ausentes);
        }

        // ---- R1: tudo ou nada ----------------------------------------------
        Map<Long, Integer> reservados = reservas.reservadoAtivo(oficinaId, idsOrdenados, agora);
        List<ItemFaltante> faltantes = new ArrayList<>();
        for (ItemSolicitado item : itens) {
            Peca p = travadas.get(item.pecaId());
            int disponivel = p.estoqueFisico() - reservados.getOrDefault(p.id(), 0);
            if (item.quantidade() > disponivel) {
                faltantes.add(new ItemFaltante(p.id(), p.codigo(), item.quantidade(), disponivel));
            }
        }
        if (!faltantes.isEmpty()) {
            RespostaHttp conflito = respostas.conflito(Erros.saldoInsuficiente(faltantes));
            idempotencia.concluir(oficinaId, chave, conflito.status(), conflito.contentType(), conflito.body());
            return conflito;   // nada de reserva foi escrito
        }

        Instant expiraEm = agora.plus(props.ttl());   // R4
        long reservaId = reservas.inserir(oficinaId, req.ordemServicoId(), userId, agora, expiraEm);
        reservas.inserirItens(reservaId, itens);

        var corpo = new ReservaResponse(reservaId, req.ordemServicoId(), StatusReserva.ATIVA.name(),
                agora, expiraEm,
                itens.stream().map(i -> new ItemResponse(i.pecaId(), travadas.get(i.pecaId()).codigo(), i.quantidade())).toList());
        RespostaHttp criado = respostas.criado(corpo);
        idempotencia.concluir(oficinaId, chave, criado.status(), criado.contentType(), criado.body());
        return criado;
    }

    /** Consolida pecaId repetido (soma) e ordena: resposta e hash deterministicos. */
    private List<ItemSolicitado> consolidar(List<ItemRequest> itens) {
        if (itens == null || itens.isEmpty()) {
            throw Erros.validacao("Informe ao menos um item.", Map.of());
        }
        TreeMap<Long, Integer> acc = new TreeMap<>();
        for (ItemRequest i : itens) {
            if (i.pecaId() == null || i.quantidade() == null || i.quantidade() <= 0) {
                throw Erros.validacao("Cada item exige pecaId e quantidade > 0.", Map.of());
            }
            acc.merge(i.pecaId(), i.quantidade(), Integer::sum);
        }
        return acc.entrySet().stream().map(e -> new ItemSolicitado(e.getKey(), e.getValue())).toList();
    }

    private List<ItemResponse> paraDto(List<ItemReserva> itens) {
        return itens.stream().map(i -> new ItemResponse(i.pecaId(), i.codigo(), i.quantidade())).toList();
    }

    private ReservaResponse paraDto(Reserva r, List<ItemReserva> itens) {
        return new ReservaResponse(r.id(), r.ordemServicoId(), r.status().name(), r.criadaEm(), r.expiraEm(), paraDto(itens));
    }

    @Transactional
    public RespostaHttp confirmar(long oficinaId, long reservaId) {
        Reserva r = reservas.buscarParaAtualizacao(oficinaId, reservaId)
                .orElseThrow(() -> Erros.reservaNaoEncontrada(reservaId));   // R6 -> 404
        Instant agora = clock.instant();

        if (r.status() != StatusReserva.ATIVA) {
            throw Erros.transicaoInvalida(r.status(), StatusReserva.CONFIRMADA.name());   // R5
        }
        if (!r.expiraEm().isAfter(agora)) {
            // A transicao precisa ser commitada: por isso 409 e RETORNO, nao excecao.
            reservas.mudarStatus(reservaId, StatusReserva.ATIVA, StatusReserva.EXPIRADA, agora);
            return respostas.conflito(Erros.reservaExpirada(reservaId));
        }

        List<ItemReserva> itens = reservas.itens(reservaId);   // ja vem ORDER BY peca_id
        for (ItemReserva item : itens) {
            pecas.buscarParaAtualizacao(oficinaId, item.pecaId())
                    .orElseThrow(() -> Erros.pecaNaoEncontrada(java.util.List.of(item.pecaId())));
        }
        for (ItemReserva item : itens) {
            if (pecas.baixarEstoque(oficinaId, item.pecaId(), item.quantidade()) != 1) {
                throw Erros.estoqueInconsistente(item.pecaId());   // rollback de toda a baixa
            }
        }
        if (reservas.mudarStatus(reservaId, StatusReserva.ATIVA, StatusReserva.CONFIRMADA, agora) != 1) {
            throw Erros.transicaoInvalida(r.status(), StatusReserva.CONFIRMADA.name());
        }
        return respostas.ok(new ReservaResponse(r.id(), r.ordemServicoId(), StatusReserva.CONFIRMADA.name(),
                r.criadaEm(), r.expiraEm(), paraDto(itens)));
    }

    @Transactional
    public RespostaHttp cancelar(long oficinaId, long reservaId) {
        Reserva r = reservas.buscarParaAtualizacao(oficinaId, reservaId)
                .orElseThrow(() -> Erros.reservaNaoEncontrada(reservaId));
        Instant agora = clock.instant();

        if (r.status() != StatusReserva.ATIVA) {
            throw Erros.transicaoInvalida(r.status(), StatusReserva.CANCELADA.name());
        }
        if (!r.expiraEm().isAfter(agora)) {
            reservas.mudarStatus(reservaId, StatusReserva.ATIVA, StatusReserva.EXPIRADA, agora);
            return respostas.conflito(Erros.reservaExpirada(reservaId));
        }
        // Cancelar nao mexe no estoque fisico: sair de ATIVA ja libera o saldo derivado.
        if (reservas.mudarStatus(reservaId, StatusReserva.ATIVA, StatusReserva.CANCELADA, agora) != 1) {
            throw Erros.transicaoInvalida(r.status(), StatusReserva.CANCELADA.name());
        }
        return respostas.ok(new ReservaResponse(r.id(), r.ordemServicoId(), StatusReserva.CANCELADA.name(),
                r.criadaEm(), r.expiraEm(), paraDto(reservas.itens(reservaId))));
    }

    @Transactional(readOnly = true)
    public ReservaResponse buscar(long oficinaId, long reservaId) {
        Reserva r = reservas.buscar(oficinaId, reservaId)
                .orElseThrow(() -> Erros.reservaNaoEncontrada(reservaId));
        return paraDto(r, reservas.itens(reservaId));
    }
}
