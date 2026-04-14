#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
K8S_DIR="${REPO_ROOT}/poke-app/k8s"
NAMESPACE="poke-app"
HOSTNAME_VALUE="${HOSTNAME_VALUE:-poke.local}"

log() {
  printf '\n==> %s\n' "$1"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Falta el comando requerido: $1" >&2
    exit 1
  fi
}

build_images() {
  log "Construyendo imagen poke-auth:latest"
  docker build -t poke-auth:latest "${REPO_ROOT}/poke-app/auth"

  log "Construyendo imagen poke-back:latest"
  docker build -t poke-back:latest "${REPO_ROOT}/poke-app/back"

  log "Construyendo imagen poke-front:latest"
  docker build \
    -t poke-front:latest \
    "${REPO_ROOT}/poke-app/front" \
    --build-arg VITE_API_BASE_URL=/api/v2 \
    --build-arg VITE_AUTH_BASE_URL=/auth
}

wait_for_rollouts() {
  kubectl -n "${NAMESPACE}" rollout status deployment/auth-db
  kubectl -n "${NAMESPACE}" rollout status deployment/auth
  kubectl -n "${NAMESPACE}" rollout status deployment/back
  kubectl -n "${NAMESPACE}" rollout status deployment/front
}

main() {
  require_command minikube
  require_command kubectl
  require_command docker

  log "Arrancando Minikube"
  minikube start

  log "Activando addon ingress"
  minikube addons enable ingress

  log "Conectando al daemon Docker de Minikube"
  eval "$(minikube -p minikube docker-env --shell bash)"

  build_images

  log "Aplicando manifiestos"
  kubectl apply -k "${K8S_DIR}"

  log "Esperando a que los deployments queden listos"
  wait_for_rollouts

  log "Estado final"
  kubectl get pods -n "${NAMESPACE}"
  kubectl get svc -n "${NAMESPACE}"
  kubectl get ingress -n "${NAMESPACE}"

  local minikube_ip
  minikube_ip="$(minikube ip)"

  echo
  echo "Minikube IP: ${minikube_ip}"
  echo "Anade esta entrada en /etc/hosts si aun no existe:"
  echo "${minikube_ip} ${HOSTNAME_VALUE}"
  echo "Prueba la app con: curl http://${HOSTNAME_VALUE}/"
}

main "$@"
