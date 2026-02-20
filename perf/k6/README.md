# k6 Performance Tests

Performance testing suite for loan-limit-gateway fan-out modes.

## Structure

```
perf/k6/
├── lib/
│   ├── common.js      # Shared utilities and config
│   ├── gateway.js     # Gateway API helpers
│   └── metrics.js     # Custom k6 metrics
├── journey.js         # End-to-end submit+poll test
└── smoke.js          # Quick health check
```

## Prerequisites

- k6 installed: `brew install k6`
- Gateway running on localhost:8080
- Mock servers running (10 shards on 18000-18009)
- MySQL running

## Quick Start

### Smoke Test
```bash
k6 run perf/k6/smoke.js
```

### Journey Test (single iteration)
```bash
MODE=coroutine k6 run perf/k6/journey.js
MODE=async-threadpool k6 run perf/k6/journey.js
MODE=webclient k6 run perf/k6/journey.js
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| BASE_URL | http://localhost:8080 | Gateway base URL |
| MODE | (required) | coroutine, async-threadpool, webclient |
| MAX_WAIT_MS | 60000 | Max time to wait for completion |
| POLL_MIN_MS | 100 | Initial poll interval |
| POLL_MAX_MS | 5000 | Max poll interval |

## Custom Metrics

- `e2e_completion_time`: End-to-end time (submit to terminal)
- `polls_per_transaction`: Number of poll requests per transaction
- `timeout_waiting_rate`: Rate of transactions that timed out

## Running Full Benchmarks

See runbook for detailed instructions on baseline and stress tests.
