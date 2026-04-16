#!/usr/bin/env bash

set -euo pipefail

if [[ "${EUID}" -eq 0 ]]; then
  SUDO=""
else
  SUDO="sudo"
fi

if ! command -v apt-get >/dev/null 2>&1; then
  echo "Este script soporta actualmente Debian/Ubuntu con apt." >&2
  exit 1
fi

log() {
  printf '\n==> %s\n' "$1"
}

remove_file_if_exists() {
  local path="$1"

  if [[ -e "${path}" ]]; then
    $SUDO rm -f "${path}"
    echo "Eliminado: ${path}"
  else
    echo "No existe, se omite: ${path}"
  fi
}

remove_packages_if_installed() {
  local packages=()
  local package

  for package in "$@"; do
    if dpkg -s "${package}" >/dev/null 2>&1; then
      packages+=("${package}")
    fi
  done

  if [[ "${#packages[@]}" -gt 0 ]]; then
    $SUDO apt-get remove -y "${packages[@]}"
  else
    echo "No hay paquetes instalados para eliminar en este bloque."
  fi
}

stop_docker_services() {
  if command -v systemctl >/dev/null 2>&1; then
    $SUDO systemctl disable --now docker.service docker.socket containerd.service >/dev/null 2>&1 || true
  fi
}

main() {
  log "Aviso"
  echo "Este proceso elimina Docker y componentes asociados instalados para el taller."
  echo "No se garantiza la conservacion de imagenes, contenedores o datos de otros proyectos que usen Docker en esta maquina."

  log "Deteniendo servicios de Docker"
  stop_docker_services

  log "Eliminando binarios instalados manualmente"
  remove_file_if_exists /usr/local/bin/kubectl
  remove_file_if_exists /usr/local/bin/minikube
  remove_file_if_exists /usr/local/bin/argocd

  log "Eliminando paquetes principales"
  remove_packages_if_installed \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin \
    conntrack \
    socat

  log "Eliminando repositorio de Docker anadido por el instalador"
  remove_file_if_exists /etc/apt/sources.list.d/docker.list
  remove_file_if_exists /etc/apt/keyrings/docker.gpg

  log "Actualizando indice de paquetes"
  $SUDO apt-get update

  log "Desinstalacion completada"
  echo "No se han borrado volumenes de Docker, imagenes, /var/lib/docker, /etc/docker, ~/.kube ni ~/.minikube."
  echo "Tampoco se hace purge ni autoremove para preservar configuraciones y datos persistentes."
}

main "$@"
