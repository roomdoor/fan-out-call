
### maxPoolSize 스윕 결과 (mock 고정 지연 500ms · 상대 비교용 · 절대 성능치 아님)

| maxPoolSize | queueCapacity | capacity (pool+queue) | e2e avg (ms) | e2e p95 (ms) | http_req_failed | timeout_waiting_rate | iterations | dropped_iterations | Δ p95 vs prev |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 64 | 256 | 320 | 136 | 146 | 0.00% | 0.00% | 5,111 | 3 | — |
| 128 | 512 | 640 | 177 | 234 | 0.00% | 0.00% | 5,106 | 9 | -60.3%  ← 수확 체감 |
| 256 | 1024 | 1280 | 1,512 | 2,544 | 0.00% | 0.00% | 4,898 | 216 | -987.2% |
| 512 | 2048 | 2560 | 3,064 | 5,087 | 0.00% | 0.00% | 3,745 | 1,369 | -100.0% |

**수확 체감 지점**: maxPoolSize=128. 이전 단계 대비 p95 개선이 5% 미만으로 떨어짐.

_부가 정보_
- pool=64, queue=256: http_reqs=15,451, polls_per_tx_rate=56.7/s, checks_pass=80.1%, e2e_max=3,356 ms
- pool=128, queue=512: http_reqs=15,755, polls_per_tx_rate=58.6/s, checks_pass=80.4%, e2e_max=3,431 ms
- pool=256, queue=1024: http_reqs=20,921, polls_per_tx_rate=88.7/s, checks_pass=81.3%, e2e_max=6,412 ms
- pool=512, queue=2048: http_reqs=22,017, polls_per_tx_rate=98.6/s, checks_pass=82.1%, e2e_max=16,969 ms
