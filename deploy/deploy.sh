#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Build & deploy Behavio (backend Spring Boot + dashboard Angular)
#
# Penggunaan:
#   ./deploy/deploy.sh [--frontend|--backend]
#
# Contoh:
#   ./deploy/deploy.sh              # build + deploy semua
#   ./deploy/deploy.sh --frontend   # hanya frontend
#   ./deploy/deploy.sh --backend    # hanya backend
#
# Prasyarat: `.env` di root repo (salin dari .env.example).
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(dirname "$SCRIPT_DIR")"
# backend-split: struktur 3 module (simulator=bank, qris, main-app). Menggantikan
# backend/ lama yang dihapus 2026-07-19 setelah migrasi penuh.
BACKEND_DIR="$WORKSPACE_DIR/backend-split"
FRONTEND_DIR="$WORKSPACE_DIR/frontend"
LOCAL_DEPLOY_DIR="$SCRIPT_DIR"
ENV_FILE="$WORKSPACE_DIR/.env"

# ── Warna output ──────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Parse flag --backend / --frontend ────────────────────────────────────────
BUILD_BACKEND=true
BUILD_FRONTEND=true
case "${1:-}" in
  --backend)  BUILD_FRONTEND=false ;;
  --frontend) BUILD_BACKEND=false  ;;
esac

# ── Baca .env ─────────────────────────────────────────────────────────────────
[[ -f "$ENV_FILE" ]] || error ".env tidak ditemukan di $WORKSPACE_DIR — jalankan: cp .env.example .env lalu isi nilainya"

set -a
source "$ENV_FILE"
set +a

# Gagal SEKARANG, bukan setelah container hidup: token tunnel yang kosong membuat
# cloudflared mati-hidup selamanya sementara deploy melaporkan sukses.
: "${CLOUDFLARE_TUNNEL_TOKEN:?CLOUDFLARE_TUNNEL_TOKEN kosong di .env — tunnel akan gagal. Isi tokennya, atau hapus service cloudflared dari deploy/docker-compose.yml bila memang tak dipakai}"

# BENTUKNYA juga diperiksa, bukan cuma keberadaannya. Token cloudflared adalah base64
# dari JSON {a,t,s}. Pernah terjadi tokennya tersimpan dengan `---` menempel di ujung —
# base64 decoder Python diam-diam mengabaikannya sehingga tampak sah, tapi cloudflared
# menolak dengan "Provided Tunnel token is not valid" dan restart selamanya.
if ! printf '%s' "$CLOUDFLARE_TUNNEL_TOKEN" | grep -Eq '^[A-Za-z0-9+/]+=*$'; then
  error "CLOUDFLARE_TUNNEL_TOKEN memuat karakter di luar base64 (mis. '-', spasi, atau baris baru) — salin ulang tokennya utuh dari Cloudflare Zero Trust → Networks → Tunnels"
fi
if ! python3 - "$CLOUDFLARE_TUNNEL_TOKEN" <<'PYCHECK'
import base64, json, sys
t = sys.argv[1]
try:
    d = json.loads(base64.b64decode(t + "=" * (-len(t) % 4), validate=True))
except Exception:
    sys.exit(1)
sys.exit(0 if {"a", "t", "s"} <= set(d) else 1)
PYCHECK
then
  error "CLOUDFLARE_TUNNEL_TOKEN tidak terbaca sebagai token cloudflared (base64 dari JSON {a,t,s}) — ambil token baru di Cloudflare Zero Trust → Networks → Tunnels → Configure"
fi
: "${DEPLOY_HOST:?DEPLOY_HOST harus diisi di .env}"
: "${DEPLOY_USER:?DEPLOY_USER harus diisi di .env}"
: "${DEPLOY_DIR:?DEPLOY_DIR harus diisi di .env}"

REMOTE_USER="$DEPLOY_USER"
REMOTE_HOST="$DEPLOY_HOST"
REMOTE_DIR="$DEPLOY_DIR"

info "=============================================="
info "  Remote      : $REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR"
info "  Env file    : $ENV_FILE"
info "=============================================="

TMPDIR_BUILD=$(mktemp -d)
trap 'rm -rf "$TMPDIR_BUILD"' EXIT

# ── Step 1: Build Backend ─────────────────────────────────────────────────────
if [[ "$BUILD_BACKEND" == true ]]; then
  info "=== Build Backend (Spring Boot / Gradle) ==="
  cd "$BACKEND_DIR"
  # Gradle, bukan Maven: repo ini tak punya pom.xml. bootJar menamai artefaknya
  # `behavio.jar` (main-app/build.gradle.kts), jadi path-nya pasti — tak perlu glob.
  # Modulnya :main-app (launcher yang merakit simulator+qris), bukan :app seperti dulu.
  ./gradlew :main-app:bootJar -x test --console=plain -q
  JAR_FILE="$BACKEND_DIR/main-app/build/libs/behavio.jar"
  [[ -f "$JAR_FILE" ]] || error "JAR tidak ditemukan: $JAR_FILE"
  JAR_SHA=$(sha256sum "$JAR_FILE" | cut -c1-16)
  success "JAR siap: $(du -sh "$JAR_FILE" | cut -f1)  sha256:${JAR_SHA}..."
fi

# ── Step 2: Build Frontend ────────────────────────────────────────────────────
if [[ "$BUILD_FRONTEND" == true ]]; then
  info "=== Build Frontend (Angular) ==="
  cd "$FRONTEND_DIR"
  rm -rf "$FRONTEND_DIR/dist"
  npm run build
  # `dist/starter` — nama projek di angular.json masih "starter" (sisa template
  # ng-matero). Kalau suatu saat diganti, outputPath di angular.json dan baris ini
  # harus berubah bersamaan.
  DIST_DIR="$FRONTEND_DIR/dist/starter/browser"
  [[ -d "$DIST_DIR" ]] || error "Dist tidak ditemukan: $DIST_DIR (cek outputPath di angular.json)"
  success "Frontend siap: $DIST_DIR"
fi

# ── Step 3: Transfer artifacts ke server ─────────────────────────────────────
info "=== Transfer ke server $REMOTE_USER@$REMOTE_HOST ==="
ssh "$REMOTE_USER@$REMOTE_HOST" "mkdir -p $REMOTE_DIR /tmp/behavio-build-backend /tmp/behavio-build-frontend"

if [[ "$BUILD_BACKEND" == true ]]; then
  info "Transfer JAR ($(du -sh "$JAR_FILE" | cut -f1))..."
  scp "$JAR_FILE" "$REMOTE_USER@$REMOTE_HOST:/tmp/behavio-build-backend/app.jar"
  success "JAR transferred"
fi

if [[ "$BUILD_FRONTEND" == true ]]; then
  info "Compress & transfer frontend dist..."
  tar czf "$TMPDIR_BUILD/frontend-dist.tar.gz" -C "$DIST_DIR" .
  scp "$TMPDIR_BUILD/frontend-dist.tar.gz" "$REMOTE_USER@$REMOTE_HOST:/tmp/behavio-build-frontend/dist.tar.gz"
  scp "$LOCAL_DEPLOY_DIR/nginx.conf"      "$REMOTE_USER@$REMOTE_HOST:/tmp/behavio-build-frontend/nginx.conf"
  success "Frontend dist transferred ($(du -sh "$TMPDIR_BUILD/frontend-dist.tar.gz" | cut -f1))"
fi

scp "$LOCAL_DEPLOY_DIR/docker-compose.yml" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/"
scp "$ENV_FILE"                              "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/.env"
success "Transfer selesai"

# ── Step 4: Build images & Deploy di server ───────────────────────────────────
info "=== Build & Deploy di server ==="
ssh "$REMOTE_USER@$REMOTE_HOST" bash <<ENDSSH
set -euo pipefail
RDIR="$REMOTE_DIR"
DO_BACKEND="$BUILD_BACKEND"
DO_FRONTEND="$BUILD_FRONTEND"

echo "  → Stop containers..."
cd "\$RDIR"
docker compose down --remove-orphans 2>/dev/null || true
docker ps -aq --filter "name=behavio-" | xargs -r docker rm -f 2>/dev/null || true

if [[ "\$DO_BACKEND" == true ]]; then
  echo "  → Build backend image on server..."
  docker rmi behavio-backend:latest 2>/dev/null || true
  # JRE 25, BUKAN 21: toolchain projek Java 25 (backend-split/build.gradle.kts). JRE 21
  # akan menolak jar-nya dengan UnsupportedClassVersionError saat start — gagal
  # setelah deploy "berhasil", jenis kegagalan yang paling membingungkan.
  cat > /tmp/behavio-build-backend/Dockerfile <<'DOCKERFILE'
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY app.jar app.jar
# Admin API + dashboard. EXPOSE bersifat dokumentatif saja: container memakai
# network_mode host, jadi seluruh port yang dibuka aplikasi langsung terjangkau.
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
DOCKERFILE
  docker build --no-cache -t behavio-backend:latest /tmp/behavio-build-backend/
  NEW_ID=\$(docker inspect --format='{{.Id}}' behavio-backend:latest | cut -c8-19)
  echo "  → Backend image: sha256:\${NEW_ID}"
  rm -rf /tmp/behavio-build-backend
fi

if [[ "\$DO_FRONTEND" == true ]]; then
  echo "  → Build frontend image on server..."
  docker rmi behavio-frontend:latest 2>/dev/null || true
  mkdir -p /tmp/behavio-build-frontend/html
  tar xzf /tmp/behavio-build-frontend/dist.tar.gz -C /tmp/behavio-build-frontend/html
  cat > /tmp/behavio-build-frontend/Dockerfile <<'DOCKERFILE'
FROM nginx:1.27-alpine
COPY html /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
DOCKERFILE
  docker build --no-cache -t behavio-frontend:latest /tmp/behavio-build-frontend/
  NEW_ID=\$(docker inspect --format='{{.Id}}' behavio-frontend:latest | cut -c8-19)
  echo "  → Frontend image: sha256:\${NEW_ID}"
  rm -rf /tmp/behavio-build-frontend
fi

echo "  → Start containers..."
cd "\$RDIR"
docker compose up -d --remove-orphans --pull never --force-recreate

echo ""
docker compose ps

# Tunnel gagal itu senyap: container tetap "Up" sebentar lalu restart, dan deploy
# terlanjur dilaporkan sukses. Diperiksa di sini supaya ketahuan saat itu juga.
echo ""
echo "  → Cek tunnel Cloudflare..."
sleep 8
if docker logs behavio-cloudflared 2>&1 | tail -40 | grep -qiE "Registered tunnel connection|Connection [a-f0-9-]+ registered"; then
  echo "  ✓ cloudflared tersambung"
else
  echo "  ✗ cloudflared BELUM tersambung — 20 baris log terakhir:"
  docker logs behavio-cloudflared 2>&1 | tail -20 | sed "s/^/      /"
fi
ENDSSH

echo ""
success "======================================================"
success "  Deploy selesai!"
success "  URL : http://$REMOTE_HOST"
success "======================================================"
