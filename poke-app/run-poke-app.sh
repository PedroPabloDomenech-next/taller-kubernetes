#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACK_DIR="$ROOT_DIR/back"
AUTH_DIR="$ROOT_DIR/auth"
FRONT_DIR="$ROOT_DIR/front"

BACK_PID=""
AUTH_PID=""
FRONT_PID=""

cleanup() {
  if [[ -n "${FRONT_PID}" ]] && kill -0 "${FRONT_PID}" 2>/dev/null; then
    kill "${FRONT_PID}" 2>/dev/null || true
  fi

  if [[ -n "${AUTH_PID}" ]] && kill -0 "${AUTH_PID}" 2>/dev/null; then
    kill "${AUTH_PID}" 2>/dev/null || true
  fi

  if [[ -n "${BACK_PID}" ]] && kill -0 "${BACK_PID}" 2>/dev/null; then
    kill "${BACK_PID}" 2>/dev/null || true
  fi

  docker compose stop auth-db >/dev/null 2>&1 || true
}

require_command() {
  local command_name="$1"

  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Error: no se encontró '${command_name}' en el PATH." >&2
    exit 1
  fi
}

trap cleanup EXIT INT TERM

require_command dotnet
require_command npm
require_command docker

if ! docker compose version >/dev/null 2>&1; then
  echo "Error: no se ha encontrado 'docker compose'." >&2
  exit 1
fi

if [[ ! -d "$FRONT_DIR/node_modules" ]]; then
  echo "Instalando dependencias del frontend..."
  (cd "$FRONT_DIR" && npm install)
fi

echo "Restaurando dependencias del backend..."
(cd "$BACK_DIR" && dotnet restore >/dev/null)

echo "Restaurando dependencias del auth..."
(cd "$AUTH_DIR" && dotnet restore >/dev/null)

echo "Levantando PostgreSQL para auth..."
(cd "$ROOT_DIR" && docker compose up -d auth-db >/dev/null)

echo "Esperando a que PostgreSQL esté disponible..."
for _ in {1..30}; do
  if (cd "$ROOT_DIR" && docker compose exec -T auth-db pg_isready -U poke -d poke_auth >/dev/null 2>&1); then
    break
  fi
  sleep 1
done

if ! (cd "$ROOT_DIR" && docker compose exec -T auth-db pg_isready -U poke -d poke_auth >/dev/null 2>&1); then
  echo "Error: PostgreSQL no está disponible tras esperar 30 segundos." >&2
  exit 1
fi

echo "Levantando auth en http://localhost:5190 ..."
(
  cd "$AUTH_DIR" && \
  ConnectionStrings__AuthDb="Host=localhost;Port=55432;Database=poke_auth;Username=poke;Password=poke" \
  Jwt__Issuer="PokeAuth" \
  Jwt__Audience="PokeApp" \
  Jwt__Key="super-secret-development-key-change-me" \
  Jwt__ExpirationHours="8" \
  dotnet run
) &
AUTH_PID=$!

echo "Levantando backend en http://localhost:5180 ..."
(cd "$BACK_DIR" && dotnet run) &
BACK_PID=$!

echo "Levantando frontend con Vite..."
(cd "$FRONT_DIR" && npm run dev) &
FRONT_PID=$!

wait -n "$BACK_PID" "$AUTH_PID" "$FRONT_PID"
