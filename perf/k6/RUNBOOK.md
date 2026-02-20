# Performance Testing Runbook

Complete guide for running performance tests on loan-limit-gateway.

## Prerequisites

### Required Tools

- **k6**: `brew install k6` (or download from https://k6.io/docs/get-started/installation/)
- **Docker**: For running mock server fleet
- **Docker Compose**: Usually included with Docker Desktop
- **Node.js**: For report generation script
- **Java 17+**: For building and running the gateway

### Verify Installation

```bash
k6 version
docker --version
docker-compose --version
java -version
node --version
```

## Quick Start

### 1. Start Mock Fleet

```bash
cd perf/k6
./harness.sh up
```

This starts:
- Mock server fleet (10 shards on ports 18000-18009)

You run MySQL and `loan-limit-gateway` separately.

### 2. Run a Test

```bash
MODE=coroutine PROFILE=baseline ./harness.sh run
```

### 3. Generate Report

```bash
node report.js
```

### 4. Stop Mock Fleet

```bash
./harness.sh down
```

## Full Test Cycle

Run mock-fleet up + test + down in one command:

```bash
MODE=coroutine PROFILE=baseline ./harness.sh full
```

## Test Profiles

### Baseline

Deterministic, low-load test for fair comparison:

```bash
MODE=coroutine PROFILE=baseline ./harness.sh run
MODE=async-threadpool PROFILE=baseline ./harness.sh run
MODE=webclient PROFILE=baseline ./harness.sh run
```

**Characteristics**:
- Fixed 500ms latency from mock servers
- 100% success rate
- 3 RPS arrival rate
- 3-minute duration

### Stress

Find throughput limits:

```bash
MODE=coroutine PROFILE=stress ./harness.sh run
```

**Characteristics**:
- Variable latency (3-15s normal, 30-45s slow)
- 90% success rate
- Ramps from 1 to 50 RPS
- 3-minute total duration

### Smoke

Quick health check:

```bash
MODE=coroutine PROFILE=smoke ./harness.sh run
```

### Journey

Single end-to-end test:

```bash
MODE=coroutine PROFILE=journey ./harness.sh run
```

## Manual Mode Selection

The gateway supports two routing modes:

### Single Mock Server (Default)

All banks call the same mock server:

```yaml
app:
  web-client-fan-out:
    routing-mode: single
    mock-base-url: http://localhost:18080
```

### Sharded (10 instances)

Distributes calls across 10 mock servers:

```yaml
app:
  web-client-fan-out:
    routing-mode: sharded
    sharded-mock-routing:
      base-port: 18000
      shard-count: 10
```

**Routing Formula**: `port = 18000 + ((bankNumber - 1) % 10)`

- BANK-01 → port 18000
- BANK-10 → port 18009
- BANK-11 → port 18000
- BANK-50 → port 18009

## Mock Server Profiles

### Baseline Profile

Located at `/Users/sihwa/IdeaProjects/loan-limit-mock-server/perf/baseline.env`:

```bash
MOCK_MIN_LATENCY_MS=500
MOCK_MAX_LATENCY_MS=500
MOCK_SLOW_BANK_COUNT=0
MOCK_SUCCESS_RATE_PERCENT=100
```

### Stress Profile

Located at `/Users/sihwa/IdeaProjects/loan-limit-mock-server/perf/stress.env`:

```bash
MOCK_MIN_LATENCY_MS=3000
MOCK_MAX_LATENCY_MS=15000
MOCK_SLOW_MIN_LATENCY_MS=30000
MOCK_SLOW_MAX_LATENCY_MS=45000
MOCK_SLOW_BANK_COUNT=2
MOCK_SUCCESS_RATE_PERCENT=90
```

## Directory Structure

```
perf/k6/
├── lib/
│   ├── common.js       # Shared configuration
│   ├── gateway.js      # API helpers
│   └── metrics.js      # Custom metrics
├── baseline.js         # Baseline test scenario
├── stress.js           # Stress test scenario
├── journey.js          # End-to-end test
├── smoke.js            # Quick health check
├── run.sh              # Single test runner
├── harness.sh          # Full infrastructure runner
├── report.js           # Report generator
└── README.md           # This file
```

## Test Artifacts

After each run, evidence is saved to:

```
.sisyphus/evidence/perf/{timestamp}_{mode}_{profile}/
├── manifest.json       # Run metadata
├── summary.json        # k6 results
└── gateway-git.json    # Gateway version info
```

## Interpreting Results

### Key Metrics

1. **e2e_completion_time**: Time from submit to terminal status
   - Lower is better
   - Baseline target: p95 < 30s
   
2. **http_req_failed**: HTTP error rate
   - Lower is better
   - Baseline target: < 1%
   
3. **timeout_waiting_rate**: Transactions that timed out
   - Lower is better
   - Baseline target: < 1%

### Comparative Analysis

Run all three modes with the same profile:

```bash
for mode in coroutine async-threadpool webclient; do
  MODE=$mode PROFILE=baseline ./harness.sh run
done

node report.js
```

Compare:
- End-to-end completion times
- Error rates
- Timeout rates
- Poll efficiency (polls per transaction)

## Troubleshooting

### Gateway won't start

Check if port 8080 is in use:
```bash
lsof -i :8080
```

### Mock fleet won't start

Check if ports 18000-18009 are available:
```bash
lsof -i :18000
```

Check Docker memory limit:
```bash
docker info | grep Memory
```

### MySQL connection failed

Verify MySQL is running:
```bash
docker ps | grep mysql
```

Check connection:
```bash
docker exec loan-limit-gateway-mysql mysql -u root -proot -e "SELECT 1"
```

### k6 test fails

Check gateway health:
```bash
curl http://localhost:8080/actuator/health
```

Check mock fleet health:
```bash
curl http://localhost:18000/health
```

## Advanced Usage

### Custom Environment Variables

```bash
MODE=coroutine \
PROFILE=baseline \
BASE_URL=http://localhost:8080 \
MAX_WAIT_MS=90000 \
POLL_MIN_MS=200 \
POLL_MAX_MS=10000 \
./harness.sh run
```

### Run Without Harness

Start infrastructure manually:

```bash
# 1. Start MySQL
docker-compose up -d mysql

# 2. Start mock fleet
cd /Users/sihwa/IdeaProjects/loan-limit-mock-server/perf
source baseline.env
docker-compose up -d

# 3. Start gateway (with sharded routing)
cd /Users/sihwa/IdeaProjects/loan-limit-gateway
./gradlew bootRun --args='--app.web-client-fan-out.routing-mode=sharded'

# 4. Run test
cd perf/k6
MODE=coroutine k6 run baseline.js
```

### View Logs

Gateway logs:
```bash
tail -f /Users/sihwa/IdeaProjects/loan-limit-gateway/build/logs/*.log
```

Mock fleet logs:
```bash
cd /Users/sihwa/IdeaProjects/loan-limit-mock-server/perf
docker-compose logs -f mock-bank-00
```

## Tips for Fair Comparison

1. **Use deterministic mock settings** (baseline.env) when comparing modes
2. **Run multiple iterations** (3x recommended) to account for variance
3. **Keep infrastructure constant** between mode comparisons
4. **Monitor resource usage** (CPU, memory) to identify bottlenecks
5. **Clear evidence directory** between major test cycles if disk space is limited

## Safety Guidelines

⚠️ **Never run stress tests in production**

⚠️ **Always run `./harness.sh down` after testing to free resources**

⚠️ **Mock fleet uses significant memory** - ensure Docker has at least 4GB allocated

## Further Reading

- [Benchmark Spec](../../.sisyphus/perf/BENCHMARK_SPEC.md)
- [k6 Documentation](https://k6.io/docs/)
- [Gateway README](../../../README.md)
- [Mock Server README](/Users/sihwa/IdeaProjects/loan-limit-mock-server/README.md)
