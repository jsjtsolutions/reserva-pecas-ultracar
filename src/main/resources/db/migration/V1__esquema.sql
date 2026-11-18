CREATE TABLE peca (
    id             BIGSERIAL    PRIMARY KEY,
    oficina_id     BIGINT       NOT NULL,
    codigo         VARCHAR(40)  NOT NULL,
    descricao      VARCHAR(200) NOT NULL,
    estoque_fisico INTEGER      NOT NULL,
    CONSTRAINT ck_peca_estoque_nao_negativo CHECK (estoque_fisico >= 0),
    CONSTRAINT uq_peca_oficina_codigo UNIQUE (oficina_id, codigo)
);

CREATE TABLE reserva (
    id               BIGSERIAL    PRIMARY KEY,
    oficina_id       BIGINT       NOT NULL,
    ordem_servico_id VARCHAR(40)  NOT NULL,
    status           VARCHAR(12)  NOT NULL,
    criada_em        TIMESTAMPTZ  NOT NULL,
    expira_em        TIMESTAMPTZ  NOT NULL,
    criada_por       BIGINT,
    atualizada_em    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_reserva_status CHECK (status IN ('ATIVA','CONFIRMADA','CANCELADA','EXPIRADA')),
    CONSTRAINT ck_reserva_janela CHECK (expira_em > criada_em)
);

CREATE TABLE reserva_item (
    reserva_id BIGINT  NOT NULL REFERENCES reserva(id) ON DELETE CASCADE,
    peca_id    BIGINT  NOT NULL REFERENCES peca(id),
    quantidade INTEGER NOT NULL,
    CONSTRAINT ck_item_quantidade_positiva CHECK (quantidade > 0),
    PRIMARY KEY (reserva_id, peca_id)
);

-- R3: a PK e o proprio mecanismo de serializacao entre instancias.
CREATE TABLE idempotencia (
    oficina_id            BIGINT      NOT NULL,
    chave                 VARCHAR(80) NOT NULL,
    hash_corpo            CHAR(64)    NOT NULL,
    resposta_status       SMALLINT,
    resposta_content_type VARCHAR(60),
    resposta_body         TEXT,
    criada_em             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (oficina_id, chave)
);

COMMENT ON TABLE idempotencia IS 'R3: guarda hash do corpo canonico e a resposta original para replay';