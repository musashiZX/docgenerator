#!/usr/bin/env bash
# start-java.sh — Java DocIO backend + React frontend (Git Bash on Windows)
# Usage:  bash start-java.sh          (default port 8080)
#         bash start-java.sh 8082

PORT=${1:-8080}
ROOT="$(cd "$(dirname "$0")" && pwd)"
FRONTEND="$ROOT/frontend"
DOC_SERVER="$ROOT/doc-agent-server"
ENV_FILE="$ROOT/.env"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

load_dotenv() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo -e "  ${YELLOW}No .env at $file — copy .env.example to .env${NC}"
    return 0
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${line// }" ]] && continue
    local name="${line%%=*}"
    local value="${line#*=}"
    value="${value#\"}"; value="${value%\"}"
    value="${value#\'}"; value="${value%\'}"
    export "$name=$value"
  done < "$file"
  echo "  Loaded $file"
}

port_in_use() {
  netstat -ano 2>/dev/null | tr -d '\r' | grep -qE "TCP\s.*:${1}\s.*LISTENING"
}

kill_port_owner() {
  local p="$1"
  local owner
  owner=$(netstat -ano 2>/dev/null | tr -d '\r' \
    | grep -E "TCP\s.*:${p}\s.*LISTENING" \
    | awk '{print $NF}' | head -1)
  if [[ "$owner" =~ ^[0-9]+$ ]] && [ "$owner" -gt 0 ]; then
    echo -e "  ${YELLOW}Killing PID $owner (holding port $p)${NC}"
    taskkill /F /PID "$owner" >/dev/null 2>&1 || true
    sleep 1
  fi
}

write_frontend_env_local() {
  local backend_port="$1"
  local vite_key="${VITE_SYNCFUSION_LICENSE:-$SYNCFUSION_LICENSE_KEY}"
  {
    printf 'VITE_API_BASE=http://localhost:%s\n' "$backend_port"
    if [[ -n "$vite_key" ]]; then
      printf 'VITE_SYNCFUSION_LICENSE=%s\n' "$vite_key"
    fi
  } > "$FRONTEND/.env.local"
  echo "  frontend/.env.local → API + Syncfusion license"
}

echo ""
echo -e "${CYAN}${BOLD}=== docGenerator — Java DocIO launcher ===${NC}"
echo ""

echo "[1/5] Loading environment..."
load_dotenv "$ENV_FILE"

# Same key for Java DocIO + React when license includes both SDKs
if [[ -n "$SYNCFUSION_LICENSE_KEY" && -z "$VITE_SYNCFUSION_LICENSE" ]]; then
  export VITE_SYNCFUSION_LICENSE="$SYNCFUSION_LICENSE_KEY"
fi

echo "[2/5] Stopping old Node processes..."
taskkill /F /IM node.exe >/dev/null 2>&1 || true
sleep 1

echo "[3/5] Finding backend port..."
p=$PORT
while port_in_use "$p"; do
  echo "  Port $p is in use, trying $((p+1))..."
  kill_port_owner "$p"
  port_in_use "$p" || break
  p=$((p + 1))
  [[ $p -gt $((PORT + 20)) ]] && { echo -e "${RED}No free port found.${NC}"; exit 1; }
done
PORT=$p
kill_port_owner "$PORT"
echo "  Using port $PORT"

export SERVER_PORT="$PORT"
write_frontend_env_local "$PORT"

echo "[4/5] Starting Java backend (Maven)..."
echo "  First run may download dependencies — allow up to 60 s..."
cd "$DOC_SERVER" || exit 1
mvn -q spring-boot:run &
BACKEND_PID=$!

READY=0
for i in $(seq 1 60); do
  sleep 1
  if curl -sf "http://localhost:${PORT}/api/health" >/dev/null 2>&1; then
    READY=1
    break
  fi
done

if [[ "$READY" -eq 0 ]]; then
  echo -e "${RED}ERROR: Java backend did not become healthy within 60 s.${NC}"
  kill "$BACKEND_PID" 2>/dev/null || true
  exit 1
fi
echo -e "  ${GREEN}Backend ready → http://localhost:${PORT}${NC}"

echo "[5/5] Starting frontend..."
cd "$FRONTEND" || exit 1
VITE_LOG=$(mktemp)
npm run dev 2>&1 | tee "$VITE_LOG" &
FRONTEND_PID=$!

VITE_PORT=""
for i in $(seq 1 15); do
  sleep 1
  VITE_PORT=$(grep -oE "localhost:[0-9]+" "$VITE_LOG" 2>/dev/null | head -1 | cut -d: -f2)
  [[ -n "$VITE_PORT" ]] && break
done
VITE_URL="http://localhost:${VITE_PORT:-5173}"
echo -e "  ${GREEN}Frontend ready → ${VITE_URL}${NC}"

echo ""
echo -e "${CYAN}${BOLD}┌──────────────────────────────────────────┐${NC}"
printf "${CYAN}${BOLD}│  Java API  http://localhost:%-14s  │${NC}\n" "$PORT"
printf "${CYAN}${BOLD}│  Frontend  %-30s  │${NC}\n" "$VITE_URL"
echo -e "${CYAN}${BOLD}│  Docs      doc-agent-server/docs/         │${NC}"
echo -e "${CYAN}${BOLD}│  Secrets   .env (repo root, gitignored)   │${NC}"
echo -e "${CYAN}${BOLD}└──────────────────────────────────────────┘${NC}"
echo ""
echo "Press Ctrl+C to stop both servers."
echo ""

cleanup() {
  echo -e "\n${YELLOW}Shutting down...${NC}"
  kill "$BACKEND_PID"  2>/dev/null || true
  kill "$FRONTEND_PID" 2>/dev/null || true
  taskkill /F /IM node.exe >/dev/null 2>&1 || true
  # Kill Java only if it holds our port
  kill_port_owner "$PORT"
}
trap cleanup INT TERM EXIT

wait "$BACKEND_PID"
