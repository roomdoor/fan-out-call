#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOCK_SERVER_DIR="/Users/sihwa/IdeaProjects/loan-limit-mock-server"

echo "=== Mock Fleet Harness (loan-limit-mock-server) ==="
echo ""

show_usage() {
  echo "Usage: MODE=<mode> PROFILE=<profile> ./harness.sh [command]"
  echo ""
  echo "Commands:"
  echo "  up       - Start mock server fleet only (10 shards)"
  echo "  down     - Stop mock server fleet only"
  echo "  run      - Run performance test (requires MODE and PROFILE)"
  echo "  full     - up + run + down (mock fleet only)"
  echo ""
  echo "Environment Variables:"
  echo "  PROFILE    - baseline | stress | smoke | journey"
  echo "  MOCK_PROFILE - baseline | stress (used for mock fleet; default: baseline)"
  echo "  MODE       - coroutine | async-threadpool | webclient"
  echo "  BASE_URL   - Gateway base URL (default: http://localhost:8080)"
  echo ""
  echo "Examples:"
  echo "  MOCK_PROFILE=baseline ./harness.sh up"
  echo "  ./harness.sh up"
  echo "  MODE=webclient PROFILE=stress ./harness.sh run"
  echo "  ./harness.sh down"
}

start_mock_fleet() {
  echo "Starting mock server fleet (10 shards)..."
  cd "${MOCK_SERVER_DIR}"

  if [ ! -d build/install/loan-limit-mock-server ]; then
    echo "Building mock server distribution..."
    ./gradlew installDist
  fi
  
  cd "${MOCK_SERVER_DIR}/perf"

  local profile="${MOCK_PROFILE:-${PROFILE:-baseline}}"

  if [ "${profile}" == "stress" ]; then
    source stress.env
  else
    source baseline.env
  fi
  
  docker-compose up -d --build --force-recreate || {
    echo "Failed to start mock fleet. Is Docker running?"
    exit 1
  }
  
  echo "Waiting for mock fleet to be ready..."
  for port in 18000 18005 18009; do
    local ready=false
    for i in {1..30}; do
      if curl -sf "http://localhost:${port}/health" &>/dev/null; then
        echo "Port ${port} is ready!"
        ready=true
        break
      fi
      sleep 1
    done

    if [ "$ready" != true ]; then
      echo "Port ${port} failed health check"
      exit 1
    fi
  done
  
  echo "Mock fleet is ready!"
}

stop_mock_fleet() {
  echo "Stopping mock server fleet..."
  cd "${MOCK_SERVER_DIR}/perf"
  docker-compose down || true
}

run_test() {
  if [ -z "$MODE" ] || [ -z "$PROFILE" ]; then
    echo "Error: MODE and PROFILE must be set"
    show_usage
    exit 1
  fi
  
  echo "Running performance test..."
  echo "Mode: ${MODE}"
  echo "Profile: ${PROFILE}"
  
  cd "${SCRIPT_DIR}"
  ./run.sh
}

case "${1:-}" in
  up)
    start_mock_fleet
    echo ""
    echo "=== Mock fleet is up and running ==="
    ;;
  down)
    stop_mock_fleet
    echo ""
    echo "=== Mock fleet stopped ==="
    ;;
  run)
    run_test
    ;;
  full)
    start_mock_fleet
    run_test
    stop_mock_fleet
    echo ""
    echo "=== Mock fleet cycle complete ==="
    ;;
  *)
    show_usage
    exit 1
    ;;
esac
