package com.example.reserva_pecas.demo.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String titulo;
    private final transient Map<String, Object> extras;

    public ApiException(HttpStatus status, String code, String titulo, String detail, Map<String, Object> extras) {
        super(detail);
        this.status = status;
        this.code = code;
        this.titulo = titulo;
        this.extras = extras == null ? Map.of() : extras;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getTitulo() { return titulo; }
    public Map<String, Object> getExtras() { return extras; }
}
