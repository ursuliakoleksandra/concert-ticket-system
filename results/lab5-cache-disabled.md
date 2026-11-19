### Scenario A — Read-Intensive

Умови: cache disabled, 3 instances, 50 VUs.
Ступінь: 30s з постійною кількістю VUs; розігрів: 15s × 20 VUs.

| Навантаження (VUs) | Запитів | Throughput (RPS) | Avg, мс | p50, мс | p95, мс | p99 (Tail), мс | Max, мс | Error Rate, % |
|---|---|---|---|---|---|---|---|---|
| 50 | 4428 | 147.6 | 341.3 | 91.7 | 1301.9 | 1882.8 | 3229.8 | 0.00 |

Cache hit ratio (X-Cache: HIT серед кешованих запитів): 0.0 %
