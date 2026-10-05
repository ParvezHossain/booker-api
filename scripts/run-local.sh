#!/usr/bin/env bash
set -euo pipefail

repo_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_directory"

if [[ ! -f .env ]]; then
  echo 'Create .env from .env.example and configure local credentials first.' >&2
  exit 1
fi

# Load trusted, shell-compatible local settings and export them to Maven/Java.
set -a
source ./.env
set +a

: "${DATABASE_PASSWORD:?Set DATABASE_PASSWORD in .env}"
: "${JWT_SECRET:?Set JWT_SECRET in .env}"
if [[ "${BOOK_REQUEST_EMAIL_ENABLED:-true}" == true || "${PASSWORD_RESET_EMAIL_ENABLED:-true}" == true \
    || "${PASSWORD_CHANGE_EMAIL_ENABLED:-true}" == true || "${EMAIL_ACTIVATION_EMAIL_ENABLED:-true}" == true ]]; then
  : "${RABBITMQ_PASSWORD:?Set RABBITMQ_PASSWORD in .env}"
fi

exec ./mvnw spring-boot:run "$@"
