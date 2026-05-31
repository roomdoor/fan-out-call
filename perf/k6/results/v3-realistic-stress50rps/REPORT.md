
### maxPoolSize 스윕 결과 (mock: 정상 7~13s · slow×2개 30s · 상대 비교용 · 절대 성능치 아님)

| maxPoolSize | queueCapacity | capacity (pool+queue) | e2e avg (ms) | e2e p95 (ms) | http_req_failed | timeout_waiting_rate | iterations | dropped_iterations | Δ p95 vs prev |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 64 | 256 | 320 | 184 | 129 | 0.00% | 0.00% | 5,114 | 0 | — |
| 128 | 512 | 640 | 245 | 138 | 0.00% | 0.00% | 5,111 | 4 | -7.0%  ← 수확 체감 |
| 256 | 1024 | 1280 | 436 | 140 | 0.00% | 0.00% | 5,092 | 22 | -1.4% |
| 512 | 2048 | 2560 | 120,000 | 120,000 | 0.00% | 100.00% | 100 | 4,915 | -85614.3% |

**수확 체감 지점**: maxPoolSize=128. 이전 단계 대비 p95 개선이 5% 미만으로 떨어짐.

_부가 정보_
- pool=64, queue=256: http_reqs=15,447, polls_per_tx_rate=57.4/s, checks_pass=80.0%, e2e_max=71,709 ms
- pool=128, queue=512: http_reqs=15,536, polls_per_tx_rate=57.9/s, checks_pass=80.0%, e2e_max=71,711 ms
- pool=256, queue=1024: http_reqs=15,794, polls_per_tx_rate=59.5/s, checks_pass=80.1%, e2e_max=76,717 ms
- pool=512, queue=2048: http_reqs=5,576, polls_per_tx_rate=15.2/s, checks_pass=66.7%, e2e_max=120,000 ms
