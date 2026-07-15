#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Build & deploy Behavio ke server produksi
#
# Jalankan dari root repo:
#   ./deploy.sh [--frontend|--backend]
#
# Semua kredensial & konfigurasi target dibaca dari .env (salin dari .env.example).
# .env di-gitignore — kredensial produksi TIDAK PERNAH masuk git.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/deploy/deploy.sh" "$@"
