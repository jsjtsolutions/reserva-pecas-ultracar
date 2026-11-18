package com.example.reserva_pecas.demo.servico;

import com.example.reserva_pecas.demo.api.ApiException;
import com.example.reserva_pecas.demo.api.erro.Erros;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class Respostas {

    private final ObjectMapper mapper;

    public Respostas(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RespostaHttp criado(Object corpo) {
        return new RespostaHttp(201, MediaType.APPLICATION_JSON_VALUE, escrever(corpo));
    }

    public RespostaHttp ok(Object corpo) {
        return new RespostaHttp(200, MediaType.APPLICATION_JSON_VALUE, escrever(corpo));
    }

    public RespostaHttp conflito(ApiException e) {
        ProblemDetail pd = Erros.toProblem(e);
        java.util.Map<String, Object> corpo = new java.util.LinkedHashMap<>();
        corpo.put("type", pd.getType().toString());
        corpo.put("title", pd.getTitle());
        corpo.put("status", pd.getStatus());
        corpo.put("detail", pd.getDetail());
        corpo.putAll(pd.getProperties());   // code, itensFaltantes, reservaId...
        return new RespostaHttp(e.getStatus().value(), MediaType.APPLICATION_PROBLEM_JSON_VALUE, escrever(corpo));
    }

    private String escrever(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("falha ao serializar resposta", e);
        }
    }
}
