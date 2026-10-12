// Kịch bản k6: VUS người dùng ảo (mặc định 200) gọi trang chi tiết đơn GET /api/orders/{id}/detail liên tục trong
// DURATION (mặc định 2m), không nghỉ giữa hai lần gọi. Song song đó, 2 lần mỗi giây đọc số đo của app qua Actuator:
// kết nối DB đang chờ (hikaricp.connections.pending), đang dùng (active), luồng Tomcat đang bận, CPU của app.
//
// Chạy:  k6 run -e BASE_URL=http://localhost:8080 scripts/k6/order-detail-load.js
// Biến:  BASE_URL, VUS, DURATION, ORDERS (số đơn mới nhất để gọi ngẫu nhiên, mặc định 1000)
// Thường không chạy tay mà qua scripts/load-test.ps1 (khởi động app với từng cấu hình, chạy kịch bản này, in bảng).
//
// Kết quả: thay cho bảng tóm tắt mặc định, in đúng một dòng "K6_SUMMARY {json}" cho script đọc.

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '200', 10);
const DURATION = __ENV.DURATION || '2m';
const ORDERS = parseInt(__ENV.ORDERS || '1000', 10);

// Chỉ tính các lời gọi trang chi tiết (http_reqs có cả lời gọi lấy danh sách đơn, đọc Actuator)
const detailDuration = new Trend('detail_duration', true);
const detailRequests = new Counter('detail_requests');
const detailNot200 = new Counter('detail_not_200');
const detailDegraded = new Counter('detail_degraded');   // 200 nhưng thiếu nguồn phụ (unavailable khác rỗng)

const poolPending = new Trend('pool_pending');   // request (luồng) đang xếp hàng chờ kết nối
const poolActive = new Trend('pool_active');     // kết nối đang được dùng
const tomcatBusy = new Trend('tomcat_busy');     // luồng Tomcat đang xử lý request (chỉ có với luồng thường)
const appCpu = new Trend('app_cpu');             // CPU của app, 0..1 trên toàn máy

export const options = {
    scenarios: {
        detail: { executor: 'constant-vus', exec: 'detail', vus: VUS, duration: DURATION, gracefulStop: '5s' },
        metrics: {
            executor: 'constant-arrival-rate', exec: 'sample', rate: 2, timeUnit: '1s', duration: DURATION,
            preAllocatedVUs: 4, maxVUs: 20,
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    discardResponseBodies: false,
};

// Lấy id của ORDERS đơn mới nhất qua GET /api/orders (cursor), mỗi trang 100 đơn
export function setup() {
    const ids = [];
    let cursor = null;
    while (ids.length < ORDERS) {
        const url = `${BASE_URL}/api/orders?size=100` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : '');
        const res = http.get(url, { tags: { name: 'setup' } });
        if (res.status !== 200) {
            exec.test.abort(`GET ${url} -> ${res.status}: ${res.body}`);
        }
        const page = res.json();
        page.content.forEach((o) => ids.push(o.id));
        cursor = page.nextCursor;
        if (!cursor) {
            break;
        }
    }
    if (ids.length === 0) {
        exec.test.abort('DB chưa có đơn nào: tạo đơn trước (vd scripts/order-detail.ps1 hoặc seed-big-data.ps1)');
    }
    return { ids: ids.slice(0, ORDERS) };
}

export function detail(data) {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    const res = http.get(`${BASE_URL}/api/orders/${id}/detail`, { tags: { name: 'detail' }, timeout: '30s' });
    detailRequests.add(1);
    detailDuration.add(res.timings.duration);
    if (res.status !== 200) {
        detailNot200.add(1, { status: String(res.status) });
    } else if (res.body.indexOf('"unavailable":[]') < 0) {
        detailDegraded.add(1);
    }
}

function metric(name) {
    const res = http.get(`${BASE_URL}/actuator/metrics/${name}`, { tags: { name: 'actuator' }, timeout: '5s' });
    if (res.status !== 200) {
        return null;
    }
    const value = res.json().measurements.find((m) => m.statistic === 'VALUE');
    return value ? value.value : null;
}

export function sample() {
    const pending = metric('hikaricp.connections.pending');
    if (pending !== null) poolPending.add(pending);
    const active = metric('hikaricp.connections.active');
    if (active !== null) poolActive.add(active);
    const busy = metric('tomcat.threads.busy');   // -1 khi Tomcat chạy request trên luồng ảo (không có pool luồng)
    if (busy !== null && busy >= 0) tomcatBusy.add(busy);
    const cpu = metric('process.cpu.usage');
    if (cpu !== null) appCpu.add(cpu);
}

export function handleSummary(data) {
    const m = data.metrics;
    const v = (name, stat) => (m[name] && m[name].values[stat] !== undefined ? m[name].values[stat] : null);
    const summary = {
        vus: VUS,
        duration: DURATION,
        requests: v('detail_requests', 'count'),
        rps: v('detail_requests', 'rate'),
        avg: v('detail_duration', 'avg'),
        p50: v('detail_duration', 'med'),
        p95: v('detail_duration', 'p(95)'),
        p99: v('detail_duration', 'p(99)'),
        max: v('detail_duration', 'max'),
        not200: v('detail_not_200', 'count') || 0,
        degraded: v('detail_degraded', 'count') || 0,
        pendingAvg: v('pool_pending', 'avg'),
        pendingMax: v('pool_pending', 'max'),
        activeAvg: v('pool_active', 'avg'),
        activeMax: v('pool_active', 'max'),
        busyAvg: v('tomcat_busy', 'avg'),
        busyMax: v('tomcat_busy', 'max'),
        cpuAvg: v('app_cpu', 'avg'),
        cpuMax: v('app_cpu', 'max'),
    };
    return { stdout: `K6_SUMMARY ${JSON.stringify(summary)}\n` };
}
