package com.example.reserva_pecas.demo.api.erro;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.reserva_pecas.demo.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> negocio(ApiException e) {
        return ResponseEntity.status(e.getStatus()).contentType(PROBLEM).body(Erros.toProblem(e));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> inesperado(Exception e) {
        log.error("erro nao tratado", e);
        var ex = new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ERRO_INTERNO",
                "Erro interno", "Falha inesperada ao processar a requisicao.", Map.of());
        return ResponseEntity.status(ex.getStatus()).contentType(PROBLEM).body(Erros.toProblem(ex));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> campos = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("campo", f.getField());
                    m.put("mensagem", String.valueOf(f.getDefaultMessage()));
                    return m;
                }).toList();
        var api = Erros.validacao("Corpo da requisicao invalido.", Map.of("campos", campos));
        return ResponseEntity.status(api.getStatus()).contentType(PROBLEM).body(Erros.toProblem(api));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var api = Erros.corpoIlegivel();
        return ResponseEntity.status(api.getStatus()).contentType(PROBLEM).body(Erros.toProblem(api));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> resposta = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (resposta.getBody() instanceof ProblemDetail pd
                && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            String code = statusCode.value() == 404 ? "RECURSO_NAO_ENCONTRADO"
                    : statusCode.value() == 405 ? "METODO_NAO_PERMITIDO" : "REQUISICAO_INVALIDA";
            pd.setProperty("code", code);
        }
        return resposta;
    }
}
