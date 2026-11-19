### Scenario A — Read-Intensive

Умови: cache enabled, 3 instances, 50 VUs.
Ступінь: 30s з постійною кількістю VUs; розігрів: 15s × 20 VUs.

| Навантаження (VUs) | Запитів | Throughput (RPS) | Avg, мс | p50, мс | p95, мс | p99 (Tail), мс | Max, мс | Error Rate, % |
|---|---|---|---|---|---|---|---|---|
| 50 | 5251 | 175.0 | 286.6 | 215.3 | 795.8 | 1041.6 | 1614.7 | 0.00 |

Cache hit ratio (X-Cache: HIT серед кешованих запитів): 99.6 %
