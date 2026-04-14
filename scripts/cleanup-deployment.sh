#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
K8S_DIR="${REPO_ROOT}/poke-app/k8s"
NAMESPACE="poke-app"
DELETE_PVC=0

usage() {
  cat <<'EOF'
Uso:
  ./scripts/cleanup-deployment.sh
  ./scripts/cleanup-deployment.sh --delete-pvc
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
  if [[ "${1:-}" == "--delete-pvc" ]]; then
    DELETE_PVC=1
  elif [[ "${#}" -gt 0 ]]; then
    usage >&2
    exit 1
  fi

  require_command kubectl

  log "Eliminando recursos desplegados con Kustomize"
  kubectl delete -k "${K8S_DIR}" --ignore-not-found=true

  log "Comprobando recursos restantes en el namespace"
  kubectl get all -n "${NAMESPACE}" || true
  kubectl get ingress -n "${NAMESPACE}" || true
  kubectl get secret -n "${NAMESPACE}" || true

  if [[ "${DELETE_PVC}" -eq 1 ]]; then
    log "Eliminando PVC auth-db-data"
    kubectl delete pvc auth-db-data -n "${NAMESPACE}" --ignore-not-found=true
  fi
}

main "$@"
