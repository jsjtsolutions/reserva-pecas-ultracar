import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const DURACAO = __ENV.DURACAO || '60s';
const TERMOS = ['Pastilha', 'Correia', 'Amortecedor', 'Filtro', 'Rolamento', 'Sensor',
    'Disco', 'Junta', 'Bomba', 'Vela', 'dianteiro', 'traseiro', 'aplicacao 123',
    'P0001', 'P0100', 'linha 7'];

export const options = {
    scenarios: {
        busca: { executor: 'constant-vus', vus: 20, duration: DURACAO, gracefulStop: '5s' },
    },
    thresholds: {
        http_req_duration: ['p(95)<150'],
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.99'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
    const oficina = 1 + Math.floor(Math.random() * 50);
    const termo = TERMOS[Math.floor(Math.random() * TERMOS.length)];
    const res = http.get(`${BASE}/api/v1/pecas?busca=${encodeURIComponent(termo)}`, {
        headers: { 'X-Oficina-Id': String(oficina), 'X-User-Id': '1001' },
        tags: { endpoint: 'busca' },
    });
    check(res, {
        'status 200': (r) => r.status === 200,
        'tem itens': (r) => ((r.json('itens') || [])).length > 0,
        'no maximo 20': (r) => ((r.json('itens') || [])).length <= 20,
    });
    sleep(0.1);
}

export function handleSummary(data) {
    const d = data.metrics.http_req_duration.values;
    const reqs = data.metrics.http_reqs.values.count;
    const falhas = data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0;
    const linha = `reqs=${reqs} falhas=${(falhas * 100).toFixed(2)}% avg=${d.avg.toFixed(1)}ms `
        + `p90=${d['p(90)'].toFixed(1)}ms p95=${d['p(95)'].toFixed(1)}ms p99=${d['p(99)'].toFixed(1)}ms\n`;
    return { stdout: linha, 'carga/resultado-busca.json': JSON.stringify(data, null, 2) };
}