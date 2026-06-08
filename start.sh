#!/usr/bin/env bash
# start.sh — single launcher for docGenerator (Git Bash on Windows)
# Usage:  bash start.sh          (default port 8001)
#         bash start.sh 8002     (custom port)

PORT=${1:-8001}
ROOT="$(cd "$(dirname "$0")" && pwd)"
FRONTEND="$ROOT/frontend"
VENV_PYTHON="$ROOT/.venv/Scripts/python.exe"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

echo ""
echo -e "${CYAN}${BOLD}=== docGenerator launcher ===${NC}"
echo ""

# ── 1. Kill old processes ─────────────────────────────────────────────────────
echo "[1/4] Stopping old Python / Node processes..."
taskkill /F /IM python.exe  >/dev/null 2>&1 || true
taskkill /F /IM python3.exe >/dev/null 2>&1 || true
taskkill /F /IM node.exe    >/dev/null 2>&1 || true
sleep 1

# ── 2. Find a free port ───────────────────────────────────────────────────────
echo "[2/4] Finding a free port starting at $PORT..."

# Check if a port is in use (strips \r from Windows netstat output)
port_in_use() {
    netstat -ano 2>/dev/null | tr -d '\r' | grep -qE "TCP\s.*:${1}\s.*LISTENING"
}

# Find first free port
p=$PORT
while port_in_use "$p"; do
    echo "  Port $p is in use, trying $((p+1))..."
    # Try to kill the squatter
    owner=$(netstat -ano 2>/dev/null | tr -d '\r' \
        | grep -E "TCP\s.*:${p}\s.*LISTENING" \
        | awk '{print $NF}' | head -1)
    if [[ "$owner" =~ ^[0-9]+$ ]] && [ "$owner" -gt 0 ]; then
        echo -e "  ${YELLOW}Killing PID $owner (holding port $p)${NC}"
        taskkill /F /PID "$owner" >/dev/null 2>&1 || true
        sleep 1
        port_in_use "$p" || break   # freed — stay on same port
    fi
    p=$((p + 1))
    [ $p -gt $((PORT + 20)) ] && { echo -e "${RED}No free port found.${NC}"; exit 1; }
done
PORT=$p
echo "  Using port $PORT"

# Write port into frontend/.env.local so Vite always has the right URL.
printf 'VITE_API_BASE=http://localhost:%s\n' "$PORT" > "$FRONTEND/.env.local"
echo "  frontend/.env.local → VITE_API_BASE=http://localhost:$PORT"

# ── 3. Start backend ──────────────────────────────────────────────────────────
echo "[3/4] Starting backend..."
"$VENV_PYTHON" -m uvicorn api:app --port "$PORT" --log-level warning &
BACKEND_PID=$!

# Poll /api/health until ready (up to 20 s)
READY=0
for i in $(seq 1 20); do
    sleep 1
    if curl -sf "http://localhost:${PORT}/api/health" >/dev/null 2>&1; then
        READY=1; break
    fi
done

if [ "$READY" -eq 0 ]; then
    echo -e "${RED}ERROR: Backend did not become healthy within 20 s.${NC}"
    echo "       Check logs/app.log for details."
    kill "$BACKEND_PID" 2>/dev/null || true
    exit 1
fi
echo -e "  ${GREEN}Backend ready → http://localhost:${PORT}${NC}"

# ── 4. Start frontend ─────────────────────────────────────────────────────────
echo "[4/4] Starting frontend..."
cd "$FRONTEND"

# Capture Vite output to a temp file so we can read the actual port
VITE_LOG=$(mktemp)
npm run dev 2>&1 | tee "$VITE_LOG" &
FRONTEND_PID=$!

# Wait for Vite to print its port (up to 15 s)
VITE_PORT=""
for i in $(seq 1 15); do
    sleep 1
    VITE_PORT=$(grep -oE "localhost:[0-9]+" "$VITE_LOG" 2>/dev/null | head -1 | cut -d: -f2)
    [ -n "$VITE_PORT" ] && break
done
VITE_URL="http://localhost:${VITE_PORT:-5173}"
echo -e "  ${GREEN}Frontend ready → ${VITE_URL}${NC}"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}┌──────────────────────────────────────────┐${NC}"
printf "${CYAN}${BOLD}│  Backend   http://localhost:%-14s  │${NC}\n" "$PORT"
printf "${CYAN}${BOLD}│  Frontend  %-30s  │${NC}\n" "$VITE_URL"
echo -e "${CYAN}${BOLD}│  Logs      logs/app.log                   │${NC}"
echo -e "${CYAN}${BOLD}└──────────────────────────────────────────┘${NC}"
echo ""
echo "Press Ctrl+C to stop both servers."
echo ""

# ── Cleanup on Ctrl+C ─────────────────────────────────────────────────────────
cleanup() {
    echo -e "\n${YELLOW}Shutting down...${NC}"
    kill "$BACKEND_PID"  2>/dev/null || true
    kill "$FRONTEND_PID" 2>/dev/null || true
    taskkill /F /IM python.exe >/dev/null 2>&1 || true
    taskkill /F /IM node.exe   >/dev/null 2>&1 || true
}
trap cleanup INT TERM EXIT

wait "$BACKEND_PID"
