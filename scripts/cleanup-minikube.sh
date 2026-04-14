#!/usr/bin/env bash

set -euo pipefail

PURGE=0

usage() {
  cat <<'EOF'
Uso:
  ./scripts/cleanup-minikube.sh
  ./scripts/cleanup-minikube.sh --purge
EOF
}

log() {
  printf '\n==> %s\n' "$1"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Falta el comando requerido: $1" >&2
    exit 1
  fi
}

main() {
  if [[ "${1:-}" == "--purge" ]]; then
    PURGE=1
  elif [[ "${#}" -gt 0 ]]; then
    usage >&2
    exit 1
  fi

  require_command minikube

  log "Eliminando cluster de Minikube"
  if [[ "${PURGE}" -eq 1 ]]; then
    minikube delete --purge
  else
    minikube delete
  fi
}

main "$@"
