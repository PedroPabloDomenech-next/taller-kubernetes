#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
NAMESPACE="poke-app"

usage() {
  cat <<'EOF'
Uso:
  ./scripts/apply-pod-changes.sh front
  ./scripts/apply-pod-changes.sh back
  ./scripts/apply-pod-changes.sh auth
  ./scripts/apply-pod-changes.sh manifests
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

build_front() {
  docker build \
    -t poke-front:latest \
    "${REPO_ROOT}/poke-app/front" \
    --build-arg VITE_API_BASE_URL=/api/v2 \
    --build-arg VITE_AUTH_BASE_URL=/auth
}

build_back() {
  docker build -t poke-back:latest "${REPO_ROOT}/poke-app/back"
}

build_auth() {
  docker build -t poke-auth:latest "${REPO_ROOT}/poke-app/auth"
}

restart_deployment() {
  local deployment="$1"

  kubectl -n "${NAMESPACE}" rollout restart "deployment/${deployment}"
  kubectl -n "${NAMESPACE}" rollout status "deployment/${deployment}"
}

main() {
  local target="${1:-}"

  if [[ -z "${target}" ]]; then
    usage >&2
    exit 1
  fi

  require_command minikube
  require_command kubectl
  require_command docker

  case "${target}" in
    front|back|auth)
      log "Conectando al daemon Docker de Minikube"
      eval "$(minikube -p minikube docker-env --shell bash)"
      ;;
    manifests)
      ;;
    *)
      echo "Objetivo no soportado: ${target}" >&2
      usage >&2
      exit 1
      ;;
  esac

  case "${target}" in
    front)
      log "Reconstruyendo frontend"
      build_front
      log "Reiniciando deployment/front"
      restart_deployment front
      ;;
    back)
      log "Reconstruyendo backend"
      build_back
      log "Reiniciando deployment/back"
      restart_deployment back
      ;;
    auth)
      log "Reconstruyendo auth"
      build_auth
      log "Reiniciando deployment/auth"
      restart_deployment auth
      ;;
    manifests)
      log "Reaplicando manifiestos de Kubernetes"
      kubectl apply -k "${REPO_ROOT}/poke-app/k8s"
      ;;
  esac

  log "Pods actuales"
  kubectl get pods -n "${NAMESPACE}"
}

main "$@"
