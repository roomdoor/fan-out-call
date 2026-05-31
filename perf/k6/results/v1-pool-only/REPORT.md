
### maxPoolSize 스윕 결과 (mock 고정 지연 500ms · 상대 비교용 · 절대 성능치 아님)

| maxPoolSize | e2e avg (ms) | e2e p95 (ms) | http_req_failed | timeout_waiting_rate | iterations | dropped_iterations | Δ p95 vs prev |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 64 | 128 | 129 | 0.00% | 0.00% | 5,115 | 0 | — |
| 128 | 140 | 151 | 0.00% | 0.00% | 5,106 | 8 | -17.1%  ← 수확 체감 |
| 256 | 682 | 1,335 | 0.00% | 0.00% | 5,057 | 58 | -784.1% |
| 512 | 1,990 | 2,851 | 0.00% | 0.00% | 4,613 | 501 | -113.6% |

**수확 체감 지점**: maxPoolSize=128. 이전 단계 대비 p95 개선이 5% 미만으로 떨어짐.

_부가 정보_
- pool=64: http_reqs=15,446, polls_per_tx_rate=57.0/s, checks_pass=80.1%, e2e_max=2,564 ms
- pool=128: http_reqs=15,545, polls_per_tx_rate=58.0/s, checks_pass=80.3%, e2e_max=2,741 ms
- pool=256: http_reqs=17,863, polls_per_tx_rate=70.8/s, checks_pass=81.0%, e2e_max=2,636 ms
- pool=512: http_reqs=24,527, polls_per_tx_rate=110.6/s, checks_pass=81.8%, e2e_max=5,746 ms
