#!/usr/bin/env bash
set -euo pipefail

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

mvn clean package
java -jar target/agento-telegram-bot-0.0.1-SNAPSHOT.jar
