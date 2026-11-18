package com.example.reserva_pecas.demo.api.erro;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.example.reserva_pecas.demo.api.ApiException;
import com.example.reserva_pecas.demo.dominio.ItemFaltante;
import com.example.reserva_pecas.demo.dominio.StatusReserva;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;


public final class Erros {

    private Erros() { }

    public static ApiException headerAusente(String header) {
        return new ApiException(HttpStatus.BAD_REQUEST, "HEADER_OBRIGATORIO_AUSENTE",
                "Header obrigatorio ausente", "Informe o header " + header + ".", Map.of("header", header));
    }

    public static ApiException chaveAusente() {
        return new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_AUSENTE",
                "Idempotency-Key ausente", "O header Idempotency-Key e obrigatorio.", Map.of());
    }

    public static ApiException chaveReutilizada() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUTILIZADA",
                "Idempotency-Key reutilizada",
                "Esta Idempotency-Key ja foi usada com um corpo diferente.", Map.of());
    }

    public static ApiException validacao(String detalhe, Map<String, Object> extras) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDACAO_INVALIDA",
                "Requisicao invalida", detalhe, extras);
    }

    public static ApiException corpoIlegivel() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CORPO_INVALIDO",
                "Corpo invalido", "Nao foi possivel ler o JSON enviado.", Map.of());
    }

    public static ApiException cursorInvalido() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CURSOR_INVALIDO",
                "Cursor invalido", "O parametro cursor nao e um cursor valido desta API.", Map.of());
    }

    public static ApiException pecaNaoEncontrada(List<Long> ids) {
        return new ApiException(HttpStatus.NOT_FOUND, "PECA_NAO_ENCONTRADA",
                "Peca nao encontrada", "Pecas inexistentes nesta oficina: " + ids + ".", Map.of("pecasIds", ids));
    }

    public static ApiException reservaNaoEncontrada(long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESERVA_NAO_ENCONTRADA",
                "Reserva nao encontrada", "Reserva " + id + " inexistente nesta oficina.", Map.of("reservaId", id));
    }

    public static ApiException saldoInsuficiente(List<ItemFaltante> faltantes) {
        return new ApiException(HttpStatus.CONFLICT, "SALDO_INSUFICIENTE",
                "Saldo insuficiente",
                "Sem saldo para " + faltantes.size() + " item(ns); nada foi reservado.",
                Map.of("itensFaltantes", faltantes));
    }

    public static ApiException transicaoInvalida(StatusReserva atual, String alvo) {
        return new ApiException(HttpStatus.CONFLICT, "TRANSICAO_INVALIDA",
                "Transicao de status invalida",
                "Reserva esta " + atual + "; nao e possivel passar para " + alvo + ".",
                Map.of("statusAtual", atual.name(), "statusSolicitado", alvo));
    }

    public static ApiException reservaExpirada(long id) {
        return new ApiException(HttpStatus.CONFLICT, "RESERVA_EXPIRADA",
                "Reserva expirada", "A reserva " + id + " expirou e o saldo foi liberado.", Map.of("reservaId", id));
    }

    public static ApiException estoqueInconsistente(long pecaId) {
        return new ApiException(HttpStatus.CONFLICT, "ESTOQUE_FISICO_INSUFICIENTE",
                "Estoque fisico insuficiente",
                "Baixa do estoque falhou para a peca " + pecaId + "; operacao revertida.", Map.of("pecaId", pecaId));
    }

    public static ProblemDetail toProblem(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        pd.setTitle(e.getTitulo());
        pd.setType(URI.create("https://ultracar.com.br/erros/" + e.getCode().toLowerCase().replace('_', '-')));
        pd.setProperty("code", e.getCode());
        e.getExtras().forEach(pd::setProperty);
        return pd;
    }
}
