import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PECA = Number(__ENV.PECA_ID);
const OFICINA = __ENV.OFICINA_ID || '42';
const criadas = new Counter('reservas_201');
const negadas = new Counter('reservas_409');

http.setResponseCallback(http.expectedStatuses(201, 409));

export const options = {
    scenarios: { disputa: { executor: 'per-vu-iterations', vus: 20, iterations: 1, maxDuration: '60s' } },
    thresholds: {
        reservas_201: ['count==5'],
        reservas_409: ['count==15'],
        http_req_failed: ['rate==0'],
    },
};

export default function () {
    const chave = `k6-${__VU}-${Date.now()}-${Math.random()}`;
    const corpo = JSON.stringify({ ordemServicoId: `OS-K6-${__VU}`, itens: [{ pecaId: PECA, quantidade: 1 }] });
    const res = http.post(`${BASE}/api/v1/reservas`, corpo, {
        headers: {
            'Content-Type': 'application/json',
            'X-Oficina-Id': OFICINA,
            'X-User-Id': '1001',
            'Idempotency-Key': chave,
        },
    });
    if (res.status === 201) criadas.add(1);
    if (res.status === 409) negadas.add(1);
    check(res, { '201 ou 409': (r) => r.status === 201 || r.status === 409 });
}

export function teardown() {
    const r = http.get(`${BASE}/api/v1/pecas/${PECA}/saldo`, { headers: { 'X-Oficina-Id': OFICINA } });
    console.log(`SALDO FINAL (esperado 0): ${r.body}`);
}